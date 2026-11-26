package com.nightcinder.helix.stream.nv

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException

class NvHttpTest {

    // Captured from `curl http://192.0.2.20:47989/serverinfo` on the real Vibepollo host (unpaired client).
    private val serverInfoXml = """<?xml version="1.0" encoding="utf-8"?>
<root status_code="200"><hostname>Desktop</hostname><appversion>7.1.431.-1</appversion><GfeVersion>3.23.0.74</GfeVersion><uniqueid>6223EB02-46C4-6F2F-C896-9F850C6C46C3</uniqueid><HttpsPort>47984</HttpsPort><ExternalPort>47989</ExternalPort><mac>00:00:00:00:00:00</mac><LocalIP>192.0.2.20</LocalIP><ServerCodecModeSupport>126092033</ServerCodecModeSupport><PairStatus>0</PairStatus><currentgame>0</currentgame><currentgameuuid/><state>SUNSHINE_SERVER_FREE</state></root>"""

    private val http = NvHttp(
        host = "192.0.2.20",
        uniqueId = "0123456789ABCDEF",
        identity = null,
        uuidProvider = { "UUID-1" },
    )

    @Test
    fun serverInfo_parsesRealHostResponse() {
        val info = NvHttp.parseServerInfo(serverInfoXml)
        assertEquals("Desktop", info.hostname)
        assertEquals("7.1.431.-1", info.appVersion)
        assertEquals(7, info.majorVersion)
        assertEquals("3.23.0.74", info.gfeVersion)
        assertEquals(47984, info.httpsPort)
        assertEquals(126092033, info.serverCodecModeSupport)
        assertFalse(info.isPaired)
        assertEquals(0, info.currentGame)
        assertEquals("SUNSHINE_SERVER_FREE", info.state)
    }

    @Test
    fun serverInfo_parsesVirtualDisplayReadiness() {
        // Fields as served live by desktop (Vibepollo) at 192.0.2.20:47989/serverinfo.
        val xml = serverInfoXml.replace(
            "<mac>",
            "<VirtualDisplayCapable>true</VirtualDisplayCapable><VirtualDisplayDriverReady>true</VirtualDisplayDriverReady>" +
                "<VirtualDisplayHDRCapable>false</VirtualDisplayHDRCapable><mac>",
        )
        val info = NvHttp.parseServerInfo(xml)
        assertTrue(info.virtualDisplayCapable)
        assertTrue(info.virtualDisplayDriverReady)
        assertFalse(info.virtualDisplayHdrCapable)
        assertFalse(NvHttp.parseServerInfo(serverInfoXml).virtualDisplayCapable)
    }

    @Test
    fun describeFailure_listsClassMessageAndCauses() {
        val e = java.io.IOException("outer", IllegalStateException("inner"))
        assertEquals(
            "java.io.IOException: outer <- java.lang.IllegalStateException: inner",
            NvHttp.describeFailure(e),
        )
    }

    @Test
    fun urls_matchGameStreamEndpoints() {
        assertEquals(
            "http://192.0.2.20:47989/serverinfo?uniqueid=0123456789ABCDEF&uuid=UUID-1",
            http.httpUrl("serverinfo").toString(),
        )
        assertEquals(
            "https://192.0.2.20:47984/applist?uniqueid=0123456789ABCDEF&uuid=UUID-1",
            http.httpsUrl("applist").toString(),
        )
        assertEquals(
            "http://192.0.2.20:47989/pair?uniqueid=0123456789ABCDEF&uuid=UUID-1&devicename=helix&updateState=1&phrase=getservercert",
            http.httpUrl("pair", "devicename=helix&updateState=1&phrase=getservercert").toString(),
        )
    }

