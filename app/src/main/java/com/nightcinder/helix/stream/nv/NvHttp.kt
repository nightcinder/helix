package com.nightcinder.helix.stream.nv

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NvHttpException(message: String, val statusCode: Int = -1, cause: Throwable? = null) : IOException(message, cause)

data class NvServerInfo(
    val hostname: String,
    val appVersion: String,
    val gfeVersion: String,
    val httpsPort: Int,
    val pairStatus: Int,
    val currentGame: Int,
    val state: String,
    val serverCodecModeSupport: Int,
    val virtualDisplayCapable: Boolean = false,
    val virtualDisplayDriverReady: Boolean = false,
    val virtualDisplayHdrCapable: Boolean = false,
) {
    val isPaired: Boolean get() = pairStatus == 1

    /** Major protocol version, the first dotted component of `appversion` (e.g. 7 for "7.1.431.-1"). */
    val majorVersion: Int get() = appVersion.substringBefore('.').toIntOrNull() ?: 0
}

data class NvApp(val id: Int, val title: String, val isHdrSupported: Boolean = false)

data class NvLaunchResponse(val sessionUrl: String?, val raw: String)

/** The slice of [NvHttp] that app discovery and `/launch` need; lets tests inject a host double. */
interface NvHostApi {
    /** True once the host's certificate is pinned, i.e. this client has paired with it. */
    val isPinned: Boolean

    suspend fun getServerInfo(): NvServerInfo

    suspend fun getAppList(): List<NvApp>

    suspend fun launch(params: NvLaunchParams): NvLaunchResponse

    suspend fun resume(params: NvLaunchParams): NvLaunchResponse

    suspend fun cancel(): Boolean
}

/** Parameters of `/launch` and `/resume`. */
data class NvLaunchParams(
    val appId: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val riKey: ByteArray,
    val riKeyId: Int,
    val surroundAudioInfo: Int = STEREO_SURROUND_AUDIO_INFO,
    /** Extra query parameters from moonlight-common-c (`LiGetLaunchUrlQueryParameters()`), already `&`-prefixed. */
    val extraQuery: String = "",
) {
    companion object {
        /** (channelMask 0x3 << 16) | channelCount 2. */
        const val STEREO_SURROUND_AUDIO_INFO = (0x3 shl 16) or 2
    }
}

/**
 * Minimal GameStream/Sunshine HTTP client (serverinfo, applist, launch, resume, cancel, pairing, unpair).
 *
 * Deliberately built from a fresh [OkHttpClient.Builder] (never the app's shared client) so the Termix JWT
 * and cookie jar are not sent to the host. HTTPS presents the client certificate and trusts only [serverCert].
 */
