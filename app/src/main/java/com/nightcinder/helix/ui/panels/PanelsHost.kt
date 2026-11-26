package com.nightcinder.helix.ui.panels

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.xr.compose.platform.LocalSpatialCapabilities
import com.nightcinder.helix.ui.media.LocalMediaViewModel
import com.nightcinder.helix.ui.spotify.LocalSpotifyViewModel
import com.nightcinder.helix.ui.monitoring.AlertCountBadge
import com.nightcinder.helix.ui.spatial.AndroidXrCockpitScreen
import com.nightcinder.helix.ui.spotify.SpotifyPanel
import com.nightcinder.helix.ui.state.DashboardUiState
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.MonitoringUiState
import com.nightcinder.helix.ui.state.MonitoringViewModel
import com.nightcinder.helix.ui.state.SpatialUiState
import com.nightcinder.helix.ui.state.SpatialViewModel
import com.nightcinder.helix.ui.state.StreamUiState
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalUiState
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.WorkspaceUiState
import com.nightcinder.helix.ui.state.WorkspaceViewModel

/** Width at which phone/tablet layouts switch to the terminal + right-wing two-pane split. */
private val WIDE_BREAKPOINT = 840.dp

private val PanelNavStateSaver = listSaver<PanelNavState, Int>(
    save = { it.toSaveable() },
    restore = { PanelNavState.fromSaveable(it) ?: PanelNavState() },
)

