package com.nightcinder.helix.ui.state

import android.content.Context
import android.content.Intent
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nightcinder.helix.core.model.AppDiscovery
import com.nightcinder.helix.core.model.ClipboardSyncState
import com.nightcinder.helix.core.model.HostApp
import com.nightcinder.helix.core.model.HostAppCatalog
import com.nightcinder.helix.core.model.SiteConfig
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamConnectionState
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.core.model.StreamResolution
import com.nightcinder.helix.core.model.StreamTelemetry
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.WebSocketCapabilityStatus
import com.nightcinder.helix.core.stream.ClipboardBridge
import com.nightcinder.helix.core.stream.MoonlightStreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppListStatus { Idle, Loading, Loaded, Unpaired, Error }

/**
 * Pre-stream host app picker. [catalog] only ever holds what the host returned; on [AppListStatus.Error] it keeps
 * the last good answer (or null) and the stream stays launchable with the current [StreamConfig] selection.
 */
data class AppPickerState(
    val status: AppListStatus = AppListStatus.Idle,
    val catalog: HostAppCatalog? = null,
    val message: String? = null,
) {
    val apps: List<HostApp> get() = catalog?.apps.orEmpty()
}

data class StreamUiState(
    val streamState: StreamConnectionState = StreamConnectionState(),
    val telemetry: StreamTelemetry = StreamTelemetry(),
    val config: StreamConfig = StreamConfig(),
    val clipboardState: ClipboardSyncState = ClipboardSyncState(),
    val selectedHost: TermixHost? = null,
    val isSurfaceAttached: Boolean = false,
    val appPicker: AppPickerState = AppPickerState(),
    val wsCapabilityStatus: WebSocketCapabilityStatus = WebSocketCapabilityStatus.Unknown,
    val errorMessage: String? = null,
)