class NvHttp(
    val host: String,
    private val uniqueId: String,
    private val identity: ClientIdentity?,
    @Volatile var serverCert: X509Certificate? = null,
    private val httpPort: Int = DEFAULT_HTTP_PORT,
    @Volatile var httpsPort: Int = DEFAULT_HTTPS_PORT,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
) : PairingTransport, NvHostApi {

    override val isPinned: Boolean get() = serverCert != null

    companion object {
        const val DEFAULT_HTTP_PORT = 47989
        const val DEFAULT_HTTPS_PORT = 47984
        private const val DEVICE_NAME = "helix"
        private val CLIPBOARD_TEXT_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaType()

        fun parseXml(xml: String): Document {
            val factory = DocumentBuilderFactory.newInstance()
            // The host response is untrusted input: never resolve DTDs or external entities.
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            factory.isExpandEntityReferences = false
            return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }

        /** First descendant element text with this tag name, or null. */
        fun xmlString(doc: Document, tag: String): String? {
            val nodes = doc.getElementsByTagName(tag)
            if (nodes.length == 0) return null
            return nodes.item(0).textContent?.trim()
        }

        /** Throws [NvHttpException] when the root `status_code` is not 200. Returns the parsed document. */
        fun parseResponse(xml: String): Document {
            val doc = try {
                parseXml(xml)
            } catch (e: Exception) {
                throw NvHttpException("Malformed host response", cause = e)
            }
            val root = doc.documentElement as Element
            val code = root.getAttribute("status_code").toIntOrNull() ?: 200
            if (code != 200) {
                val msg = root.getAttribute("status_message").ifBlank { "Host returned status $code" }
                throw NvHttpException(msg, code)
            }
            return doc
        }

        /** "Class: message" for [e] and its causes (max 3), joined with " <- ". Holds no request, cert or key data. */
        fun describeFailure(e: Throwable): String {
            val parts = ArrayList<String>()
            var cur: Throwable? = e
            while (cur != null && parts.size < 3) {
                parts += cur.javaClass.name + (cur.message?.let { ": $it" } ?: "")
                val next = cur.cause
                cur = if (next === cur) null else next
            }
            return parts.joinToString(" <- ")
        }

        fun parseServerInfo(xml: String): NvServerInfo {
            val doc = parseResponse(xml)
            return NvServerInfo(
                hostname = xmlString(doc, "hostname").orEmpty(),
                appVersion = xmlString(doc, "appversion") ?: throw NvHttpException("serverinfo has no appversion"),
                gfeVersion = xmlString(doc, "GfeVersion").orEmpty(),
                httpsPort = xmlString(doc, "HttpsPort")?.toIntOrNull() ?: DEFAULT_HTTPS_PORT,
                pairStatus = xmlString(doc, "PairStatus")?.toIntOrNull() ?: 0,
                currentGame = xmlString(doc, "currentgame")?.toIntOrNull() ?: 0,
                state = xmlString(doc, "state").orEmpty(),
                serverCodecModeSupport = xmlString(doc, "ServerCodecModeSupport")?.toIntOrNull() ?: 0,
                virtualDisplayCapable = xmlString(doc, "VirtualDisplayCapable").toBoolFlag(),
                virtualDisplayDriverReady = xmlString(doc, "VirtualDisplayDriverReady").toBoolFlag(),
                virtualDisplayHdrCapable = xmlString(doc, "VirtualDisplayHDRCapable").toBoolFlag(),
            )
        }

        private fun String?.toBoolFlag() = this?.trim().let { it.equals("true", ignoreCase = true) || it == "1" }

        fun parseAppList(xml: String): List<NvApp> {
            val doc = parseResponse(xml)
            val apps = doc.getElementsByTagName("App")
            val out = ArrayList<NvApp>(apps.length)
            for (i in 0 until apps.length) {
                val el = apps.item(i) as Element
                val id = el.getElementsByTagName("ID").item(0)?.textContent?.trim()?.toIntOrNull() ?: continue
                val title = el.getElementsByTagName("AppTitle").item(0)?.textContent?.trim().orEmpty()
                val hdr = el.getElementsByTagName("IsHdrSupported").item(0)?.textContent?.trim() == "1"
                out += NvApp(id, title, hdr)
            }
            return out
        }

        fun parseLaunch(xml: String): NvLaunchResponse {
            val doc = parseResponse(xml)
            return NvLaunchResponse(xmlString(doc, "sessionUrl0"), xml)
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** No read timeout: `getservercert` only answers once the owner has typed the PIN on the host. */
    private val longPollClient: OkHttpClient by lazy {
        httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    @Volatile
    private var httpsClientCache: Pair<X509Certificate, OkHttpClient>? = null

    private fun httpsClient(): OkHttpClient {
        val pinned = serverCert ?: throw NvHttpException("Host is not paired (no pinned certificate)")
        val id = identity ?: throw NvHttpException("No client identity")
        httpsClientCache?.let { (cert, client) -> if (cert === pinned) return client }
        val client = buildHttpsClient(httpClient, id, pinned)
        httpsClientCache = pinned to client
        return client
    }

    // --- URLs (public for tests/curl verification) ---

    private fun idQuery() = "uniqueid=$uniqueId&uuid=${uuidProvider()}"

    fun httpUrl(command: String, extra: String = ""): HttpUrl =
        "http://$host:$httpPort/$command?${idQuery()}${if (extra.isEmpty()) "" else "&$extra"}".toHttpUrl()

    fun httpsUrl(command: String, extra: String = ""): HttpUrl =
        "https://$host:$httpsPort/$command?${idQuery()}${if (extra.isEmpty()) "" else "&$extra"}".toHttpUrl()

    fun launchQuery(params: NvLaunchParams): String = buildString {
        append("appid=").append(params.appId)
        append("&mode=").append(params.width).append('x').append(params.height).append('x').append(params.fps)
        append("&additionalStates=1&sops=0")
        append("&rikey=").append(NvCrypto.bytesToHex(params.riKey))
        append("&rikeyid=").append(params.riKeyId)
        append("&localAudioPlayMode=0")
        append("&surroundAudioInfo=").append(params.surroundAudioInfo)
        if (params.extraQuery.isNotEmpty()) {
            if (!params.extraQuery.startsWith("&")) append('&')
            append(params.extraQuery)
        }
    }

    // --- API ---

    /** Unpaired: plain HTTP (PairStatus 0). Paired: HTTPS with the client cert, falling back to HTTP. */
    override suspend fun getServerInfo(): NvServerInfo {
        lastServerInfoViaHttps = false
        lastHttpsFailure = null
        if (serverCert != null && identity != null) {
            try {
                val info = parseServerInfo(execute(httpsClient(), httpsUrl("serverinfo")))
                httpsPort = info.httpsPort
                lastServerInfoViaHttps = true
                return info
            } catch (e: IOException) {
                lastHttpsFailure = describeFailure(e)
                Log.w("HelixNv", "HTTPS serverinfo failed, falling back to HTTP: $lastHttpsFailure", e)
                // Fall through to HTTP: the host may have dropped our pairing or changed its certificate.
                // Over HTTP Sunshine always reports PairStatus 0, so callers must not treat that as "unpaired".
            }
        }
        val info = parseServerInfo(execute(httpClient, httpUrl("serverinfo")))
        httpsPort = info.httpsPort
        return info
    }

    /** True when the last [getServerInfo] answer came over the authenticated HTTPS channel. */
    @Volatile var lastServerInfoViaHttps: Boolean = false
        private set

    /** [describeFailure] of the last HTTPS serverinfo failure in [getServerInfo], or null if none. */
    @Volatile var lastHttpsFailure: String? = null
        private set

    override suspend fun getAppList(): List<NvApp> = parseAppList(execute(httpsClient(), httpsUrl("applist")))

    override suspend fun launch(params: NvLaunchParams): NvLaunchResponse =
        parseLaunch(execute(httpsClient(), httpsUrl("launch", launchQuery(params))))

    override suspend fun resume(params: NvLaunchParams): NvLaunchResponse =
        parseLaunch(execute(httpsClient(), httpsUrl("resume", launchQuery(params))))

    override suspend fun cancel(): Boolean {
        val doc = parseResponse(execute(httpsClient(), httpsUrl("cancel")))
        return xmlString(doc, "cancel") == "1"
    }

    /** Pulls UTF-8 plain text from Vibepollo's active stream clipboard (`/actions/clipboard?type=text`). */
    suspend fun getClipboardText(): String {
        val req = Request.Builder()
            .url(httpsUrl("actions/clipboard", "type=text"))
            .get()
            .build()
        return executeStrict(httpsClient(), req)
    }

    /** Pushes UTF-8 plain text to Vibepollo's active stream clipboard (`/actions/clipboard?type=text`). */
    suspend fun setClipboardText(text: String) {
        val req = Request.Builder()
            .url(httpsUrl("actions/clipboard", "type=text"))
            .post(text.toRequestBody(CLIPBOARD_TEXT_MEDIA_TYPE))
            .build()
        executeStrict(httpsClient(), req)
    }

    // --- PairingTransport ---

    override suspend fun pair(args: String, longPoll: Boolean): String {
        val client = if (longPoll) longPollClient else httpClient
        return execute(client, httpUrl("pair", "devicename=$DEVICE_NAME&updateState=1&$args"))
    }

    override suspend fun pairChallengeHttps(serverCert: X509Certificate): String {
        this.serverCert = serverCert
        return execute(httpsClient(), httpsUrl("pair", "devicename=$DEVICE_NAME&updateState=1&phrase=pairchallenge"))
    }

    override suspend fun unpair() {
        runCatching { execute(httpClient, httpUrl("unpair")) }
    }

    // --- plumbing ---

    private suspend fun executeStrict(client: OkHttpClient, request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = try {
                            it.body?.string().orEmpty()
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                            return
                        }
                        if (!it.isSuccessful) {
                            val detail = body.trim().ifEmpty { "HTTP ${it.code}" }
                            if (cont.isActive) cont.resumeWithException(NvHttpException(detail, it.code))
                        } else if (cont.isActive) {
                            cont.resume(body)
                        }
                    }
                }
            })
        }

    private suspend fun execute(client: OkHttpClient, url: HttpUrl): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(Request.Builder().url(url).build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = try {
                            it.body?.string().orEmpty()
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                            return
                        }
                        if (!it.isSuccessful && body.isEmpty()) {
                            if (cont.isActive) cont.resumeWithException(NvHttpException("HTTP ${it.code}", it.code))
                        } else if (cont.isActive) {
                            cont.resume(body)
                        }
                    }
                }
            })
        }
}