    @Test
    fun launchQuery_hasModeRikeyStereoAudioAndNoLocalAudio() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val params = NvLaunchParams(appId = 881448767, width = 1920, height = 1200, fps = 60, riKey = key, riKeyId = -12345)
        assertEquals(
            "appid=881448767&mode=1920x1200x60&additionalStates=1&sops=0" +
                "&rikey=0102030405060708090A0B0C0D0E0F10&rikeyid=-12345&localAudioPlayMode=0&surroundAudioInfo=196610",
            http.launchQuery(params),
        )
        assertEquals(196610, NvLaunchParams.STEREO_SURROUND_AUDIO_INFO)
    }

    @Test
    fun launchQuery_appendsLibraryParametersWithSeparator() {
        val key = ByteArray(16)
        val q = http.launchQuery(NvLaunchParams(1, 1280, 720, 30, key, 1, extraQuery = "&foo=1"))
        assertTrue(q.endsWith("&surroundAudioInfo=196610&foo=1"))
        val q2 = http.launchQuery(NvLaunchParams(1, 1280, 720, 30, key, 1, extraQuery = "foo=1"))
        assertTrue(q2.endsWith("&surroundAudioInfo=196610&foo=1"))
    }

    @Test
    fun appList_parsesDesktopEntry() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><root status_code="200">
            <App><IsHdrSupported>1</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>
            <App><IsHdrSupported>0</IsHdrSupported><AppTitle>Steam Big Picture</AppTitle><ID>1093255277</ID></App></root>"""
        val apps = NvHttp.parseAppList(xml)
        assertEquals(2, apps.size)
        assertEquals(NvApp(881448767, "Desktop", true), apps[0])
        assertEquals("Steam Big Picture", apps[1].title)
    }

    @Test
    fun launchResponse_extractsSessionUrl() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><root status_code="200"><sessionUrl0>rtspenc://192.0.2.20:48010</sessionUrl0><gamesession>1</gamesession></root>"""
        assertEquals("rtspenc://192.0.2.20:48010", NvHttp.parseLaunch(xml).sessionUrl)
        assertNull(NvHttp.parseLaunch("""<root status_code="200"></root>""").sessionUrl)
    }

    @Test
    fun nonOkStatusCode_throwsWithHostMessage() {
        val xml = """<?xml version="1.0"?><root status_code="401" status_message="The client is not authorized."/>"""
        val e = assertThrows(NvHttpException::class.java) { NvHttp.parseResponse(xml) }
        assertEquals(401, e.statusCode)
        assertEquals("The client is not authorized.", e.message)
    }

    @Test
    fun doctypeIsRejected_noExternalEntityExpansion() {
        val evil = """<?xml version="1.0"?><!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]><root status_code="200">&x;</root>"""
        assertThrows(NvHttpException::class.java) { NvHttp.parseResponse(evil) }
    }

    @Test
    fun pinnedTrustManager_acceptsOnlyThePinnedCertificate() {
        val pinned = ClientIdentity.generate().certificate
        val other = ClientIdentity.generate().certificate
        val tm = PinnedTrustManager(pinned)
        tm.checkServerTrusted(arrayOf(pinned), "RSA")
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(other), "RSA") }
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(emptyArray(), "RSA") }
    }

    @Test
    fun freshContextSocketFactory_buildsNewContextPerSocket_soNoSessionIsResumed() {
        var contexts = 0
        val factory = FreshContextSocketFactory {
            contexts++
            javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, null, null) }
        }
        java.net.ServerSocket(0).use { server ->
            repeat(3) {
                java.net.Socket("127.0.0.1", server.localPort).use { raw ->
                    factory.createSocket(raw, "127.0.0.1", server.localPort, false).close()
                }
            }
        }
        // 1 for the lazily built cipher-suite probe is not triggered here, so exactly one context per socket.
        assertEquals(3, contexts)
    }

    @Test
    fun buildHttpsClient_usesOwnPoolThatKeepsNoIdleConnection() {
        val base = okhttp3.OkHttpClient()
        val id = ClientIdentity.generate()
        val client = buildHttpsClient(base, id, id.certificate)
        // A dedicated zero-idle pool: no authenticated connection may stay open between calls.
        assertTrue(client.connectionPool !== base.connectionPool)
        assertEquals(0, client.connectionPool.idleConnectionCount())
    }

    @Test
    fun getServerInfo_requestsPlainHttpServerinfoWithUniqueId() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(serverInfoXml))
            server.start()
            val local = NvHttp(
                host = server.hostName,
                uniqueId = "0123456789ABCDEF",
                identity = null,
                httpPort = server.port,
                uuidProvider = { "UUID-2" },
            )
            val info = local.getServerInfo()
            assertEquals("Desktop", info.hostname)
            val req = server.takeRequest()
            assertEquals("/serverinfo?uniqueid=0123456789ABCDEF&uuid=UUID-2", req.path)
            assertNull(req.getHeader("Authorization"))
            assertNull(req.getHeader("Cookie"))
        }
    }

    @Test
    fun clipboard_urlsMatchVibepolloEndpoint() {
        assertEquals(
            "https://192.0.2.20:47984/actions/clipboard?uniqueid=0123456789ABCDEF&uuid=UUID-1&type=text",
            http.httpsUrl("actions/clipboard", "type=text").toString(),
        )
    }

    @Test
    fun clipboard_getAndPostUseTlsClientAuth_andThrowOnNon2xx() = runTest {
        val identity = ClientIdentity.generate()
        val serverIdentity = ClientIdentity.generate()
        val serverCert = serverIdentity.certificate
        val serverKs = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("server", serverIdentity.privateKey, CharArray(0), arrayOf(serverCert))
        }
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(serverKs, CharArray(0))
        }
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }

        MockWebServer().use { server ->
            server.useHttps(sslContext.socketFactory, false)
            server.enqueue(MockResponse().setResponseCode(200).setBody("from windows"))
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(400).setBody("Client is not connected to a stream"))
            server.start()

            val pairedHttp = NvHttp(
                host = server.hostName,
                uniqueId = "0123456789ABCDEF",
                identity = identity,
                serverCert = serverCert,
                httpsPort = server.port,
                uuidProvider = { "UUID-CLIP" },
            )

            assertEquals("from windows", pairedHttp.getClipboardText())
            val getReq = server.takeRequest()
            assertEquals("GET", getReq.method)
            assertEquals("/actions/clipboard?uniqueid=0123456789ABCDEF&uuid=UUID-CLIP&type=text", getReq.path)

            pairedHttp.setClipboardText("hello desktop")
            val postReq = server.takeRequest()
            assertEquals("POST", postReq.method)
            assertEquals("/actions/clipboard?uniqueid=0123456789ABCDEF&uuid=UUID-CLIP&type=text", postReq.path)
            assertEquals("hello desktop", postReq.body.readUtf8())

            val err = assertThrows(NvHttpException::class.java) {
                kotlinx.coroutines.runBlocking { pairedHttp.getClipboardText() }
            }
            assertEquals(400, err.statusCode)
            assertTrue(err.message!!.contains("not connected"))
        }
    }
}