class StreamViewModel(
    private val streamClient: MoonlightStreamClient,
    private val clipboardBridge: ClipboardBridge,
    /** Current Moonlight base URL (sign-in screen setting); the WebSocket fallback URL is derived from it. */
    private val moonlightBaseUrlProvider: () -> String = { SiteConfig.MOONLIGHT_URL },
) : ViewModel() {

    private val _uiState = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    init {
        observeStreamState()
        observeTelemetry()
        observeClipboardState()
        observeCapabilityStatus()
    }

    private fun observeCapabilityStatus() {
        viewModelScope.launch {
            streamClient.capabilityStatus.collect { cap ->
                _uiState.update { it.copy(wsCapabilityStatus = cap) }
            }
        }
    }

    private fun observeStreamState() {
        viewModelScope.launch {
            streamClient.state.collect { state ->
                val becameConnected = state.status == StreamConnectionStatus.Connected &&
                    _uiState.value.streamState.status != StreamConnectionStatus.Connected
                _uiState.update { current ->
                    current.copy(
                        streamState = state,
                        errorMessage = state.errorMessage ?: current.errorMessage,
                    )
                }
                // First connect pairs an unpaired host and may change what is running: re-read /applist.
                if (becameConnected) refreshApps()
            }
        }
    }

    private fun observeTelemetry() {
        viewModelScope.launch {
            streamClient.telemetry.collect { telemetry ->
                _uiState.update { it.copy(telemetry = telemetry) }
            }
        }
    }

    private fun observeClipboardState() {
        viewModelScope.launch {
            clipboardBridge.state.collect { clipState ->
                _uiState.update { it.copy(clipboardState = clipState) }
            }
        }
    }

    fun selectHost(host: TermixHost) {
        val newConfig = _uiState.value.config.copy(
            hostIp = host.ip.ifBlank { StreamConfig.DEFAULT_HOST_IP }
        )
        _uiState.update { it.copy(selectedHost = host, config = newConfig, appPicker = AppPickerState()) }
        refreshApps()
    }

    private var appsJob: Job? = null

    /** Re-reads `/applist` and `/serverinfo` for the target host. Unpaired hosts are not queried. */
    fun refreshApps() {
        appsJob?.cancel()
        _uiState.update { it.copy(appPicker = it.appPicker.copy(status = AppListStatus.Loading, message = null)) }
        val config = _uiState.value.config
        appsJob = viewModelScope.launch {
            val picker = try {
                when (val result = streamClient.discoverApps(config)) {
                    is AppDiscovery.Loaded -> AppPickerState(AppListStatus.Loaded, result.catalog)
                    AppDiscovery.Unpaired -> AppPickerState(
                        AppListStatus.Unpaired,
                        message = "Not paired yet. Start Stream pairs, then the app list loads.",
                    )
                    is AppDiscovery.Unavailable -> failedPicker(result.reason)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                failedPicker(e.message ?: "Could not read the host's apps")
            }
            _uiState.update { it.copy(appPicker = picker, config = reconcileSelection(it.config, picker)) }
        }
    }

    private fun failedPicker(reason: String) =
        AppPickerState(AppListStatus.Error, _uiState.value.appPicker.catalog, reason)

    /** Keeps the chosen app id in step with the host's list; never invents an entry. */
    private fun reconcileSelection(config: StreamConfig, picker: AppPickerState): StreamConfig {
        val apps = picker.apps
        if (picker.status != AppListStatus.Loaded || apps.isEmpty()) return config
        val current = apps.firstOrNull { it.id == config.appId }
            ?: apps.firstOrNull { config.appId == null && it.title.equals(config.appTitle, ignoreCase = true) }
            ?: apps.firstOrNull { it.title.equals(StreamConfig.DEFAULT_APP_TITLE, ignoreCase = true) }
            ?: apps.first()
        return config.copy(appId = current.id, appTitle = current.title)
    }

    fun selectApp(app: HostApp) {
        _uiState.update {
            it.copy(config = it.config.copy(appId = app.id, appTitle = app.title, replaceRunningApp = false))
        }
    }

    fun setReplaceRunningApp(replace: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(replaceRunningApp = replace)) }
    }

    fun connect() {
        val picker = _uiState.value.appPicker
        if (picker.status == AppListStatus.Loaded && picker.apps.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Host has no apps to stream") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val config = _uiState.value.config
            val wsUrl = StreamConfig.wsUrlFor(moonlightBaseUrlProvider()).ifBlank { config.wsUrl }
            streamClient.connect(config.copy(wsUrl = wsUrl))
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            streamClient.disconnect()
        }
    }

    fun attachSurface(surface: Surface) {
        streamClient.attachSurface(surface)
        _uiState.update { it.copy(isSurfaceAttached = true) }
    }

    fun detachSurface() {
        streamClient.detachSurface()
        _uiState.update { it.copy(isSurfaceAttached = false) }
    }

    /** Called when a window's surface goes away; ignored by the client unless it is the rendering surface. */
    fun detachSurface(surface: Surface) {
        streamClient.detachSurface(surface)
        _uiState.update { it.copy(isSurfaceAttached = false) }
    }

    fun cancelPairing() {
        streamClient.cancelPairing()
    }

    fun forgetPairing() {
        viewModelScope.launch { streamClient.forgetPairing() }
    }

    fun setTransportMode(mode: StreamTransportMode) {
        val updated = _uiState.value.config.copy(transportMode = mode)
        _uiState.update { it.copy(config = updated) }
        viewModelScope.launch {
            streamClient.updateConfig(updated)
        }
    }

    fun setCodec(codec: StreamCodec) {
        val updated = _uiState.value.config.copy(codec = codec)
        _uiState.update { it.copy(config = updated) }
        viewModelScope.launch {
            streamClient.updateConfig(updated)
        }
    }

    fun setResolution(resolution: StreamResolution) {
        val updated = _uiState.value.config.copy(
            resolution = resolution,
            bitrateKbps = resolution.recommendedBitrateKbps,
        )
        _uiState.update { it.copy(config = updated) }
        viewModelScope.launch {
            streamClient.updateConfig(updated)
        }
    }

    fun sendInput(event: StreamInputEvent) {
        viewModelScope.launch {
            streamClient.sendInput(event)
        }
    }

    fun launchNativeMoonlight(context: Context): Intent {
        return streamClient.buildExternalMoonlightIntent(context)
    }

    fun toggleAutoSyncClipboard(enabled: Boolean) {
        clipboardBridge.setAutoSync(enabled)
    }

    fun pullClipboard() {
        viewModelScope.launch {
            clipboardBridge.pullFromRemote()
        }
    }

    fun pushClipboard(text: String) {
        viewModelScope.launch {
            clipboardBridge.pushToRemote(text)
        }
    }

    fun pushLocalClipboard() {
        viewModelScope.launch {
            clipboardBridge.pushLocalClipboard()
        }
    }

    fun pasteToTerminal(text: String) {
        viewModelScope.launch {
            clipboardBridge.pasteToActiveTerminal(text)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun provideFactory(
            streamClient: MoonlightStreamClient,
            clipboardBridge: ClipboardBridge,
            moonlightBaseUrlProvider: () -> String = { SiteConfig.MOONLIGHT_URL },
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return StreamViewModel(streamClient, clipboardBridge, moonlightBaseUrlProvider) as T
            }
        }
    }
}