/** Home Space only: opens Terminal / Workspace / Stream as separate windows via [onDetachWindow]. */
@Composable
private fun DetachBar(onDetachWindow: (String) -> Unit) {
    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            androidx.compose.material3.Text(
                "Detach",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PanelNavState.DETACHABLE_PANELS.forEach { (panelId, label) ->
                androidx.compose.material3.TextButton(
                    onClick = { onDetachWindow(panelId) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    androidx.compose.material3.Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun CockpitArea.icon(): ImageVector = when (this) {
    CockpitArea.STREAM -> Icons.Default.Tv
    CockpitArea.TERMINAL -> Icons.Default.Terminal
    CockpitArea.WORKSPACE -> Icons.Default.Layers
}

/**
 * Concept B root: routes between Full Space (spatial panels), Home Space (single tabbed panel) and
 * phone/tablet (navigation suite). The sign-in gate lives in DashboardScreen above this.
 */
@Composable
fun HelixPanelsHost(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    terminalViewModel: TerminalViewModel,
    terminalUiState: TerminalUiState,
    workspaceViewModel: WorkspaceViewModel,
    workspaceUiState: WorkspaceUiState,
    streamViewModel: StreamViewModel,
    streamUiState: StreamUiState,
    spatialViewModel: SpatialViewModel,
    spatialUiState: SpatialUiState,
    initialArea: CockpitArea,
    onDetachWindow: (String) -> Unit,
    onRequestFullSpace: () -> Unit,
    onRequestHomeSpace: () -> Unit,
    modifier: Modifier = Modifier,
    monitoringViewModel: MonitoringViewModel? = null,
    monitoringUiState: MonitoringUiState = MonitoringUiState(),
    /** Non-null only while URXR glasses are connected: opens the trackpad deck from the menu. */
    onOpenTrackpad: (() -> Unit)? = null,
) {
    val inFullSpace = runCatching { LocalSpatialCapabilities.current.isSpatialUiEnabled }.getOrDefault(false)
    // Single nav state for every layout so tabs survive Home Space <-> Full Space switches.
    var nav by rememberSaveable(initialArea, stateSaver = PanelNavStateSaver) {
        mutableStateOf(PanelNavState(area = initialArea))
    }
    var showSession by rememberSaveable { mutableStateOf(false) }
    val spotifyViewModel = LocalSpotifyViewModel.current
    // Home Space has no video surface: leaving Full Space unbinds the Media3 surface and pauses playback.
    val mediaViewModel = LocalMediaViewModel.current
    LaunchedEffect(inFullSpace, mediaViewModel) {
        if (!inFullSpace) mediaViewModel?.onSurfaceDestroyed()
    }
    var showSpotify by rememberSaveable { mutableStateOf(false) }
    if (showSpotify && !inFullSpace && spotifyViewModel != null) {
        Dialog(
            onDismissRequest = { showSpotify = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                color = Color(0xFF0A0F14),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f),
            ) {
                SpotifyPanel(viewModel = spotifyViewModel, onClose = { showSpotify = false })
            }
        }
    }
    if (showSession && !inFullSpace) {
        SessionDialog(
            dashboardViewModel = dashboardViewModel,
            dashboardUiState = dashboardUiState,
            onClose = { showSession = false },
        )
    }
    if (inFullSpace) {
        AndroidXrCockpitScreen(
            nav = nav,
            onNavChange = { nav = it },
            showSession = showSession,
            onShowSession = { showSession = it },
            spatialViewModel = spatialViewModel,
            spatialUiState = spatialUiState,
            dashboardViewModel = dashboardViewModel,
            dashboardUiState = dashboardUiState,
            streamViewModel = streamViewModel,
            streamUiState = streamUiState,
            terminalViewModel = terminalViewModel,
            terminalUiState = terminalUiState,
            workspaceViewModel = workspaceViewModel,
            workspaceUiState = workspaceUiState,
            monitoringViewModel = monitoringViewModel,
            monitoringUiState = monitoringUiState,
            onDetachWindow = onDetachWindow,
            onRequestFullSpace = onRequestFullSpace,
            onRequestHomeSpace = onRequestHomeSpace,
            modifier = modifier,
        )
        return
    }

    val location = rememberNetworkLocation(
        probeHost = NetworkLocationClassifier.hostOf(dashboardUiState.authState.config.termixBaseUrl)
            .ifBlank { NetworkLocationClassifier.DEFAULT_HOST },
    )

    @Composable
    fun strip(trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
        CockpitStatusStrip(
            streamUiState = streamUiState,
            location = location,
            displayBackend = dashboardUiState.displayBackend,
            isLoading = dashboardUiState.isLoading || monitoringUiState.isLoading,
            onRefresh = { dashboardViewModel.refreshHomelabData() },
            onSignOut = { dashboardViewModel.signOut() },
            errorMessage = dashboardUiState.errorMessage,
            onDismissError = { dashboardViewModel.clearError() },
            onOpenSession = { showSession = true },
            onOpenTrackpad = onOpenTrackpad,
            trailing = {
                if (spotifyViewModel != null) {
                    IconButton(onClick = { showSpotify = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MusicNote, contentDescription = "Open Spotify", modifier = Modifier.size(20.dp))
                    }
                }
                trailing()
            },
        )
    }

    val center: @Composable (Modifier) -> Unit = { m ->
        CenterPanel(
            streamViewModel = streamViewModel,
            streamUiState = streamUiState,
            dashboardViewModel = dashboardViewModel,
            dashboardUiState = dashboardUiState,
            modifier = m,
        )
    }
    val terminal: @Composable (Modifier) -> Unit = { m ->
        TerminalPanel(
            terminalViewModel = terminalViewModel,
            terminalUiState = terminalUiState,
            dashboardViewModel = dashboardViewModel,
            dashboardUiState = dashboardUiState,
            nav = nav,
            onNavChange = { nav = it },
            modifier = m,
        )
    }
    val rightWing: @Composable (Modifier) -> Unit = { m ->
        RightWingPanel(
            nav = nav,
            onNavChange = { nav = it },
            dashboardViewModel = dashboardViewModel,
            dashboardUiState = dashboardUiState,
            workspaceViewModel = workspaceViewModel,
            workspaceUiState = workspaceUiState,
            monitoringViewModel = monitoringViewModel,
            monitoringUiState = monitoringUiState,
            onOpenDockerConsole = { hostId, hostName, containerId, containerName ->
                terminalViewModel.openDockerConsoleTab(hostId, hostName, containerId, containerName)
                nav = nav.showTerminal()
            },
            onOpenHostTerminal = { host ->
                if (!host.enableTerminal) return@RightWingPanel
                terminalViewModel.openHostSshTab(host)
                nav = nav.showTerminal()
            },
            modifier = m,
        )
    }

    if (spatialUiState.isAndroidXr) {
        // Android XR Home Space: ONE panel, the three areas as top-level tabs.
        Column(modifier = modifier.fillMaxSize()) {
            strip {
                IconButton(onClick = onRequestFullSpace, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "Enter Full Space", modifier = Modifier.size(20.dp))
                }
            }
            PanelTabRow(
                titles = CockpitArea.entries.map { it.title },
                selectedIndex = nav.area.ordinal,
                onSelect = { nav = nav.selectArea(CockpitArea.entries[it]) },
                badges = mapOf(
                    CockpitArea.WORKSPACE.ordinal to {
                        AlertCountBadge(
                            count = monitoringUiState.activeAlertCount,
                            hasCritical = monitoringUiState.hasCriticalAlerts,
                        )
                    },
                ),
            )
            DetachBar(onDetachWindow = onDetachWindow)
            when (nav.area) {
                CockpitArea.STREAM -> center(Modifier.weight(1f))
                CockpitArea.TERMINAL -> terminal(Modifier.weight(1f))
                CockpitArea.WORKSPACE -> rightWing(Modifier.weight(1f))
            }
        }
        return
    }

    // Phone / tablet 2D.
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            CockpitArea.entries.forEach { area ->
                item(
                    icon = {
                        if (area == CockpitArea.WORKSPACE && monitoringUiState.activeAlertCount > 0) {
                            BadgedBox(
                                badge = {
                                    AlertCountBadge(
                                        count = monitoringUiState.activeAlertCount,
                                        hasCritical = monitoringUiState.hasCriticalAlerts,
                                    )
                                },
                            ) {
                                Icon(area.icon(), contentDescription = area.title)
                            }
                        } else {
                            Icon(area.icon(), contentDescription = area.title)
                        }
                    },
                    label = { androidx.compose.material3.Text(area.title) },
                    selected = nav.area == area,
                    onClick = { nav = nav.selectArea(area) },
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            strip {}
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= WIDE_BREAKPOINT
                if (wide && nav.isSplitArea) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        terminal(Modifier.weight(1f))
                        rightWing(Modifier.weight(1f))
                    }
                } else {
                    when (nav.area) {
                        CockpitArea.STREAM -> center(Modifier.fillMaxSize())
                        CockpitArea.TERMINAL -> terminal(Modifier.fillMaxSize())
                        CockpitArea.WORKSPACE -> rightWing(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
