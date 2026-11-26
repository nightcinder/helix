package com.nightcinder.helix.media

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingSignIn(val provider: MediaProvider, val code: String, val url: String?)

data class MediaUiState(
    val jellyfinSignedIn: Boolean = false,
    val plexSignedIn: Boolean = false,
    val pending: PendingSignIn? = null,
    val continueWatching: List<MediaItem> = emptyList(),
    val upNext: List<MediaItem> = emptyList(),
    val nowPlaying: MediaItem? = null,
    val playback: PlaybackState = PlaybackState.Idle,
    val tracks: PlayerTracks = PlayerTracks(),
    val isLoading: Boolean = false,
    val message: String? = null,
)

class MediaViewModel(
    private val jellyfin: JellyfinClient,
    private val plex: PlexClient,
    private val player: MediaPlayerController,
    private val signInPollMs: Long = 2_000L,
    private val signInTimeoutMs: Long = 300_000L,
    private val progressIntervalMs: Long = 10_000L,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaUiState())
    val uiState: StateFlow<MediaUiState> = _uiState.asStateFlow()

    private var signInJob: Job? = null
    private var progressJob: Job? = null
    private var playJob: Job? = null
    private var stream: ResolvedStream? = null
    private var lastReportedEnded = false

    init {
        viewModelScope.launch { player.state.collect(::onPlayerState) }
        viewModelScope.launch { player.tracks.collect { t -> _uiState.update { it.copy(tracks = t) } } }
        viewModelScope.launch { refresh() }
    }

    private fun client(provider: MediaProvider): MediaProviderClient =
        if (provider == MediaProvider.JELLYFIN) jellyfin else plex

    // --- Library ---

    suspend fun refresh() {
        _uiState.update { it.copy(isLoading = true, message = null) }
        val watching = ArrayList<MediaItem>()
        val next = ArrayList<MediaItem>()
        var error: String? = null
        for (c in listOf<MediaProviderClient>(jellyfin, plex)) {
            if (!c.isSignedIn()) continue
            c.continueWatching().fold({ watching += it }, { error = describe(c.provider, it) })
            c.upNext().fold({ next += it }, { if (error == null) error = describe(c.provider, it) })
        }
        _uiState.update {
            it.copy(
                jellyfinSignedIn = jellyfin.isSignedIn(),
                plexSignedIn = plex.isSignedIn(),
                continueWatching = watching,
                upNext = next,
                isLoading = false,
                message = error,
            )
        }
    }

    fun reload() {
        viewModelScope.launch { refresh() }
    }

    private fun describe(provider: MediaProvider, t: Throwable): String {
        val name = if (provider == MediaProvider.JELLYFIN) "Jellyfin" else "Plex"
        return if (t is MediaApiException.Unauthorized) "$name session expired — sign in again" else "$name: ${t.message}"
    }

    // --- Sign-in flows ---

    fun startJellyfinQuickConnect() {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            val ticket = jellyfin.quickConnectInitiate().getOrElse { return@launch fail("Jellyfin: ${it.message}") }
            _uiState.update { it.copy(pending = PendingSignIn(MediaProvider.JELLYFIN, ticket.code, null), message = null) }
            var waited = 0L
            while (waited < signInTimeoutMs) {
                delay(signInPollMs)
                waited += signInPollMs
                val approved = jellyfin.quickConnectApproved(ticket.secret).getOrElse { return@launch fail("Jellyfin: ${it.message}") }
                if (approved) {
                    jellyfin.signInWithQuickConnect(ticket.secret).onFailure { return@launch fail("Jellyfin: ${it.message}") }
                    _uiState.update { it.copy(pending = null) }
                    refresh()
                    return@launch
                }
            }
            fail("Jellyfin Quick Connect timed out")
        }
    }

    fun signInJellyfinPassword(username: String, password: String) {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            jellyfin.signInWithPassword(username, password)
                .onFailure { return@launch fail(if (it is MediaApiException.Unauthorized) "Jellyfin: wrong username or password" else "Jellyfin: ${it.message}") }
            refresh()
        }
    }

    fun startPlexPin() {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            val pin = plex.createPin().getOrElse { return@launch fail("Plex: ${it.message}") }
            _uiState.update { it.copy(pending = PendingSignIn(MediaProvider.PLEX, pin.code, pin.authUrl), message = null) }
            var waited = 0L
            while (waited < signInTimeoutMs) {
                delay(signInPollMs)
                waited += signInPollMs
                val done = plex.pollPin(pin).getOrElse { return@launch fail("Plex: ${it.message}") }
                if (done) {
                    _uiState.update { it.copy(pending = null) }
                    refresh()
                    return@launch
                }
            }
            fail("Plex sign-in timed out")
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _uiState.update { it.copy(pending = null) }
    }

    fun signOut(provider: MediaProvider) {
        viewModelScope.launch {
            if (_uiState.value.nowPlaying?.provider == provider) stopPlayback()
            client(provider).signOut()
            refresh()
        }
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(pending = null, message = message) }
    }

    // --- Playback ---

    fun play(item: MediaItem) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            finishCurrent()
            lastReportedEnded = false
            _uiState.update { it.copy(nowPlaying = item, playback = PlaybackState.Loading, message = null) }
            val resolved = client(item.provider).resolve(item).getOrElse { e ->
                _uiState.update { it.copy(playback = PlaybackState.Error(describe(item.provider, e))) }
                if (e is MediaApiException.Unauthorized) refresh()
                return@launch
            }
            stream = resolved
            player.prepare(resolved, item.resumePositionMs)
            client(item.provider).reportStart(item, resolved, item.resumePositionMs)
            startProgress(item, resolved)
        }
    }

    private fun startProgress(item: MediaItem, resolved: ResolvedStream) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                delay(progressIntervalMs)
                client(item.provider).reportProgress(item, resolved, player.positionMs(), _uiState.value.playback != PlaybackState.Playing)
            }
        }
    }

    private suspend fun finishCurrent() {
        progressJob?.cancel()
        val item = _uiState.value.nowPlaying
        val s = stream
        if (item != null && s != null) client(item.provider).reportStop(item, s, player.positionMs())
        stream = null
    }

    fun stopPlayback() {
        playJob?.cancel()
        viewModelScope.launch {
            finishCurrent()
            player.stop()
            _uiState.update { it.copy(nowPlaying = null, playback = PlaybackState.Idle) }
        }
    }

    fun togglePlayPause() {
        when (_uiState.value.playback) {
            PlaybackState.Playing -> player.pause()
            PlaybackState.Paused -> player.play()
            PlaybackState.Ended -> player.seekTo(0).also { player.play() }
            else -> Unit
        }
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun selectAudio(id: String) = player.selectAudio(id)
    fun selectSubtitle(id: String?) = player.selectSubtitle(id)
    fun positionMs(): Long = player.positionMs()
    fun durationMs(): Long = player.durationMs()

    private fun onPlayerState(state: PlaybackState) {
        _uiState.update { it.copy(playback = if (it.nowPlaying == null && state !is PlaybackState.Error) PlaybackState.Idle else state) }
        if (state is PlaybackState.Ended && !lastReportedEnded) {
            lastReportedEnded = true
            viewModelScope.launch { finishCurrent() }
        }
    }

    // --- Surface lifecycle (SpatialExternalSurface / Home<->Full Space) ---

    fun onSurfaceCreated(surface: Surface) = player.attachSurface(surface)

    /** Panel hidden, surface destroyed or space switched: unbind video output and pause. */
    fun onSurfaceDestroyed() {
        player.detachSurface()
        val item = _uiState.value.nowPlaying
        val s = stream
        if (item != null && s != null) {
            viewModelScope.launch { client(item.provider).reportProgress(item, s, player.positionMs(), paused = true) }
        }
    }

    override fun onCleared() {
        progressJob?.cancel()
        player.release()
    }

    companion object {
        fun provideFactory(
            jellyfin: JellyfinClient,
            plex: PlexClient,
            player: MediaPlayerController,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MediaViewModel(jellyfin, plex, player) as T
        }
    }
}
