package com.nightcinder.helix.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.SpatialMode
import com.nightcinder.helix.core.spatial.SpatialPanelConfig
import com.nightcinder.helix.core.spatial.SpatialPanelId
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpatialUiState(
    val displayBackend: WorkspaceDisplayBackend = WorkspaceDisplayBackend.Standard2D,
    val spatialMode: SpatialMode = SpatialMode.HOME_SPACE,
    val panels: Map<SpatialPanelId, SpatialPanelConfig> = defaultSpatialPanels(),
    val urxrPresentationActive: Boolean = false,
    val recenterEpochMs: Long = 0L,
    val errorMessage: String? = null,
) {
    val isFullSpace: Boolean
        get() = spatialMode == SpatialMode.FULL_SPACE

    val isAndroidXr: Boolean
        get() = displayBackend is WorkspaceDisplayBackend.AndroidXrSpatial

    val isUrxrGlasses: Boolean
        get() = displayBackend is WorkspaceDisplayBackend.Urxr3Dof
}

/**
 * Default panel configuration. Only [SpatialPanelConfig.visible] is honoured by the Full Space layout today;
 * the offset/yaw values are NOT used for placement yet.
 */
fun defaultSpatialPanels(): Map<SpatialPanelId, SpatialPanelConfig> = mapOf(
    SpatialPanelId.CENTER_DESKTOP to SpatialPanelConfig(
        id = SpatialPanelId.CENTER_DESKTOP,
        title = "Moonlight Desktop",
        visible = true,
        widthDp = 1280,
        heightDp = 720,
        offsetXDp = 0f,
        offsetYDp = 0f,
        offsetZDp = 1.6f,
        yawDegrees = 0f,
    ),
    SpatialPanelId.LEFT_TERMINAL to SpatialPanelConfig(
        id = SpatialPanelId.LEFT_TERMINAL,
        title = "Helix Terminal",
        visible = true,
        widthDp = 860,
        heightDp = 680,
        offsetXDp = -1.1f,
        offsetYDp = 0f,
        offsetZDp = 1.4f,
        yawDegrees = 22f,
    ),
    SpatialPanelId.RIGHT_WORKSPACE to SpatialPanelConfig(
        id = SpatialPanelId.RIGHT_WORKSPACE,
        title = "XPipe Workspace & Telemetry",
        visible = true,
        widthDp = 860,
        heightDp = 680,
        offsetXDp = 1.1f,
        offsetYDp = 0f,
        offsetZDp = 1.4f,
        yawDegrees = -22f,
    ),
    SpatialPanelId.FLOATING_MEDIA to SpatialPanelConfig(
        id = SpatialPanelId.FLOATING_MEDIA,
        title = "Media Player (Jellyfin/Plex)",
        visible = false,
        widthDp = 720,
        heightDp = 480,
        offsetXDp = 0f,
        offsetYDp = 0.7f,
        offsetZDp = 1.5f,
        yawDegrees = 0f,
    ),
)

class SpatialViewModel(
    private val displayBackendDetector: DisplayBackendDetector,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpatialUiState())
    val uiState: StateFlow<SpatialUiState> = _uiState.asStateFlow()

    init {
        detectDisplayBackend()
    }

    fun detectDisplayBackend() {
        val backend = displayBackendDetector.detect()
        _uiState.update { it.copy(displayBackend = backend) }
    }

    fun setHomeSpace() {
        _uiState.update { it.copy(spatialMode = SpatialMode.HOME_SPACE) }
    }

    fun setFullSpace() {
        _uiState.update { it.copy(spatialMode = SpatialMode.FULL_SPACE) }
    }

    fun togglePanel(panelId: SpatialPanelId, visible: Boolean? = null) {
        _uiState.update { current ->
            val existing = current.panels[panelId] ?: return@update current
            val newVisibility = visible ?: !existing.visible
            val updatedPanels = current.panels + (panelId to existing.copy(visible = newVisibility))
            current.copy(panels = updatedPanels)
        }
    }

    fun recenter3Dof() {
        _uiState.update { it.copy(recenterEpochMs = System.currentTimeMillis()) }
    }

    fun setUrxrPresentationActive(active: Boolean) {
        _uiState.update { it.copy(urxrPresentationActive = active) }
    }

    companion object {
        fun provideFactory(
            displayBackendDetector: DisplayBackendDetector,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SpatialViewModel(displayBackendDetector) as T
            }
        }
    }
}
