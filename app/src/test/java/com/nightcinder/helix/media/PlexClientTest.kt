package com.nightcinder.helix.media

import com.nightcinder.helix.auth.InMemoryCryptoStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlexClientTest {
    private lateinit var server: MockWebServer
    private lateinit var routes: RoutingDispatcher
    private val store = InMemoryCryptoStore()
    private lateinit var client: PlexClient

    @Before
    fun setUp() {
        routes = RoutingDispatcher()
        server = MockWebServer().apply { dispatcher = routes; start() }
        client = PlexClient(OkHttpClient(), store, plexTvBase = server.base())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun devices(json: String): List<JsonObject> =
        (mediaJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonArray).map { it as JsonObject }

    @Test
    fun pinFlow_createsPinThenStoresTokenWhenApproved() = runTest {
        routes.json("/api/v2/pins?strong=true", """{"id":42,"code":"abcd1234"}""")
        var polls = 0
        routes.on("/api/v2/pins/42") {
            polls++
            MockResponse().setBody(if (polls < 2) """{"id":42,"authToken":null}""" else """{"id":42,"authToken":"plex-tok"}""")
        }
        val pin = client.createPin().getOrThrow()
        assertEquals(42L, pin.id)
        assertTrue(pin.authUrl.contains("code=abcd1234"))
        val create = routes.requestsTo("/api/v2/pins?strong=true").single()
        assertEquals("POST", create.method)
        assertEquals("Helix", create.getHeader("X-Plex-Product"))
        assertFalse(create.getHeader("X-Plex-Client-Identifier").isNullOrEmpty())

        assertFalse(client.pollPin(pin).getOrThrow())
        assertFalse(client.isSignedIn())
        assertTrue(client.pollPin(pin).getOrThrow())
        assertTrue(client.isSignedIn())
        assertEquals("plex-tok", store.getString(PlexClient.KEY_TOKEN))
        assertTrue(routes.requestsTo("/api/v2/pins/42").first().path.orEmpty().contains("code=abcd1234"))
    }

    @Test
    fun connectionOrder_localPlexDirect_thenRemote_thenRelay_httpsOnly() {
        val ordered = PlexClient.orderConnections(
            devices(
                """[{"provides":"client","connections":[{"uri":"https://not-a-server.plex.direct:32400","local":true}]},
                   {"provides":"server","connections":[
                     {"uri":"https://relay.plex.services:443","relay":true,"local":false},
                     {"uri":"https://203-0-113-9.abc.plex.direct:32400","local":false,"relay":false},
                     {"uri":"http://192.168.1.5:32400","local":true},
                     {"uri":"https://192-168-1-5.abc.plex.direct:32400","local":true,"relay":false},
                     {"uri":"https://192.168.1.5:32400","local":true}]}]""",
            ),
        )
        assertEquals(
            listOf(
                "https://192-168-1-5.abc.plex.direct:32400" to PlexRoute.LOCAL_PLEX_DIRECT,
                "https://203-0-113-9.abc.plex.direct:32400" to PlexRoute.REMOTE,
                "https://relay.plex.services:443" to PlexRoute.RELAY,
            ),
            ordered.map { it.uri to it.route },
        )
    }

    @Test
    fun serverConnections_requireSignInAndWipeOn401() = runTest {
        assertTrue(client.serverConnections().exceptionOrNull() is MediaApiException.NotSignedIn)
        store.putString(PlexClient.KEY_TOKEN, "stale")
        routes.json("/api/v2/resources", "", code = 401)
        assertTrue(client.serverConnections().exceptionOrNull() is MediaApiException.Unauthorized)
        assertFalse(client.isSignedIn())
    }

    @Test
    fun urlOverride_isHttpsOnlyAndWinsOverDiscovery() = runTest {
        val err = runCatching { client.setUrlOverride("http://192.168.1.5:32400") }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertNull(client.urlOverride())

        // The override base is tried first; here it is the mock server (stored directly: MockWebServer is plain http).
        store.putString(PlexClient.KEY_TOKEN, "tok")
        store.putString(PlexClient.KEY_OVERRIDE, server.base())
        routes.json("/identity", """{"MediaContainer":{}}""")
        routes.json("/api/v2/resources", "[]")
        assertEquals(server.base(), client.serverBase())
        assertEquals(0, routes.requestsTo("/api/v2/resources").size)
        assertEquals(server.base(), store.getString(PlexClient.KEY_SERVER))
    }

    @Test
    fun serverBase_withNoOverrideAndNoServers_failsWithoutHardcodedFallback() = runTest {
        store.putString(PlexClient.KEY_TOKEN, "tok")
        routes.json("/api/v2/resources", "[]")
        assertTrue(runCatching { client.serverBase() }.exceptionOrNull() is MediaApiException.Network)
        assertEquals(1, server.requestCount)
    }

    private suspend fun signedInWithServer() {
        store.putString(PlexClient.KEY_TOKEN, "tok")
        store.putString(PlexClient.KEY_SERVER, server.base())
    }

    @Test
    fun onDeck_parsesItemsAndUpNextIsNotYetStarted() = runTest {
        signedInWithServer()
        routes.json(
            "/library/onDeck",
            """{"MediaContainer":{"Metadata":[
               {"ratingKey":"10","title":"Movie","type":"movie","viewOffset":600000,"duration":7200000},
               {"ratingKey":"11","title":"Pilot","grandparentTitle":"Show","type":"episode","parentIndex":1,"index":2,"duration":1800000}]}}""",
        )
        val items = client.continueWatching().getOrThrow()
        assertEquals(2, items.size)
        assertEquals(600_000L, items[0].resumePositionMs)
        assertEquals("Show", items[1].title)
        assertEquals("S01E02 · Pilot", items[1].subtitle)
        assertEquals(listOf("11"), client.upNext().getOrThrow().map { it.id })
        assertEquals("tok", routes.requestsTo("/library/onDeck").first().getHeader("X-Plex-Token"))
    }

    @Test
    fun onDeck401_wipesTokenAndServer() = runTest {
        signedInWithServer()
        routes.json("/library/onDeck", "", code = 401)
        assertTrue(client.continueWatching().exceptionOrNull() is MediaApiException.Unauthorized)
        assertFalse(client.isSignedIn())
        assertNull(store.getString(PlexClient.KEY_SERVER))
    }

    @Test
    fun resolve_buildsDirectPartUrlWithTokenAndTracks() = runTest {
        signedInWithServer()
        routes.json(
            "/library/metadata/10",
            """{"MediaContainer":{"Metadata":[{"Media":[{"Part":[{"key":"/library/parts/77/1/file.mkv","Stream":[
               {"id":"1","streamType":1},
               {"id":"2","streamType":2,"displayTitle":"English (AC3 5.1)","languageTag":"en","selected":true},
               {"id":"3","streamType":3,"displayTitle":"English (SRT)","languageTag":"en","codec":"srt","key":"/library/streams/3"},
               {"id":"4","streamType":3,"displayTitle":"German (PGS)","languageTag":"de","codec":"pgs"}]}]}]}]}}""",
        )
        val stream = client.resolve(MediaItem(MediaProvider.PLEX, "10", "Movie")).getOrThrow()
        assertEquals("${server.base()}/library/parts/77/1/file.mkv?X-Plex-Token=tok", stream.url)
        assertEquals(listOf("English (AC3 5.1)"), stream.audio.map { it.label })
        assertEquals(listOf("English (SRT)", "German (PGS)"), stream.subtitles.map { it.label })
        assertEquals("application/x-subrip", stream.externalSubtitles.single().mimeType)
        assertTrue(stream.externalSubtitles.single().url.contains("X-Plex-Token=tok"))
    }

    @Test
    fun timeline_reportsStatesWithPosition() = runTest {
        signedInWithServer()
        routes.json("/:/timeline", "", code = 200)
        val item = MediaItem(MediaProvider.PLEX, "10", "Movie", durationMs = 5_000)
        val stream = ResolvedStream("u", emptyMap(), emptyList(), emptyList())
        client.reportStart(item, stream, 1_000)
        client.reportProgress(item, stream, 2_000, paused = true)
        client.reportStop(item, stream, 3_000)
        val paths = routes.requestsTo("/:/timeline").map { it.path.orEmpty() }
        assertTrue(paths[0].contains("state=playing") && paths[0].contains("time=1000"))
        assertTrue(paths[1].contains("state=paused") && paths[1].contains("time=2000"))
        assertTrue(paths[2].contains("state=stopped") && paths[2].contains("duration=5000"))
    }

    @Test
    fun cachedServerNetworkFailure_clearsCacheAndRediscoversOnce() = runTest {
        val deadServer = MockWebServer().apply { start() }
        val deadBase = deadServer.base()
        deadServer.shutdown()

        store.putString(PlexClient.KEY_TOKEN, "tok")
        store.putString(PlexClient.KEY_SERVER, deadBase)
        store.putString(PlexClient.KEY_OVERRIDE, server.base())

        routes.json("/identity", """{"MediaContainer":{}}""")
        routes.json(
            "/library/onDeck",
            """{"MediaContainer":{"Metadata":[{"ratingKey":"10","title":"Movie","type":"movie","duration":7200000}]}}""",
        )

        val items = client.continueWatching().getOrThrow()
        assertEquals(listOf("10"), items.map { it.id })
        assertEquals(server.base(), store.getString(PlexClient.KEY_SERVER))
        assertEquals(1, routes.requestsTo("/identity").size)
        assertEquals(1, routes.requestsTo("/library/onDeck").size)
    }
}
