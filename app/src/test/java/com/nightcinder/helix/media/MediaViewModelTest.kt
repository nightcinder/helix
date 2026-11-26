package com.nightcinder.helix.media

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nightcinder.helix.auth.InMemoryCryptoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var routes: RoutingDispatcher
    private val store = InMemoryCryptoStore()
    private lateinit var player: FakePlayer
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        routes = RoutingDispatcher()
        server = MockWebServer().apply { dispatcher = routes; start() }
        player = FakePlayer()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun newVm(): MediaViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = MediaViewModel(
                JellyfinClient(OkHttpClient(), store, defaultBaseUrl = server.base()),
                PlexClient(OkHttpClient(), store, plexTvBase = server.base()),
                player,
                signInPollMs = 10,
                signInTimeoutMs = 2_000,
                progressIntervalMs = 25,
            ) as T
        }
        return ViewModelProvider(viewModelStore, factory)[MediaViewModel::class.java]
    }

    private suspend fun MediaViewModel.await(what: String, predicate: (MediaUiState) -> Boolean): MediaUiState =
        try {
            withTimeout(5_000) { uiState.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state=${uiState.value}")
        }

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) kotlinx.coroutines.delay(10) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; player calls=${player.calls}")
        }
    }

    private fun signInJellyfin() {
        runBlocking {
            store.putString(JellyfinClient.KEY_TOKEN, "t")
            store.putString(JellyfinClient.KEY_USER_ID, "u-1")
        }
        routes.json("/Users/u-1/Items/Resume", """{"Items":[{"Id":"m1","Name":"Movie","Type":"Movie","RunTimeTicks":72000000000,"UserData":{"PlaybackPositionTicks":6000000000}}]}""")
        routes.json("/Shows/NextUp", """{"Items":[]}""")
        routes.json(
            "/Items/m1/PlaybackInfo",
            """{"PlaySessionId":"ps","MediaSources":[{"Id":"src","SupportsDirectPlay":true,"MediaStreams":[]}]}""",
        )
        routes.json("/Sessions/Playing", "", 204)
    }

    @Test
    fun init_loadsContinueWatchingWhenSignedIn() = runBlocking {
        signInJellyfin()
        val vm = newVm()
        val state = vm.await("library") { it.continueWatching.isNotEmpty() && !it.isLoading }
        assertTrue(state.jellyfinSignedIn)
        assertFalse(state.plexSignedIn)
        assertEquals("m1", state.continueWatching.single().id)
    }

    @Test
    fun playback_idleLoadingPlayingPausedEnded_withResumeAndReports() = runBlocking {
        signInJellyfin()
        val vm = newVm()
        val item = vm.await("library") { it.continueWatching.isNotEmpty() && !it.isLoading }.continueWatching.single()
        assertEquals(PlaybackState.Idle, vm.uiState.value.playback)

        vm.play(item)
        vm.await("Loading") { it.playback == PlaybackState.Loading && it.nowPlaying == item }
        until("prepare") { player.prepared != null }
        assertEquals(600_000L, player.startPosition)

        player.stateFlow.value = PlaybackState.Playing
        vm.await("Playing") { it.playback == PlaybackState.Playing }
        vm.togglePlayPause()
        vm.await("Paused") { it.playback == PlaybackState.Paused }
        assertTrue(player.calls.contains("pause"))
        vm.togglePlayPause()
        vm.await("Playing again") { it.playback == PlaybackState.Playing }

        // Periodic progress is reported while playing.
        withTimeout(5_000) { while (routes.requestsTo("/Sessions/Playing/Progress").isEmpty()) kotlinx.coroutines.delay(10) }

        player.stateFlow.value = PlaybackState.Ended
        vm.await("Ended") { it.playback == PlaybackState.Ended }
        withTimeout(5_000) { while (routes.requestsTo("/Sessions/Playing/Stopped").isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(1, routes.requestsTo("/Sessions/Playing/Stopped").size)
        assertEquals(1, routes.requestsTo("/Sessions/Playing").count { it.path == "/Sessions/Playing" })
    }

    @Test
    fun playback_errorWhenResolveFails() = runBlocking {
        signInJellyfin()
        routes.json("/Items/m1/PlaybackInfo", """{"MediaSources":[{"Id":"s","SupportsDirectPlay":false}]}""")
        val vm = newVm()
        val item = vm.await("library") { it.continueWatching.isNotEmpty() && !it.isLoading }.continueWatching.single()
        vm.play(item)
        val state = vm.await("Error") { it.playback is PlaybackState.Error }
        assertTrue((state.playback as PlaybackState.Error).message.contains("Direct Play"))
        assertFalse(player.calls.contains("prepare"))
    }

    @Test
    fun playback_unauthorizedDuringResolve_signsOutAndSurfacesError() = runBlocking {
        signInJellyfin()
        routes.json("/Items/m1/PlaybackInfo", "", 401)
        val vm = newVm()
        val item = vm.await("library") { it.continueWatching.isNotEmpty() && !it.isLoading }.continueWatching.single()
        vm.play(item)
        vm.await("signed out") { !it.jellyfinSignedIn && it.playback is PlaybackState.Error && !it.isLoading }
        assertTrue((vm.uiState.value.playback as PlaybackState.Error).message.contains("session expired"))
    }

    @Test
    fun surfaceDestroyed_detachesAndPauses_thenRecreatedReattaches() = runBlocking {
        signInJellyfin()
        val vm = newVm()
        val item = vm.await("library") { it.continueWatching.isNotEmpty() && !it.isLoading }.continueWatching.single()
        vm.play(item)
        until("prepare") { player.prepared != null }
        player.stateFlow.value = PlaybackState.Playing
        vm.await("Playing") { it.playback == PlaybackState.Playing }

        vm.onSurfaceDestroyed() // panel hidden or Home<->Full Space switch
        vm.await("Paused") { it.playback == PlaybackState.Paused }
        assertTrue(player.calls.contains("detach"))

        vm.onSurfaceCreated(dummySurface())
        assertEquals("attach", player.calls.last())
        assertEquals(PlaybackState.Paused, vm.uiState.value.playback) // stays paused until the user resumes
    }

    @Test
    fun stopPlayback_reportsStopAndReturnsToIdle() = runBlocking {
        signInJellyfin()
        val vm = newVm()
        val item = vm.await("library") { it.continueWatching.isNotEmpty() && !it.isLoading }.continueWatching.single()
        vm.play(item)
        until("prepare") { player.prepared != null }
        vm.stopPlayback()
        val state = vm.await("idle") { it.nowPlaying == null }
        assertEquals(PlaybackState.Idle, state.playback)
        assertTrue(player.calls.contains("stop"))
        withTimeout(5_000) { while (routes.requestsTo("/Sessions/Playing/Stopped").isEmpty()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun trackSelection_delegatesToPlayerAndMirrorsTracks() = runBlocking {
        val vm = newVm()
        player.tracksFlow.value = PlayerTracks(
            audio = listOf(TrackOption("0:0", "English", true), TrackOption("0:1", "Japanese", false)),
            subtitles = listOf(TrackOption("0:0", "English", false)),
        )
        vm.await("tracks") { it.tracks.audio.size == 2 }
        vm.selectAudio("0:1")
        vm.selectSubtitle("0:0")
        vm.selectSubtitle(null)
        assertEquals(listOf("audio:0:1", "sub:0:0", "sub:null"), player.calls)
    }

    @Test
    fun onCleared_releasesThePlayer() {
        newVm()
        assertFalse(player.calls.contains("release"))
        viewModelStore.clear()
        assertEquals(1, player.calls.count { it == "release" })
    }

    @Test
    fun jellyfinQuickConnect_pendingThenSignedIn() = runBlocking {
        routes.json("/QuickConnect/Initiate", """{"Secret":"s","Code":"654321"}""")
        var polls = 0
        routes.on("/QuickConnect/Connect") { MockResponse().setBody("""{"Authenticated":${++polls >= 2}}""") }
        routes.json("/Users/AuthenticateWithQuickConnect", """{"AccessToken":"qc","User":{"Id":"u-9"}}""")
        routes.json("/Users/u-9/Items/Resume", """{"Items":[]}""")
        routes.json("/Shows/NextUp", """{"Items":[]}""")
        val vm = newVm()
        vm.startJellyfinQuickConnect()
        assertEquals("654321", vm.await("pending") { it.pending != null }.pending!!.code)
        val done = vm.await("signed in") { it.jellyfinSignedIn && it.pending == null && !it.isLoading }
        assertTrue(done.jellyfinSignedIn)
    }

    @Test
    fun jellyfinPassword401_showsWrongCredentials() = runBlocking {
        routes.json("/Users/AuthenticateByName", "", 401)
        val vm = newVm()
        vm.signInJellyfinPassword("a", "b")
        assertTrue(vm.await("message") { it.message != null }.message!!.contains("wrong username or password"))
        assertFalse(vm.uiState.value.jellyfinSignedIn)
    }

    @Test
    fun plexPin_pendingThenSignedIn() = runBlocking {
        routes.json("/api/v2/pins?strong=true", """{"id":7,"code":"pincode"}""")
        var polls = 0
        routes.on("/api/v2/pins/7") { MockResponse().setBody(if (++polls < 2) """{"authToken":null}""" else """{"authToken":"plex-t"}""") }
        store.putString(PlexClient.KEY_OVERRIDE, server.base())
        routes.json("/identity", "{}")
        routes.json("/library/onDeck", """{"MediaContainer":{"Metadata":[{"ratingKey":"5","title":"Film","type":"movie"}]}}""")
        val vm = newVm()
        vm.startPlexPin()
        val pending = vm.await("pending") { it.pending != null }.pending!!
        assertEquals(MediaProvider.PLEX, pending.provider)
        assertTrue(pending.url!!.contains("code=pincode"))
        val done = vm.await("plex signed in") { it.plexSignedIn && it.continueWatching.isNotEmpty() && !it.isLoading }
        assertEquals("5", done.continueWatching.single().id)
    }

    @Test
    fun signOut_wipesProviderAndClearsLists() = runBlocking {
        signInJellyfin()
        val vm = newVm()
        vm.await("library") { it.continueWatching.isNotEmpty() && !it.isLoading }
        vm.signOut(MediaProvider.JELLYFIN)
        val s = vm.await("signed out") { !it.jellyfinSignedIn && it.continueWatching.isEmpty() && !it.isLoading }
        assertTrue(s.continueWatching.isEmpty())
    }
}
