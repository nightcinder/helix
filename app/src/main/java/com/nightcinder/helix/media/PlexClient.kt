package com.nightcinder.helix.media

import com.nightcinder.helix.auth.CryptoStore
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

data class PlexPin(val id: Long, val code: String, val authUrl: String)

enum class PlexRoute { LOCAL_PLEX_DIRECT, REMOTE, RELAY }

data class PlexConnection(val uri: String, val route: PlexRoute)

/**
 * Plex client (ONY-1754 #D3 option 1): plex.tv PIN flow -> X-Plex-Token in [CryptoStore]; server picked from the
 * plex.tv resources list, strictly `local *.plex.direct -> remote -> relay`, HTTPS only, with an optional user HTTPS
 * override tried first. There is no hardcoded server fallback and no cleartext LAN exception.
 */
class PlexClient(
    private val client: OkHttpClient,
    private val store: CryptoStore,
    private val plexTvBase: String = PLEX_TV,
    private val product: String = "Helix",
    private val clientVersion: String = "0.1.0",
) : MediaProviderClient {

    override val provider = MediaProvider.PLEX

    override suspend fun isSignedIn(): Boolean = store.getString(KEY_TOKEN) != null

    override suspend fun signOut() {
        store.remove(KEY_TOKEN)
        store.remove(KEY_SERVER)
    }

    suspend fun urlOverride(): String? = store.getString(KEY_OVERRIDE)

    suspend fun setUrlOverride(url: String?) {
        if (url.isNullOrBlank()) {
            store.remove(KEY_OVERRIDE)
            store.remove(KEY_SERVER)
            return
        }
        val parsed = runCatching { url.trim().toHttpUrl() }.getOrNull()
        require(parsed != null && parsed.isHttps) { "Plex URL override must be https" }
        store.putString(KEY_OVERRIDE, parsed.toString().trimEnd('/'))
        store.remove(KEY_SERVER)
    }

    // --- plex.tv PIN flow ---

    suspend fun createPin(): Result<PlexPin> = runCatchingApi {
        val clientId = clientId()
        val url = "$plexTvBase/api/v2/pins".toHttpUrl().newBuilder().addQueryParameter("strong", "true").build()
        val req = plexTvRequest(url, clientId).post(ByteArray(0).toRequestBody()).build()
        val obj = parseObject(client.execute(req).ok())
        val id = obj.long("id") ?: throw MediaApiException.Invalid("Missing pin id")
        val code = obj.str("code") ?: throw MediaApiException.Invalid("Missing pin code")
        PlexPin(id, code, authAppUrl(clientId, code))
    }

    private fun authAppUrl(clientId: String, code: String): String =
        "https://app.plex.tv/auth#?clientID=$clientId&code=$code&context%5Bdevice%5D%5Bproduct%5D=$product"

    /** True once the user approved the PIN and the token was stored; false while still pending. */
    suspend fun pollPin(pin: PlexPin): Result<Boolean> = runCatchingApi {
        val url = "$plexTvBase/api/v2/pins/${pin.id}".toHttpUrl().newBuilder().addQueryParameter("code", pin.code).build()
        val obj = parseObject(client.execute(plexTvRequest(url, clientId()).build()).ok())
        val token = obj.str("authToken")
        if (token.isNullOrEmpty()) {
            false
        } else {
            store.putString(KEY_TOKEN, token)
            true
        }
    }

    // --- Server selection ---

    /** plex.tv resources: ordered, https-only server connections. */
    suspend fun serverConnections(): Result<List<PlexConnection>> = runCatchingApi {
        val token = requireToken()
        val url = "$plexTvBase/api/v2/resources".toHttpUrl().newBuilder()
            .addQueryParameter("includeHttps", "1").addQueryParameter("includeRelay", "1").build()
        val body = client.execute(plexTvRequest(url, clientId(), token).build()).ok(onUnauthorized = { signOut() })
        val devices = mediaJson.parseToJsonElement(body).arr().mapNotNull { it.obj() }
        orderConnections(devices)
    }

    /** Selected base URL: cached choice, else override, else first reachable connection in preference order. */
    suspend fun serverBase(): String {
        store.getString(KEY_SERVER)?.let { return it }
        // The override is probed before plex.tv is even consulted; discovery only runs when it is unset or dead.
        urlOverride()?.let { override ->
            if (reachable(override)) return override.also { store.putString(KEY_SERVER, it) }
        }
        for (candidate in serverConnections().getOrThrow().map { it.uri }) {
            if (reachable(candidate)) {
                store.putString(KEY_SERVER, candidate)
                return candidate
            }
        }
        throw MediaApiException.Network("No reachable Plex server connection")
    }

    private suspend fun reachable(base: String): Boolean = try {
        val url = base.toHttpUrl().newBuilder().addPathSegment("identity").build()
        client.execute(plexTvRequest(url, clientId()).build()).code in 200..299
    } catch (e: MediaApiException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    // --- Library ---

    override suspend fun continueWatching(): Result<List<MediaItem>> = runCatchingApi {
        val root = serverGet("/library/onDeck")
        root.obj("MediaContainer")?.get("Metadata").arr().mapNotNull { it.obj()?.toItem() }
    }

    /** Plex On Deck already merges in-progress and next episodes, so Up Next is the not-yet-started subset. */
    override suspend fun upNext(): Result<List<MediaItem>> =
        continueWatching().map { items -> items.filter { it.resumePositionMs == 0L } }

    private fun JsonObject.toItem(): MediaItem? {
        val key = str("ratingKey") ?: return null
        val title = str("grandparentTitle") ?: str("title") ?: return null
        val episode = if (str("type") == "episode") {
            val s = long("parentIndex")
            val e = long("index")
            if (s != null && e != null) "S%02dE%02d".format(s, e) else null
        } else {
            null
        }
        val sub = listOfNotNull(episode, str("title").takeIf { str("grandparentTitle") != null }).joinToString(" · ")
        return MediaItem(
            provider = MediaProvider.PLEX,
            id = key,
            title = title,
            subtitle = sub.ifEmpty { null },
            resumePositionMs = long("viewOffset") ?: 0L,
            durationMs = long("duration") ?: 0L,
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key].obj()

    override suspend fun resolve(item: MediaItem): Result<ResolvedStream> = runCatchingApi {
        val root = serverGet("/library/metadata/${item.id}")
        val meta = root.obj("MediaContainer")?.get("Metadata").arr().firstOrNull().obj()
            ?: throw MediaApiException.Unsupported("Item not found")
        val part = meta["Media"].arr().firstOrNull().obj()?.get("Part").arr().firstOrNull().obj()
            ?: throw MediaApiException.Unsupported("No playable media part")
        val partKey = part.str("key") ?: throw MediaApiException.Invalid("Missing part key")
        val base = serverBase()
        val token = requireToken()
        val audio = ArrayList<ProviderStream>()
        val subs = ArrayList<ProviderStream>()
        val external = ArrayList<ExternalSubtitle>()
        for (s in part["Stream"].arr().mapNotNull { it.obj() }) {
            val id = s.str("id") ?: s.long("id")?.toString() ?: continue
            val label = s.str("displayTitle") ?: s.str("language") ?: "Track $id"
            val stream = ProviderStream(id, label, s.str("languageTag"), s.bool("selected") == true)
            when (s.long("streamType")) {
                2L -> audio += stream
                3L -> {
                    subs += stream
                    val key = s.str("key")
                    if (key != null) {
                        external += ExternalSubtitle(
                            url = withToken("$base$key", token),
                            label = label,
                            language = s.str("languageTag"),
                            mimeType = if (s.str("codec") == "srt") "application/x-subrip" else "text/vtt",
                        )
                    }
                }
            }
        }
        ResolvedStream(
            url = withToken("$base$partKey", token),
            headers = emptyMap(),
            audio = audio,
            subtitles = subs,
            externalSubtitles = external,
            sessionRef = item.id,
        )
    }

    private fun withToken(url: String, token: String): String =
        url.toHttpUrl().newBuilder().setQueryParameter("X-Plex-Token", token).build().toString()

    // --- Timeline reporting (best effort) ---

    override suspend fun reportStart(item: MediaItem, stream: ResolvedStream, positionMs: Long) =
        timeline(item, "playing", positionMs)

    override suspend fun reportProgress(item: MediaItem, stream: ResolvedStream, positionMs: Long, paused: Boolean) =
        timeline(item, if (paused) "paused" else "playing", positionMs)

    override suspend fun reportStop(item: MediaItem, stream: ResolvedStream, positionMs: Long) =
        timeline(item, "stopped", positionMs)

    private suspend fun timeline(item: MediaItem, state: String, positionMs: Long) {
        runCatchingApi {
            withServerBase { base ->
                val url = base.toHttpUrl().newBuilder().addPathSegments(":/timeline")
                    .addQueryParameter("ratingKey", item.id)
                    .addQueryParameter("key", "/library/metadata/${item.id}")
                    .addQueryParameter("state", state)
                    .addQueryParameter("time", positionMs.toString())
                    .addQueryParameter("duration", item.durationMs.toString())
                    .build()
                client.execute(plexTvRequest(url, clientId(), requireToken()).build()).ok(onUnauthorized = { signOut() })
            }
        }
    }

    // --- HTTP plumbing ---

    private suspend fun <T> withServerBase(block: suspend (base: String) -> T): T {
        val hadCached = store.getString(KEY_SERVER) != null
        val base = serverBase()
        return try {
            block(base)
        } catch (e: MediaApiException.Network) {
            if (!hadCached) throw e
            store.remove(KEY_SERVER)
            block(serverBase())
        }
    }

    private suspend fun serverGet(path: String): JsonObject = withServerBase { base ->
        val url = base.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).build()
        val body = client.execute(plexTvRequest(url, clientId(), requireToken()).build()).ok(onUnauthorized = { signOut() })
        parseObject(body)
    }

    private suspend fun requireToken(): String = store.getString(KEY_TOKEN) ?: throw MediaApiException.NotSignedIn()

    private suspend fun clientId(): String =
        store.getString(KEY_CLIENT_ID) ?: UUID.randomUUID().toString().also { store.putString(KEY_CLIENT_ID, it) }

    private fun plexTvRequest(url: HttpUrl, clientId: String, token: String? = null): Request.Builder =
        Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Plex-Product", product)
            .header("X-Plex-Version", clientVersion)
            .header("X-Plex-Client-Identifier", clientId)
            .header("X-Plex-Platform", "Android")
            .header("X-Plex-Device-Name", "Helix XR")
            .apply { if (token != null) header("X-Plex-Token", token) }

    private inline fun <T> runCatchingApi(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: MediaApiException) {
            Result.failure(e)
        }

    companion object {
        const val PLEX_TV = "https://plex.tv"
        const val KEY_TOKEN = "media.plex.token"
        const val KEY_CLIENT_ID = "media.plex.clientId"
        const val KEY_OVERRIDE = "media.plex.urlOverride"
        const val KEY_SERVER = "media.plex.server"

        /**
         * Strict preference: local `*.plex.direct` -> remote -> relay. HTTPS only; plain-http LAN addresses are
         * dropped (no cleartext exception exists in network_security_config.xml). Order within a class is kept.
         */
        internal fun orderConnections(devices: List<JsonObject>): List<PlexConnection> {
            val all = devices
                .filter { it.str("provides").orEmpty().split(',').any { p -> p.trim() == "server" } }
                .flatMap { it["connections"].arr().mapNotNull { c -> c.obj() } }
                .mapNotNull { c ->
                    val uri = c.str("uri")?.trimEnd('/') ?: return@mapNotNull null
                    val parsed = runCatching { uri.toHttpUrl() }.getOrNull() ?: return@mapNotNull null
                    if (!parsed.isHttps) return@mapNotNull null
                    val route = when {
                        c.bool("relay") == true -> PlexRoute.RELAY
                        c.bool("local") == true && parsed.host.endsWith(".plex.direct") -> PlexRoute.LOCAL_PLEX_DIRECT
                        c.bool("local") == true -> return@mapNotNull null
                        else -> PlexRoute.REMOTE
                    }
                    PlexConnection(uri, route)
                }
            return PlexRoute.entries.flatMap { r -> all.filter { it.route == r } }
        }
    }
}
