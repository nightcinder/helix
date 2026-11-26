package com.nightcinder.helix.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpotifyPlayerTest {
    class FakeSession(var playing: Boolean = false) : LocalSpotifySession {
        val calls = mutableListOf<String>()
        override fun snapshot() = LocalPlaybackSnapshot("Title", "Artist", "Album", null, playing, 1_000, 200_000)
        override fun play() { calls += "play"; playing = true }
        override fun pause() { calls += "pause"; playing = false }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    }

    class FakeSource(
        var access: Boolean = true,
        var installed: Boolean = true,
        var session: LocalSpotifySession? = null,
        var throwOnSession: Boolean = false,
    ) : LocalSessionSource {
        var listenerRegistered = false
        override fun hasNotificationAccess() = access
        override fun isSpotifyInstalled() = installed
        override fun spotifySession(): LocalSpotifySession? {
            if (throwOnSession) throw SecurityException("revoked")
            return session
        }
        override fun addChangeListener(onChange: () -> Unit): () -> Unit {
            listenerRegistered = true
            return { listenerRegistered = false }
        }
    }

    @Test
    fun withoutNotificationAccessNeedsAccess() {
        val p = LocalSpotifyPlayer(FakeSource(access = false))
        assertEquals(LocalPlayerState.NeedsNotificationAccess, p.state.value)
    }

    @Test
    fun revokedMidFlightDegradesInsteadOfCrashing() {
        val p = LocalSpotifyPlayer(FakeSource(throwOnSession = true))
        assertEquals(LocalPlayerState.NeedsNotificationAccess, p.state.value)
    }

    @Test
    fun noSessionIsUnavailableWithInstalledFlag() {
        val src = FakeSource(session = null, installed = false)
        val p = LocalSpotifyPlayer(src)
        assertEquals(LocalPlayerState.LocalPlayerUnavailable(installed = false), p.state.value)
        src.installed = true
        p.refresh()
        assertEquals(LocalPlayerState.LocalPlayerUnavailable(installed = true), p.state.value)
    }

    @Test
    fun controlsDriveSessionAndStateRefreshes() {
        val session = FakeSession(playing = false)
        val src = FakeSource(session = session)
        val p = LocalSpotifyPlayer(src)
        p.togglePlayPause()
        assertTrue((p.state.value as LocalPlayerState.Active).snapshot.isPlaying)
        p.togglePlayPause()
        p.next(); p.previous(); p.seekTo(-5)
        assertEquals(listOf("play", "pause", "next", "previous", "seek:0"), session.calls)
    }

    @Test
    fun listenerRegisteredOnlyBetweenStartAndStopWhenAccessGranted() {
        val src = FakeSource(session = FakeSession())
        val p = LocalSpotifyPlayer(src)
        p.start()
        assertTrue(src.listenerRegistered)
        p.stop()
        assertTrue(!src.listenerRegistered)
        val noAccess = FakeSource(access = false)
        LocalSpotifyPlayer(noAccess).start()
        assertTrue(!noAccess.listenerRegistered)
    }
}
