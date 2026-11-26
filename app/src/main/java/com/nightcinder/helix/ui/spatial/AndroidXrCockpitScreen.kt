package com.nightcinder.helix.ui.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropLandscape
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterDefaults
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialActivityPanel
import androidx.xr.compose.subspace.SpatialActivityPanelController
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.compose.subspace.layout.width
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.spatial.SpatialPanelId
import com.nightcinder.helix.media.SpatialFinHost
import com.nightcinder.helix.media.SpatialFinState
import com.nightcinder.helix.ui.media.LocalMediaViewModel
import com.nightcinder.helix.ui.media.MediaPanel
import com.nightcinder.helix.ui.spotify.LocalSpotifyViewModel
import com.nightcinder.helix.ui.monitoring.AlertCountBadge
import com.nightcinder.helix.ui.panels.CenterPanel
import com.nightcinder.helix.ui.panels.CockpitStatusStrip
import com.nightcinder.helix.ui.panels.NetworkLocationClassifier
import com.nightcinder.helix.ui.panels.PanelNavState
import com.nightcinder.helix.ui.panels.RightTab
import com.nightcinder.helix.ui.panels.RightWingPanel
import com.nightcinder.helix.ui.panels.SessionPanel
import com.nightcinder.helix.ui.panels.TerminalPanel
import com.nightcinder.helix.ui.panels.rememberNetworkLocation
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
import com.nightcinder.helix.ui.theme.CyanPrimary

