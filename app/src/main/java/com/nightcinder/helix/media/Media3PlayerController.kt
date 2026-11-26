package com.nightcinder.helix.media

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Maps Media3 player state to [PlaybackState]; null means "keep the current state" (player idle). */
internal fun mapPlayerState(playbackState: Int, playWhenReady: Boolean): PlaybackState? = when (playbackState) {
    Player.STATE_BUFFERING -> PlaybackState.Loading
    Player.STATE_READY -> if (playWhenReady) PlaybackState.Playing else PlaybackState.Paused
    Player.STATE_ENDED -> PlaybackState.Ended
    else -> null
}

/**
 * Hardware-decoded Direct Play via Media3 ExoPlayer (HEVC / AV1 whatever the device decoder supports; decoder
 * fallback enabled). The player is created lazily and fully released by [release].
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Media3PlayerController(private val context: Context) : MediaPlayerController {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _tracks = MutableStateFlow(PlayerTracks())
    override val tracks: StateFlow<PlayerTracks> = _tracks.asStateFlow()

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    @Volatile private var headers: Map<String, String> = emptyMap()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = publishState()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publishState()
        override fun onTracksChanged(tracks: Tracks) = publishTracks(tracks)
        override fun onPlayerError(error: PlaybackException) {
            _state.value = PlaybackState.Error(error.message ?: error.errorCodeName)
        }
    }

    private fun publishState() {
        val p = player ?: return
        mapPlayerState(p.playbackState, p.playWhenReady)?.let { next ->
            if (_state.value !is PlaybackState.Error || next is PlaybackState.Loading) _state.value = next
        }
    }

    private fun ensurePlayer(): ExoPlayer = player ?: run {
        val http = DefaultHttpDataSource.Factory()
        val dataSources = DefaultDataSource.Factory(context) {
            http.setDefaultRequestProperties(headers).createDataSource()
        }
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .build()
            .also {
                it.addListener(listener)
                surface?.let { s -> it.setVideoSurface(s) }
                player = it
            }
    }

    override fun prepare(stream: ResolvedStream, startPositionMs: Long) {
        headers = stream.headers
        val p = ensurePlayer()
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setSubtitleConfigurations(
                stream.externalSubtitles.map {
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url))
                        .setMimeType(it.mimeType.takeIf { m -> m.isNotEmpty() } ?: MimeTypes.TEXT_VTT)
                        .setLabel(it.label)
                        .setLanguage(it.language)
                        .build()
                },
            )
            .build()
        _state.value = PlaybackState.Loading
        p.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
        p.playWhenReady = true
        p.prepare()
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun stop() {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        _tracks.value = PlayerTracks()
        _state.value = PlaybackState.Idle
    }

    override fun attachSurface(surface: Surface) {
        this.surface = surface
        player?.setVideoSurface(surface)
    }

    override fun detachSurface() {
        player?.let {
            it.pause()
            it.clearVideoSurface()
        }
        surface = null
        if (_state.value is PlaybackState.Playing) _state.value = PlaybackState.Paused
    }

    override fun selectAudio(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectSubtitle(id: String?) {
        val p = player ?: return
        if (id == null) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else {
            select(C.TRACK_TYPE_TEXT, id)
        }
    }

    private fun select(type: Int, id: String) {
        val p = player ?: return
        val (groupIdx, trackIdx) = id.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return
        val group = p.currentTracks.groups.filter { it.type == type }.getOrNull(groupIdx) ?: return
        if (trackIdx !in 0 until group.length) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIdx))
            .build()
    }

    private fun publishTracks(tracks: Tracks) {
        fun options(type: Int): List<TrackOption> =
            tracks.groups.filter { it.type == type }.flatMapIndexed { gi, group ->
                (0 until group.length).filter { group.isTrackSupported(it) }.map { ti ->
                    val f = group.getTrackFormat(ti)
                    TrackOption(
                        id = "$gi:$ti",
                        label = f.label ?: f.language ?: "Track ${gi + 1}.${ti + 1}",
                        selected = group.isTrackSelected(ti),
                    )
                }
            }
        val text = options(C.TRACK_TYPE_TEXT)
        _tracks.value = PlayerTracks(options(C.TRACK_TYPE_AUDIO), text, text.any { it.selected })
    }

    override fun positionMs(): Long = player?.currentPosition ?: 0L
    override fun durationMs(): Long = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L

    override fun release() {
        player?.let {
            it.removeListener(listener)
            it.clearVideoSurface()
            it.release()
        }
        player = null
        surface = null
        _tracks.value = PlayerTracks()
        _state.value = PlaybackState.Idle
    }
}
