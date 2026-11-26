package com.nightcinder.helix.media

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/** Playback lifecycle: Idle -> Loading -> Playing <-> Paused -> Ended, or Error from any state. */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data object Ended : PlaybackState
    data class Error(val message: String) : PlaybackState
}

data class TrackOption(val id: String, val label: String, val selected: Boolean)

data class PlayerTracks(
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = false,
)

/** Seam between the view model and Media3 so state transitions are testable with a double. */
interface MediaPlayerController {
    val state: StateFlow<PlaybackState>
    val tracks: StateFlow<PlayerTracks>

    fun prepare(stream: ResolvedStream, startPositionMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()

    /** Binds video output. Safe to call before [prepare]; replaces any previously attached surface. */
    fun attachSurface(surface: Surface)

    /** Unbinds video output and pauses so no frames are pushed into a dead surface. */
    fun detachSurface()

    fun selectAudio(id: String)

    /** Null turns subtitles off. */
    fun selectSubtitle(id: String?)

    fun positionMs(): Long
    fun durationMs(): Long
    fun release()
}