@Composable
fun AndroidXrCockpitScreen(
    spatialViewModel: SpatialViewModel,
    spatialUiState: SpatialUiState,
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    streamViewModel: StreamViewModel,
    streamUiState: StreamUiState,
    terminalViewModel: TerminalViewModel,
    terminalUiState: TerminalUiState,
    workspaceViewModel: WorkspaceViewModel,
    workspaceUiState: WorkspaceUiState,
    // Hoisted by HelixPanelsHost so tabs survive Home Space <-> Full Space switches.
    nav: PanelNavState,
    onNavChange: (PanelNavState) -> Unit,
    showSession: Boolean,
    onShowSession: (Boolean) -> Unit,
    onDetachWindow: (String) -> Unit,
    onRequestFullSpace: () -> Unit = { spatialViewModel.setFullSpace() },
    onRequestHomeSpace: () -> Unit = { spatialViewModel.setHomeSpace() },
    monitoringViewModel: MonitoringViewModel? = null,
    monitoringUiState: MonitoringUiState = MonitoringUiState(),
    modifier: Modifier = Modifier,
) {
    // Full Space only. Home Space (single tabbed panel) is rendered by HelixPanelsHost.
    val inFullSpace = runCatching { LocalSpatialCapabilities.current.isSpatialUiEnabled }.getOrDefault(false)
    if (!inFullSpace) return

    val isActivelyStreaming = streamUiState.streamState.status == StreamConnectionStatus.Connected &&
        streamUiState.streamState.activeTransport != StreamTransportMode.EXTERNAL_NATIVE

    val centerHeightDp = 780
    val centerWidthDp = streamUiState.config.resolution.spatialPanelWidthDp(
        videoHeightDp = centerHeightDp,
        chromeHorizontalDp = 0,
        minWidthDp = 1120,
        maxWidthDp = 1680,
    )
    val wingWidthDp = 660
    val wingHeightDp = 680
    val wingOffsetXDp = streamUiState.config.resolution.spatialWingOffsetXDp(
        centerWidthDp = centerWidthDp,
        wingWidthDp = wingWidthDp,
    )

    // Panels missing from the map are treated as visible.
    val centerVisible = spatialUiState.panels[SpatialPanelId.CENTER_DESKTOP]?.visible != false
    val leftVisible = spatialUiState.panels[SpatialPanelId.LEFT_TERMINAL]?.visible != false
    val rightVisible = spatialUiState.panels[SpatialPanelId.RIGHT_WORKSPACE]?.visible != false
    // Media is off by default: missing from the map means hidden.
    val mediaVisible = spatialUiState.panels[SpatialPanelId.FLOATING_MEDIA]?.visible == true

    // Keep Orbiter subcomposition reading fresh nav state & callbacks (Orbiter lambdas can capture stale closures).
    val currentNav by rememberUpdatedState(nav)
    val currentOnNavChange by rememberUpdatedState(onNavChange)
    val currentLeftVisible by rememberUpdatedState(leftVisible)
    val currentRightVisible by rememberUpdatedState(rightVisible)
    val currentOnRequestHomeSpace by rememberUpdatedState(onRequestHomeSpace)
    val currentOnShowSession by rememberUpdatedState(onShowSession)
    val currentHosts by rememberUpdatedState(dashboardUiState.hosts)
    val currentWolDevices by rememberUpdatedState(dashboardUiState.wolDevices)
    var showAlertsPanel by rememberSaveable { mutableStateOf(false) }
    var showSpotifyPanel by rememberSaveable { mutableStateOf(false) }
    val spotifyViewModel = LocalSpotifyViewModel.current
    val currentSpotifyAvailable by rememberUpdatedState(spotifyViewModel != null)
    val mediaViewModel = LocalMediaViewModel.current
    val currentMediaAvailable by rememberUpdatedState(mediaViewModel != null)
    val currentMediaVisible by rememberUpdatedState(mediaVisible)
    val context = LocalContext.current
    val spatialFinHost = remember(context) { SpatialFinHost.forContext(context) }
    var spatialFinState by remember { mutableStateOf<SpatialFinState>(SpatialFinState.Closed) }
    val spatialFinController = (spatialFinState as? SpatialFinState.Ready)?.let { ready ->
        remember(ready.intent) { SpatialActivityPanelController(ready.intent) }
    }

    val location = rememberNetworkLocation(
        probeHost = NetworkLocationClassifier.hostOf(dashboardUiState.authState.config.termixBaseUrl)
            .ifBlank { NetworkLocationClassifier.DEFAULT_HOST },
    )

    Subspace(modifier = SubspaceModifier) {
        // 1. Center SpatialPanel: Moonlight desktop stream (opaque surface, edge-to-edge while actively streaming)
        if (centerVisible) SpatialPanel(
            modifier = SubspaceModifier
                .width(centerWidthDp.dp)
                .height(centerHeightDp.dp)
                .offset(0.dp, 0.dp, 0.dp)
                .movable()
                .resizable(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0A0F14),
                shape = RoundedCornerShape(if (isActivelyStreaming) 0.dp else 12.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!isActivelyStreaming) {
                            CockpitStatusStrip(
                                streamUiState = streamUiState,
                                location = location,
                                displayBackend = dashboardUiState.displayBackend,
                                isLoading = dashboardUiState.isLoading || monitoringUiState.isLoading,
                                onRefresh = { dashboardViewModel.refreshHomelabData() },
                                onSignOut = { dashboardViewModel.signOut() },
                                errorMessage = dashboardUiState.errorMessage,
                                onDismissError = { dashboardViewModel.clearError() },
                                onOpenSession = { currentOnShowSession(true) },
                            )
                        }
                        CenterPanel(
                            streamViewModel = streamViewModel,
                            streamUiState = streamUiState,
                            dashboardViewModel = dashboardViewModel,
                            dashboardUiState = dashboardUiState,
                            modifier = Modifier.weight(1f),
                            hostUpByKey = monitoringUiState.snapshot.hostUpByKey,
                        )
                    }

                    // Single consolidated Top Orbiter (no bottom orbiter to avoid Android XR system bar collisions).
                    Orbiter(
                        position = OrbiterPosition.TopCenter(),
                        shape = OrbiterDefaults.Shape,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Black.copy(alpha = 0.82f),
                            modifier = Modifier.padding(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { currentOnRequestHomeSpace() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1F2937),
                                        contentColor = Color.White,
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Icon(Icons.Default.CropLandscape, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Home Space", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        spatialViewModel.togglePanel(SpatialPanelId.RIGHT_WORKSPACE, true)
                                        currentOnNavChange(currentNav.showAlerts())
                                        showAlertsPanel = !showAlertsPanel
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Notifications,
                                        contentDescription = "Open Alerts",
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Alerts", fontSize = 11.sp, color = CyanPrimary)
                                    if (monitoringUiState.activeAlertCount > 0) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        AlertCountBadge(
                                            count = monitoringUiState.activeAlertCount,
                                            hasCritical = monitoringUiState.hasCriticalAlerts,
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = { spatialViewModel.togglePanel(SpatialPanelId.LEFT_TERMINAL) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Text(
                                        if (currentLeftVisible) "Hide Terminal" else "Terminal",
                                        fontSize = 11.sp,
                                        color = CyanPrimary,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { spatialViewModel.togglePanel(SpatialPanelId.RIGHT_WORKSPACE) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Text(
                                        if (currentRightVisible) "Hide Workspace" else "Workspace",
                                        fontSize = 11.sp,
                                        color = CyanPrimary,
                                    )
                                }
                                if (currentSpotifyAvailable) {
                                    OutlinedButton(
                                        onClick = { showSpotifyPanel = !showSpotifyPanel },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp),
                                    ) {
                                        Icon(Icons.Default.MusicNote, contentDescription = "Toggle Spotify", tint = CyanPrimary, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Spotify", fontSize = 11.sp, color = CyanPrimary)
                                    }
                                }
                                if (currentMediaAvailable) {
                                    OutlinedButton(
                                        onClick = { spatialViewModel.togglePanel(SpatialPanelId.FLOATING_MEDIA) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp),
                                    ) {
                                        Icon(Icons.Default.PlayCircle, contentDescription = "Toggle media player", tint = CyanPrimary, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (currentMediaVisible) "Hide Media" else "Media", fontSize = 11.sp, color = CyanPrimary)
                                    }
                                }
                                IconButton(onClick = { currentOnShowSession(!showSession) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Settings, contentDescription = "Session settings", tint = CyanPrimary, modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = {
                                        dashboardViewModel.refreshHomelabData()
                                        monitoringViewModel?.refresh(
                                            hosts = currentHosts,
                                            wolDevices = currentWolDevices,
                                        )
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh homelab", tint = CyanPrimary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Left Wing SpatialPanel: terminal tabs (SSH hosts + docker consoles), snippet orbiter
        if (leftVisible) SpatialPanel(
            modifier = SubspaceModifier
                .width(wingWidthDp.dp)
                .height(wingHeightDp.dp)
                .offset((-wingOffsetXDp).dp, 0.dp, 80.dp)
                .rotate(0f, 22f, 0f)
                .movable()
                .resizable(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0A0F14),
                shape = RoundedCornerShape(12.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    TerminalPanel(
                        terminalViewModel = terminalViewModel,
                        terminalUiState = terminalUiState,
                        dashboardViewModel = dashboardViewModel,
                        dashboardUiState = dashboardUiState,
                        nav = currentNav,
                        onNavChange = currentOnNavChange,
                        showSnippets = false,
                    )

                    if (dashboardUiState.snippets.isNotEmpty()) {
                        Orbiter(
                            position = OrbiterPosition.CenterStart(),
                            shape = OrbiterDefaults.Shape,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(6.dp),
                            ) {
                                Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    dashboardUiState.snippets.take(3).forEach { snippet ->
                                        OutlinedButton(
                                            onClick = {
                                                PanelNavState.snippetTargetHostId(
                                                    activeTabHostId = terminalUiState.activeTab?.target?.hostId,
                                                    selectedHostId = dashboardUiState.selectedHost?.id,
                                                )?.let { hostId ->
                                                    dashboardViewModel.executeSnippet(snippet.id, hostId)
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.height(28.dp),
                                        ) {
                                            Text(snippet.name, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Right Wing SpatialPanel: ONE panel with Workspace | Alerts | Metrics tabs
        if (rightVisible) SpatialPanel(
            modifier = SubspaceModifier
                .width(wingWidthDp.dp)
                .height(wingHeightDp.dp)
                .offset(wingOffsetXDp.dp, 0.dp, 80.dp)
                .rotate(0f, -22f, 0f)
                .movable()
                .resizable(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0A0F14),
                shape = RoundedCornerShape(12.dp),
            ) {
                RightWingPanel(
                    nav = currentNav,
                    onNavChange = currentOnNavChange,
                    dashboardViewModel = dashboardViewModel,
                    dashboardUiState = dashboardUiState,
                    workspaceViewModel = workspaceViewModel,
                    workspaceUiState = workspaceUiState,
                    monitoringViewModel = monitoringViewModel,
                    monitoringUiState = monitoringUiState,
                    onOpenDockerConsole = { hostId, hostName, containerId, containerName ->
                        terminalViewModel.openDockerConsoleTab(hostId, hostName, containerId, containerName)
                        spatialViewModel.togglePanel(SpatialPanelId.LEFT_TERMINAL, true)
                        currentOnNavChange(currentNav.showTerminal())
                    },
                    onOpenHostTerminal = { host ->
                        if (!host.enableTerminal) return@RightWingPanel
                        terminalViewModel.openHostSshTab(host)
                        spatialViewModel.togglePanel(SpatialPanelId.LEFT_TERMINAL, true)
                        currentOnNavChange(currentNav.showTerminal())
                    },
                )
            }
        }

        // 4. Front-floating SpatialPanels for Session & Alerts (avoids SurfaceView Dialog hole-punch collision)
        if (showAlertsPanel) SpatialPanel(
            modifier = SubspaceModifier
                .width(780.dp)
                .height(580.dp)
                .offset(0.dp, 0.dp, 160.dp)
                .movable()
                .resizable(),
        ) {
            Surface(
                color = Color(0xFF0A0F14),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = { showAlertsPanel = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close alerts")
                        }
                        Text(
                            text = "Homelab Alerts & Telemetry",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    RightWingPanel(
                        nav = currentNav.copy(rightTab = RightTab.ALERTS),
                        onNavChange = { updated -> currentOnNavChange(updated) },
                        dashboardViewModel = dashboardViewModel,
                        dashboardUiState = dashboardUiState,
                        workspaceViewModel = workspaceViewModel,
                        workspaceUiState = workspaceUiState,
                        monitoringViewModel = monitoringViewModel,
                        monitoringUiState = monitoringUiState,
                        onOpenDockerConsole = { hostId, hostName, containerId, containerName ->
                            terminalViewModel.openDockerConsoleTab(hostId, hostName, containerId, containerName)
                            spatialViewModel.togglePanel(SpatialPanelId.LEFT_TERMINAL, true)
                            currentOnNavChange(currentNav.showTerminal())
                            showAlertsPanel = false
                        },
                        onOpenHostTerminal = { host ->
                            if (!host.enableTerminal) return@RightWingPanel
                            terminalViewModel.openHostSshTab(host)
                            spatialViewModel.togglePanel(SpatialPanelId.LEFT_TERMINAL, true)
                            currentOnNavChange(currentNav.showTerminal())
                            showAlertsPanel = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (showSpotifyPanel && spotifyViewModel != null) SpatialPanel(
            modifier = SubspaceModifier
                .width(640.dp)
                .height(560.dp)
                .offset(0.dp, -40.dp, 170.dp)
                .movable()
                .resizable(),
        ) {
            Surface(
                color = Color(0xFF0A0F14),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                SpotifyPanel(viewModel = spotifyViewModel, onClose = { showSpotifyPanel = false })
            }
        }

        // 5. Native Media3 player: video surface + controls. Leaving composition (panel hidden, Home Space) destroys
        // the surface, which detaches it from ExoPlayer and pauses.
        if (mediaVisible && mediaViewModel != null) {
            val mediaState = mediaViewModel.uiState.collectAsStateWithLifecycle().value
            if (mediaState.nowPlaying != null) SpatialExternalSurface(
                stereoMode = StereoMode.Mono,
                modifier = SubspaceModifier
                    .width(960.dp)
                    .height(540.dp)
                    .offset(0.dp, 0.dp, 140.dp)
                    .movable()
                    .resizable(),
            ) {
                onSurfaceCreated { surface -> mediaViewModel.onSurfaceCreated(surface) }
                onSurfaceDestroyed { mediaViewModel.onSurfaceDestroyed() }
                DisposableEffect(Unit) { onDispose { mediaViewModel.onSurfaceDestroyed() } }
            }
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(640.dp)
                    .height(560.dp)
                    .offset(if (mediaState.nowPlaying != null) 800.dp else 0.dp, 0.dp, 150.dp)
                    .movable()
                    .resizable(),
            ) {
                Surface(
                    color = Color(0xFF0A0F14),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    MediaPanel(
                        viewModel = mediaViewModel,
                        spatialFin = spatialFinState,
                        onOpenSpatialFin = { spatialFinState = spatialFinHost.open() },
                        onOpenSpatialFinStandalone = {
                            runCatching { context.startActivity(spatialFinHost.standaloneIntent()) }
                                .onFailure { spatialFinState = SpatialFinState.NotInstalled }
                        },
                        onReloadSpatialFin = {
                            val ready = spatialFinState as? SpatialFinState.Ready
                            if (ready != null) {
                                spatialFinState = spatialFinHost.guardedEmbed(ready.intent) { spatialFinController?.startActivity(it) }
                            }
                        },
                        onCloseSpatialFin = { spatialFinState = SpatialFinState.Closed },
                        onClose = { spatialViewModel.togglePanel(SpatialPanelId.FLOATING_MEDIA, false) },
                    )
                }
            }
        }

        // 6. SpatialFin hosted in a SpatialActivityPanel (needs knownActivityEmbeddingCerts trust from SpatialFin).
        if (spatialFinController != null) SpatialActivityPanel(
            controller = spatialFinController,
            modifier = SubspaceModifier
                .width(1280.dp)
                .height(720.dp)
                .offset(0.dp, 0.dp, 200.dp)
                .movable()
                .resizable(),
        )

        if (showSession) SpatialPanel(
            modifier = SubspaceModifier
                .width(780.dp)
                .height(580.dp)
                .offset(0.dp, 0.dp, 180.dp)
                .movable()
                .resizable(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                SessionPanel(
                    dashboardViewModel = dashboardViewModel,
                    dashboardUiState = dashboardUiState,
                    onClose = { currentOnShowSession(false) },
                )
            }
        }
    }
}
