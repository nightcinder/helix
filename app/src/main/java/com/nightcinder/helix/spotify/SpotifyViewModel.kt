package com.nightcinder.helix.spotify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SpotifyUiState(
    val auth: SpotifyAuthState = SpotifyAuthState.SignedOut,
    val local: LocalPlayerState = LocalPlayerState.NeedsNotificationAccess,
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val likedSongs: List<SpotifyTrack> = emptyList(),
    val devices: List<SpotifyDevice> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val clientIdOverride: String? = null,
) {
    val isConfigured: Boolean get() = auth != SpotifyAuthState.NotConfigured
    val isSignedIn: Boolean get() = auth == SpotifyAuthState.SignedIn
}

class SpotifyViewModel(
    private val auth: SpotifyAuthService,
    private val api: SpotifyWebApi,
    private val localPlayer: LocalSpotifyPlayer,
    private val config: SpotifyClientConfig,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SpotifyUiState(auth = auth.state.value, local = localPlayer.state.value),
    )
    val uiState: StateFlow<SpotifyUiState> = _uiState.asStateFlow()
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            auth.state.collect { s ->
                _uiState.update { it.copy(auth = s) }
                if (s == SpotifyAuthState.SignedIn) loadBrowse()
            }
        }
        viewModelScope.launch { localPlayer.state.collect { s -> _uiState.update { it.copy(local = s) } } }
        viewModelScope.launch {
            auth.refreshState()
            _uiState.update { it.copy(clientIdOverride = config.overrideClientId()) }
        }
    }

    /** Called while a Spotify panel is visible: starts the local session listener + progress tick. */
    fun start() {
        localPlayer.start()
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                localPlayer.refresh()
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        localPlayer.stop()
    }

    // --- Local Aura playback (no OAuth required) ---
    fun togglePlayPause() = localPlayer.togglePlayPause()
    fun skipNext() = localPlayer.next()
    fun skipPrevious() = localPlayer.previous()
    fun seekTo(positionMs: Long) = localPlayer.seekTo(positionMs)
    fun refreshLocal() = localPlayer.refresh()

    // --- Web API / OAuth ---
    fun signIn(openUrl: (String) -> Unit) {
        viewModelScope.launch {
            auth.beginAuthorization()
                .onSuccess(openUrl)
                .onFailure { e -> _uiState.update { it.copy(message = e.message) } }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            _uiState.update { it.copy(playlists = emptyList(), likedSongs = emptyList(), devices = emptyList(), message = null) }
        }
    }

    fun loadBrowse() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val playlists = api.playlists()
            val liked = api.likedSongs()
            val devices = api.devices()
            _uiState.update { s ->
                s.copy(
                    isLoading = false,
                    playlists = playlists.getOrDefault(s.playlists),
                    likedSongs = liked.getOrDefault(s.likedSongs),
                    devices = devices.getOrDefault(s.devices),
                    message = listOf(playlists, liked, devices).firstNotNullOfOrNull { it.exceptionOrNull() }
                        ?.let(::describe) ?: s.message,
                )
            }
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            api.devices()
                .onSuccess { list ->
                    _uiState.update { it.copy(devices = list, message = if (list.isEmpty()) NO_DEVICES else null) }
                }
                .onFailure { e -> _uiState.update { it.copy(message = describe(e)) } }
        }
    }

    fun transferTo(device: SpotifyDevice) {
        viewModelScope.launch {
            api.transferPlayback(device.id)
                .onSuccess {
                    _uiState.update { it.copy(message = null) }
                    refreshDevices()
                }
                .onFailure { e -> _uiState.update { it.copy(message = describe(e)) } }
        }
    }

    fun playPlaylist(playlist: SpotifyPlaylist) = launchPlayback { api.startContext(playlist.uri) }

    fun playTrack(track: SpotifyTrack) = launchPlayback { api.startTrack(track.uri) }

    fun setClientIdOverride(raw: String) {
        viewModelScope.launch {
            if (config.setOverride(raw)) {
                auth.refreshState()
                _uiState.update { it.copy(clientIdOverride = raw.trim().ifEmpty { null }, message = null) }
            } else {
                _uiState.update { it.copy(message = "Invalid Spotify client id") }
            }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    private fun launchPlayback(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            block().onFailure { e -> _uiState.update { it.copy(message = describe(e)) } }
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is SpotifyApiException.NotFound -> "No active Spotify device found. Open Spotify on the headset or your PC and retry."
        is SpotifyApiException.Forbidden -> "Spotify refused: Premium is required or the device is restricted."
        is SpotifyApiException.Unauthorized, is SpotifyApiException.NotSignedIn -> "Spotify session expired. Sign in again."
        else -> e.message ?: "Spotify request failed"
    }

    override fun onCleared() {
        localPlayer.stop()
        super.onCleared()
    }

    companion object {
        const val NO_DEVICES = "No Spotify Connect devices available. Open Spotify on the headset or your PC."
        private const val PROGRESS_TICK_MS = 1_000L

        fun provideFactory(
            auth: SpotifyAuthService,
            api: SpotifyWebApi,
            localPlayer: LocalSpotifyPlayer,
            config: SpotifyClientConfig,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SpotifyViewModel(auth, api, localPlayer, config) as T
        }
    }
}