/** Builds an OkHttp client that presents [identity] as TLS client auth and trusts exactly [pinned]. */
internal fun buildHttpsClient(base: OkHttpClient, identity: ClientIdentity, pinned: X509Certificate): OkHttpClient {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null)
        setKeyEntry("helix", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
    }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, CharArray(0))

    val trust = PinnedTrustManager(pinned)
    // A fresh SSLContext per connection: Conscrypt caches client sessions per context, and a resumed handshake
    // (TLS 1.3 PSK / 1.2 ticket) with client auth makes Vibepollo answer `internal_error` or stall until the read
    // timeout. moonlight-android avoids this by never reusing connections; here no session is ever resumable.
    val factory = FreshContextSocketFactory {
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trust), null) }
    }

    return base.newBuilder()
        .sslSocketFactory(factory, trust)
        // Never keep an authenticated connection alive: Vibepollo stalls every further TLS handshake (ours and
        // anyone's) while a previous client-auth connection from this client is still open, so the next call hung
        // until the read timeout. moonlight-android does the same (ConnectionPool(0, ...)).
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        // The host presents a self-signed certificate for an arbitrary CN; identity is the pinned cert.
        .hostnameVerifier { _, _ -> true }
        .build()
}

/** Accepts a server only when its leaf certificate is byte-identical to the one pinned during pairing. */
internal class PinnedTrustManager(private val pinned: X509Certificate) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw java.security.cert.CertificateException("Client trust not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Empty server chain")
        if (!leaf.encoded.contentEquals(pinned.encoded)) {
            throw java.security.cert.CertificateException("Server certificate does not match the pinned certificate")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinned)
}

/**
 * Hands every new socket a brand-new [SSLContext], so no TLS session can ever be resumed. Resuming a session on
 * Vibepollo's HTTPS port (client auth) fails with `internal_error` or hangs the handshake.
 */
internal class FreshContextSocketFactory(private val newContext: () -> SSLContext) : SSLSocketFactory() {
    private val probe: SSLSocketFactory by lazy { newContext().socketFactory }
    private fun fresh(): SSLSocketFactory = newContext().socketFactory

    override fun getDefaultCipherSuites(): Array<String> = probe.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = probe.supportedCipherSuites
    override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket =
        fresh().createSocket(s, host, port, autoClose)
    override fun createSocket(host: String, port: Int): java.net.Socket = fresh().createSocket(host, port)
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket =
        fresh().createSocket(host, port, localHost, localPort)
    override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket = fresh().createSocket(host, port)
    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): java.net.Socket =
        fresh().createSocket(address, port, localAddress, localPort)
}