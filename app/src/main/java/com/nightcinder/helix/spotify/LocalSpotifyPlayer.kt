package com.nightcinder.helix.spotify

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalPlaybackSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/** Local Aura playback state read from the `com.spotify.music` media session; independent of OAuth. */
sealed interface LocalPlayerState {
    /** Notification Listener access not granted: MediaSessionManager cannot list other apps' sessions. */
    data object NeedsNotificationAccess : LocalPlayerState

    /** Access granted but no Spotify session (app missing when [installed] is false, else not running). */
    data class LocalPlayerUnavailable(val installed: Boolean) : LocalPlayerState

    data class Active(val snapshot: LocalPlaybackSnapshot) : LocalPlayerState
}

interface LocalSpotifySession {
    fun snapshot(): LocalPlaybackSnapshot
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
}

/** Seam over `MediaSessionManager` so the player is unit-testable with doubles. */
interface LocalSessionSource {
    fun hasNotificationAccess(): Boolean
    fun isSpotifyInstalled(): Boolean
    fun spotifySession(): LocalSpotifySession?

    /** Registers a change callback; returns an unregister function. */
    fun addChangeListener(onChange: () -> Unit): () -> Unit
}

class LocalSpotifyPlayer(private val source: LocalSessionSource) {
    private val _state = MutableStateFlow(compute())
    val state: StateFlow<LocalPlayerState> = _state.asStateFlow()
    private var unregister: (() -> Unit)? = null

    /** Re-reads the session (also called by the UI's progress tick). */
    fun refresh() {
        _state.value = compute()
    }

    /** Starts reacting to session changes while a panel is visible. */
    fun start() {
        if (unregister == null && source.hasNotificationAccess()) {
            unregister = runCatching { source.addChangeListener(::refresh) }.getOrNull()
        }
        refresh()
    }

    fun stop() {
        unregister?.invoke()
        unregister = null
    }

    fun togglePlayPause() = withSession { s -> if (s.snapshot().isPlaying) s.pause() else s.play() }
    fun next() = withSession { it.next() }
    fun previous() = withSession { it.previous() }
    fun seekTo(positionMs: Long) = withSession { it.seekTo(positionMs.coerceAtLeast(0L)) }

    private fun withSession(block: (LocalSpotifySession) -> Unit) {
        runCatching { source.spotifySession()?.let(block) }
        refresh()
    }

    private fun compute(): LocalPlayerState {
        if (!source.hasNotificationAccess()) return LocalPlayerState.NeedsNotificationAccess
        val session = runCatching { source.spotifySession() }.getOrElse {
            return LocalPlayerState.NeedsNotificationAccess // SecurityException: access revoked mid-flight
        } ?: return LocalPlayerState.LocalPlayerUnavailable(installed = source.isSpotifyInstalled())
        return LocalPlayerState.Active(session.snapshot())
    }
}
