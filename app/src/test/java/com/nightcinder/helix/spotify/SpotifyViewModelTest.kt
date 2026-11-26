package com.nightcinder.helix.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyViewModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    /** The Web API runs on real IO threads, so wait on real time rather than the test scheduler. */
    private suspend fun await(vm: SpotifyViewModel, predicate: (SpotifyUiState) -> Boolean) {
        withContext(Dispatchers.Default) { withTimeout(5_000) { vm.uiState.first(predicate) } }
    }

    @Test
    fun unconfiguredShowsNotConfiguredWhileLocalControlsStillWork() = runTest {
        val env = TestEnv(server, this, clientId = "")
        val session = LocalSpotifyPlayerTest.FakeSession()
        val local = LocalSpotifyPlayer(LocalSpotifyPlayerTest.FakeSource(session = session))
        val vm = SpotifyViewModel(env.auth, env.api, local, env.config)

        assertEquals(SpotifyAuthState.NotConfigured, vm.uiState.value.auth)
        assertTrue(!vm.uiState.value.isConfigured)
        vm.togglePlayPause()
        assertEquals(listOf("play"), session.calls)
        assertTrue(vm.uiState.value.local is LocalPlayerState.Active)
        var opened = false
        vm.signIn { opened = true }
        assertTrue(!opened)
        assertEquals(SpotifyAuthService.NOT_CONFIGURED, vm.uiState.value.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missingNotificationAccessDoesNotBlockWebApiSignedInState() = runTest {
        val env = TestEnv(server, this)
        server.enqueue(MockResponse().setBody(tokenJson("a")))
        env.signInVia()
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"id":"d1","name":"desktop","type":"Computer","is_active":false}]}"""))
        // Signed-in state triggers the initial browse load on construction.
        val vm = SpotifyViewModel(
            env.auth, env.api,
            LocalSpotifyPlayer(LocalSpotifyPlayerTest.FakeSource(access = false)), env.config,
        )
        await(vm) { it.devices.isNotEmpty() }
        assertEquals(LocalPlayerState.NeedsNotificationAccess, vm.uiState.value.local)
        assertTrue(vm.uiState.value.isSignedIn)
        assertEquals(listOf("desktop"), vm.uiState.value.devices.map { it.name })
    }

    @Test
    fun transferErrorsSurfaceReadableMessages() = runTest {
        val env = TestEnv(server, this)
        server.enqueue(MockResponse().setBody(tokenJson("a")))
        env.signInVia()
        repeat(2) { server.enqueue(MockResponse().setBody("""{"items":[]}""")) }
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
        val vm = SpotifyViewModel(env.auth, env.api, LocalSpotifyPlayer(LocalSpotifyPlayerTest.FakeSource()), env.config)
        await(vm) { !it.isLoading && server.requestCount == 4 }
        val device = SpotifyDevice("d1", "desktop", "Computer", isActive = false, isRestricted = false)

        server.enqueue(MockResponse().setResponseCode(403))
        vm.transferTo(device)
        await(vm) { it.message?.contains("Premium") == true }

        server.enqueue(MockResponse().setResponseCode(404))
        vm.transferTo(device)
        await(vm) { it.message?.contains("No active Spotify device") == true }

        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
        vm.transferTo(device)
        await(vm) { it.message == SpotifyViewModel.NO_DEVICES }
        assertTrue(vm.uiState.value.devices.isEmpty())
    }
}
