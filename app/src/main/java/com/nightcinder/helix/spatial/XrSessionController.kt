package com.nightcinder.helix.spatial

import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.SpatialMode
import com.nightcinder.helix.core.spatial.SpatialPanelConfig
import com.nightcinder.helix.core.spatial.SpatialPanelId
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import com.nightcinder.helix.core.spatial.XrSessionGateState
import com.nightcinder.helix.core.spatial.XrSessionLifecycleGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * High-level Android XR Session Controller managing the lifecycle gate, spatial modes,
 * curved multi-panel cockpit layout, and floating media presets.
 *
 * Guarantees that `Session.create()` is strictly invoked only when [XrSessionLifecycleGate.canCreateSession]
 * returns true (requiring both runtime permissions and active activity resumed state).
 */
class XrSessionController(
    val backendDetector: DisplayBackendDetector,
    val gate: XrSessionLifecycleGate = XrSessionLifecycleGate(
        initialAvailable = true,
        initialPermissionsGranted = false,
        initialResumed = false,
    ),
) {
    companion object {
        const val EXTRA_HELIX_PANEL_ID = "helix_panel_id"

        val DEFAULT_PANELS: List<SpatialPanelConfig> = listOf(
            SpatialPanelConfig(
                id = SpatialPanelId.CENTER_DESKTOP,
                title = "Moonlight Desktop",
                visible = true,
                widthDp = 1280,
                heightDp = 720,
                offsetXDp = 0f,
                offsetYDp = 0f,
                offsetZDp = -1.5f,
                yawDegrees = 0f,
            ),
            SpatialPanelConfig(
                id = SpatialPanelId.LEFT_TERMINAL,
                title = "Termix Terminal",
                visible = true,
                widthDp = 860,
                heightDp = 720,
                offsetXDp = -1.05f,
                offsetYDp = 0f,
                offsetZDp = -1.35f,
                yawDegrees = 22f, // Angled inward to user
            ),
            SpatialPanelConfig(
                id = SpatialPanelId.RIGHT_WORKSPACE,
                title = "XPipe & Homelab",
                visible = true,
                widthDp = 860,
                heightDp = 720,
                offsetXDp = 1.05f,
                offsetYDp = 0f,
                offsetZDp = -1.35f,
                yawDegrees = -22f, // Angled inward to user
            ),
            SpatialPanelConfig(
                id = SpatialPanelId.FLOATING_MEDIA,
                title = "Media Player",
                visible = false,
                widthDp = 640,
                heightDp = 380,
                offsetXDp = 0.85f,
                offsetYDp = 0.55f,
                offsetZDp = -1.2f,
                yawDegrees = -15f,
            ),
        )
    }

    private val _displayBackend = MutableStateFlow<WorkspaceDisplayBackend>(backendDetector.detect())
    val displayBackend: StateFlow<WorkspaceDisplayBackend> = _displayBackend.asStateFlow()

    private val _spatialMode = MutableStateFlow(SpatialMode.HOME_SPACE)
    val spatialMode: StateFlow<SpatialMode> = _spatialMode.asStateFlow()

    private val _gateState = MutableStateFlow(gate.currentState)
    val gateState: StateFlow<XrSessionGateState> = _gateState.asStateFlow()

    private val _panels = MutableStateFlow(DEFAULT_PANELS)
    val panels: StateFlow<List<SpatialPanelConfig>> = _panels.asStateFlow()

    init {
        refreshBackend()
    }

    fun refreshBackend() {
        val detected = backendDetector.detect()
        _displayBackend.value = detected
        val isXr = detected is WorkspaceDisplayBackend.AndroidXrSpatial
        gate.onAvailabilityChanged(isXr)
        _gateState.value = gate.currentState
    }

    fun onLifecycleChanged(isResumed: Boolean) {
        gate.onLifecycleChanged(isResumed)
        _gateState.value = gate.currentState
    }

    fun onPermissionsResult(granted: Boolean) {
        gate.onPermissionsChanged(granted)
        _gateState.value = gate.currentState
    }

    fun onSessionError(message: String) {
        gate.onSessionError(message)
        _gateState.value = gate.currentState
    }

    fun clearError() {
        gate.clearError()
        _gateState.value = gate.currentState
    }

    fun requestHomeSpace() {
        _spatialMode.value = SpatialMode.HOME_SPACE
    }

    fun requestFullSpace() {
        _spatialMode.value = SpatialMode.FULL_SPACE
    }

    fun togglePanelVisibility(panelId: SpatialPanelId) {
        _panels.update { current ->
            current.map { panel ->
                if (panel.id == panelId) {
                    panel.copy(visible = !panel.visible)
                } else {
                    panel
                }
            }
        }
    }

    fun setPanelVisibility(panelId: SpatialPanelId, visible: Boolean) {
        _panels.update { current ->
            current.map { panel ->
                if (panel.id == panelId) {
                    panel.copy(visible = visible)
                } else {
                    panel
                }
            }
        }
    }

    fun canCreateSession(): Boolean = gate.canCreateSession()

}
