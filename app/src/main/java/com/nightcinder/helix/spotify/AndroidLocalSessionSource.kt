package com.nightcinder.helix.spotify

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/** Required so [MediaSessionManager.getActiveSessions] may list other apps' sessions once the user grants access. */
class SpotifyNotificationListener : NotificationListenerService()

class AndroidLocalSessionSource(context: Context) : LocalSessionSource {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(appContext, SpotifyNotificationListener::class.java)

    override fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)

    override fun isSpotifyInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SPOTIFY_PACKAGE, 0)
    }.isSuccess

    override fun spotifySession(): LocalSpotifySession? =
        manager?.getActiveSessions(listener)?.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
            ?.let(::ControllerSession)

    override fun addChangeListener(onChange: () -> Unit): () -> Unit {
        val l = MediaSessionManager.OnActiveSessionsChangedListener { onChange() }
        manager?.addOnActiveSessionsChangedListener(l, listener)
        return { manager?.removeOnActiveSessionsChangedListener(l) }
    }

    private class ControllerSession(private val c: MediaController) : LocalSpotifySession {
        override fun snapshot(): LocalPlaybackSnapshot {
            val md = c.metadata
            val ps = c.playbackState
            val playing = ps?.state == PlaybackState.STATE_PLAYING
            val position = ps?.let {
                val elapsed = if (playing) {
                    ((SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
                } else {
                    0L
                }
                (it.position + elapsed).coerceAtLeast(0L)
            } ?: 0L
            return LocalPlaybackSnapshot(
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                artwork = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                isPlaying = playing,
                positionMs = position,
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            )
        }

        override fun play() = c.transportControls.play()
        override fun pause() = c.transportControls.pause()
        override fun next() = c.transportControls.skipToNext()
        override fun previous() = c.transportControls.skipToPrevious()
        override fun seekTo(positionMs: Long) = c.transportControls.seekTo(positionMs)
    }

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
