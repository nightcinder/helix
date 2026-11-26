package com.nightcinder.helix.media

import com.nightcinder.helix.auth.InMemoryCryptoStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JellyfinClientTest {
    private lateinit var server: MockWebServer
    private lateinit var routes: RoutingDispatcher
    private val store = InMemoryCryptoStore()
    private lateinit var client: JellyfinClient

    @Before
    fun setUp() {
        routes = RoutingDispatcher()
        server = MockWebServer().apply { dispatcher = routes; start() }
        client = JellyfinClient(OkHttpClient(), store, defaultBaseUrl = server.base())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun authBody(token: String = "tok-1", user: String = "u-1") =
        """{"AccessToken":"$token","User":{"Id":"$user"}}"""

    @Test
    fun passwordLogin_storesSessionAndSendsMediaBrowserHeader() = runTest {
        routes.json("/Users/AuthenticateByName", authBody())
        assertTrue(client.signInWithPassword("alice", "pw").isSuccess)
        assertTrue(client.isSignedIn())
        assertEquals("tok-1", store.getString(JellyfinClient.KEY_TOKEN))
        val req = routes.requestsTo("/Users/AuthenticateByName").single()
        val header = req.getHeader("Authorization").orEmpty()
        assertTrue(header.startsWith("MediaBrowser Client=\"Helix\""))
        assertFalse(header.contains("Token="))
        assertTrue(req.body.readUtf8().contains("\"Pw\":\"pw\""))
    }

    @Test
    fun passwordLogin_401_isUnauthorizedAndStoresNothing() = runTest {
        routes.json("/Users/AuthenticateByName", "", code = 401)
        val result = client.signInWithPassword("alice", "bad")
        assertTrue(result.exceptionOrNull() is MediaApiException.Unauthorized)
        assertFalse(client.isSignedIn())
    }

    @Test
    fun quickConnect_initiatePollThenAuthenticate() = runTest {
        routes.json("/QuickConnect/Initiate", """{"Secret":"sec","Code":"123456"}""")
        var polls = 0
        routes.on("/QuickConnect/Connect") {
            polls++
            MockResponse().setBody("""{"Authenticated":${polls >= 2}}""")
        }
        routes.json("/Users/AuthenticateWithQuickConnect", authBody("qc-tok"))

        val ticket = client.quickConnectInitiate().getOrThrow()
        assertEquals("123456", ticket.code)
        assertFalse(client.quickConnectApproved(ticket.secret).getOrThrow())
        assertTrue(client.quickConnectApproved(ticket.secret).getOrThrow())
        assertTrue(client.signInWithQuickConnect(ticket.secret).isSuccess)
        assertEquals("qc-tok", store.getString(JellyfinClient.KEY_TOKEN))
        assertTrue(routes.requestsTo("/QuickConnect/Connect").first().path.orEmpty().contains("secret=sec"))
        assertTrue(routes.requestsTo("/Users/AuthenticateWithQuickConnect").single().body.readUtf8().contains("\"Secret\":\"sec\""))
    }

    @Test
    fun continueWatchingAndNextUp_parseTicksAndEpisodeLabels() = runTest {
        store.putString(JellyfinClient.KEY_TOKEN, "t")
        store.putString(JellyfinClient.KEY_USER_ID, "u-1")
        routes.json(
            "/Users/u-1/Items/Resume",
            """{"Items":[{"Id":"m1","Name":"Movie","Type":"Movie","RunTimeTicks":72000000000,
               "UserData":{"PlaybackPositionTicks":6000000000}}]}""",
        )
        routes.json(
            "/Shows/NextUp",
            """{"Items":[{"Id":"e2","Name":"Pilot","SeriesName":"Show","Type":"Episode","ParentIndexNumber":1,"IndexNumber":2}]}""",
        )
        val resume = client.continueWatching().getOrThrow().single()
        assertEquals(600_000L, resume.resumePositionMs)
        assertEquals(7_200_000L, resume.durationMs)
        val next = client.upNext().getOrThrow().single()
        assertEquals("Show", next.title)
        assertEquals("S01E02 · Pilot", next.subtitle)
        assertEquals("MediaBrowser", routes.requestsTo("/Users/u-1/Items/Resume").single().getHeader("Authorization")!!.substringBefore(' '))
        assertTrue(routes.requestsTo("/Users/u-1/Items/Resume").single().getHeader("Authorization")!!.contains("Token=\"t\""))
    }

    @Test
    fun library401_wipesSession() = runTest {
        store.putString(JellyfinClient.KEY_TOKEN, "stale")
        store.putString(JellyfinClient.KEY_USER_ID, "u-1")
        routes.json("/Users/u-1/Items/Resume", "", code = 401)
        assertTrue(client.continueWatching().exceptionOrNull() is MediaApiException.Unauthorized)
        assertFalse(client.isSignedIn())
        assertNull(store.getString(JellyfinClient.KEY_TOKEN))
    }

    @Test
    fun library_whenSignedOut_failsWithoutNetwork() = runTest {
        assertTrue(client.continueWatching().exceptionOrNull() is MediaApiException.NotSignedIn)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun resolve_buildsDirectPlayUrlTracksAndExternalSubtitles() = runTest {
        store.putString(JellyfinClient.KEY_TOKEN, "t")
        store.putString(JellyfinClient.KEY_USER_ID, "u-1")
        routes.json(
            "/Items/m1/PlaybackInfo",
            """{"PlaySessionId":"ps1","MediaSources":[{"Id":"src1","SupportsDirectPlay":true,"MediaStreams":[
               {"Index":0,"Type":"Video","Codec":"hevc"},
               {"Index":1,"Type":"Audio","Language":"eng","DisplayTitle":"English 5.1","IsDefault":true},
               {"Index":2,"Type":"Audio","Language":"jpn","DisplayTitle":"Japanese"},
               {"Index":3,"Type":"Subtitle","Language":"eng","DisplayTitle":"English","IsExternal":true,"Codec":"srt","DeliveryUrl":"/Videos/m1/src1/Subtitles/3/0/Stream.srt"},
               {"Index":4,"Type":"Subtitle","Language":"deu","DisplayTitle":"German","IsExternal":false}]}]}""",
        )
        val stream = client.resolve(MediaItem(MediaProvider.JELLYFIN, "m1", "Movie")).getOrThrow()
        assertTrue(stream.url.contains("/Videos/m1/stream"))
        assertTrue(stream.url.contains("static=true"))
        assertTrue(stream.url.contains("MediaSourceId=src1"))
        assertEquals(listOf("English 5.1", "Japanese"), stream.audio.map { it.label })
        assertEquals(listOf("English", "German"), stream.subtitles.map { it.label })
        val ext = stream.externalSubtitles.single()
        assertEquals("application/x-subrip", ext.mimeType)
        assertTrue(ext.url.endsWith("/Videos/m1/src1/Subtitles/3/0/Stream.srt"))
        assertTrue(stream.headers.getValue("Authorization").contains("Token=\"t\""))
    }

    @Test
    fun resolve_refusesWhenDirectPlayUnavailable() = runTest {
        store.putString(JellyfinClient.KEY_TOKEN, "t")
        store.putString(JellyfinClient.KEY_USER_ID, "u-1")
        routes.json("/Items/m1/PlaybackInfo", """{"MediaSources":[{"Id":"s","SupportsDirectPlay":false}]}""")
        assertTrue(client.resolve(MediaItem(MediaProvider.JELLYFIN, "m1", "Movie")).exceptionOrNull() is MediaApiException.Unsupported)
    }

    @Test
    fun playbackReports_postTicksAndPausedFlag() = runTest {
        store.putString(JellyfinClient.KEY_TOKEN, "t")
        store.putString(JellyfinClient.KEY_USER_ID, "u-1")
        routes.json("/Sessions/Playing", "", code = 204)
        val item = MediaItem(MediaProvider.JELLYFIN, "m1", "Movie")
        val stream = ResolvedStream("u", emptyMap(), emptyList(), emptyList(), sessionRef = "src1|ps1")
        client.reportStart(item, stream, 1_000)
        client.reportProgress(item, stream, 2_000, paused = true)
        client.reportStop(item, stream, 3_000)
        val bodies = routes.requestsTo("/Sessions/Playing").map { it.path.orEmpty() to it.body.readUtf8() }
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"), bodies.map { it.first })
        assertTrue(bodies[1].second.contains("\"PositionTicks\":20000000"))
        assertTrue(bodies[1].second.contains("\"IsPaused\":true"))
        assertTrue(bodies[0].second.contains("\"PlaySessionId\":\"ps1\""))
    }

    @Test
    fun baseUrlOverride_mustBeHttps() = runTest {
        client.setBaseUrlOverride("https://jellyfin.example:8443/")
        assertEquals("https://jellyfin.example:8443", client.baseUrl())
        val err = runCatching { client.setBaseUrlOverride("http://insecure") }.exceptionOrNull()
        assertNotNull(err)
        client.setBaseUrlOverride(null)
        assertEquals(server.base(), client.baseUrl())
    }
}
