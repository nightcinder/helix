package com.nightcinder.helix.spotify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyWebApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }

    @After fun tearDown() { server.shutdown() }

    private suspend fun signedIn(env: TestEnv) {
        server.enqueue(MockResponse().setBody(tokenJson("access-1")))
        env.signInVia()
        server.takeRequest()
    }

    @Test
    fun parsesPlaylistsLikedSongsAndDevices() = runTest {
        val env = TestEnv(server, this)
        signedIn(env)
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"p1","name":"Mix","uri":"spotify:playlist:p1","tracks":{"total":3}}]}"""))
        server.enqueue(MockResponse().setBody("""{"items":[{"track":{"id":"t1","name":"Song","uri":"spotify:track:t1","artists":[{"name":"A"},{"name":"B"}]}}]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"id":"d1","name":"Aura","type":"Smartphone","is_active":true,"is_restricted":false},{"id":null,"name":"x","type":"y"}]}"""))
        assertEquals(listOf(SpotifyPlaylist("p1", "Mix", "spotify:playlist:p1", 3)), env.api.playlists().getOrThrow())
        assertEquals("A, B", env.api.likedSongs().getOrThrow().single().artists)
        val devices = env.api.devices().getOrThrow()
        assertEquals(listOf("Aura"), devices.map { it.name })
        assertTrue(devices.single().isActive)
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun emptyDeviceListIsSuccess() = runTest {
        val env = TestEnv(server, this)
        signedIn(env)
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
        assertTrue(env.api.devices().getOrThrow().isEmpty())
    }

    @Test
    fun transferSendsDeviceIdAndMapsErrors() = runTest {
        val env = TestEnv(server, this)
        signedIn(env)
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(env.api.transferPlayback("desktop-id").isSuccess)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/v1/me/player", req.path)
        assertEquals("""{"device_ids":["desktop-id"],"play":true}""", req.body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(env.api.transferPlayback("x").exceptionOrNull() is SpotifyApiException.Forbidden)
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(env.api.transferPlayback("x").exceptionOrNull() is SpotifyApiException.NotFound)
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(500, (env.api.transferPlayback("x").exceptionOrNull() as SpotifyApiException.Http).code)
    }

    @Test
    fun unauthorizedTriggersOneRefreshAndRetry() = runTest {
        val env = TestEnv(server, this)
        signedIn(env)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(tokenJson("access-2", refresh = null)))
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
        assertTrue(env.api.devices().isSuccess)
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
        server.takeRequest() // token refresh
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun persistent401FailsUnauthorizedWithoutLooping() = runTest {
        val env = TestEnv(server, this)
        signedIn(env)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(tokenJson("access-2")))
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(env.api.devices().exceptionOrNull() is SpotifyApiException.Unauthorized)
        assertEquals(4, server.requestCount) // sign-in exchange + 401 + refresh + 401
    }

    @Test
    fun notSignedInFailsWithoutNetwork() = runTest {
        val env = TestEnv(server, this)
        assertTrue(env.api.devices().exceptionOrNull() is SpotifyApiException.NotSignedIn)
        assertEquals(0, server.requestCount)
    }
}
