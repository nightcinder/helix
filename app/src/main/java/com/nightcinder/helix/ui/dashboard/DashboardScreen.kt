package com.nightcinder.helix.ui.dashboard

import com.nightcinder.helix.core.model.SiteConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nightcinder.helix.core.model.HostHealthItem
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.LiveHostMetrics
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.TermixSnippet
import com.nightcinder.helix.core.model.WolWakeResult
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import com.nightcinder.helix.ui.auth.AuthScreen
import com.nightcinder.helix.ui.layout.CockpitLayoutMode
import com.nightcinder.helix.ui.layout.LocalCockpitLayoutManager
import com.nightcinder.helix.ui.snippets.SnippetsScreen
import com.nightcinder.helix.ui.state.DashboardUiState
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.MonitoringUiState
import com.nightcinder.helix.ui.state.MonitoringViewModel
import androidx.compose.material.icons.filled.Tv
import com.nightcinder.helix.ui.spatial.AndroidXrCockpitScreen
import com.nightcinder.helix.ui.spatial.PixelTrackpadDeck
import com.nightcinder.helix.ui.state.SpatialUiState
import com.nightcinder.helix.ui.state.SpatialViewModel
import com.nightcinder.helix.ui.state.StreamUiState
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalUiState
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.TreeUiState
import com.nightcinder.helix.ui.state.TreeViewModel
import com.nightcinder.helix.ui.state.WorkspaceUiState
import com.nightcinder.helix.ui.state.WorkspaceViewModel
import com.nightcinder.helix.ui.stream.StreamScreen
import com.nightcinder.helix.ui.terminal.TerminalScreen
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError
import com.nightcinder.helix.ui.tree.HelixTreeApp
import com.nightcinder.helix.ui.workspace.WorkspaceTreeScreen
import kotlinx.coroutines.launch
import com.nightcinder.helix.ui.panels.CockpitArea
import com.nightcinder.helix.ui.panels.HelixPanelsHost

enum class DashboardDestination(
    val title: String,
    val icon: ImageVector,
) {
    HOSTS("Hosts", Icons.Default.Computer),
    DESKTOP("Desktop", Icons.Default.Tv),
    SPATIAL("Spatial", Icons.Default.ViewInAr),
    TERMINAL("Terminal", Icons.Default.Terminal),
    WORKSPACE("Workspace", Icons.Default.Layers),
    SNIPPETS("Snippets", Icons.Default.Code),
    SESSION("Session", Icons.Default.Security),
}

/**
 * Concept B entry point. The sign-in screen gates everything; once authenticated the panel cockpit
 * (see ui/panels) renders for phone/tablet, Android XR Home Space and Android XR Full Space.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    uiState: DashboardUiState,
    terminalViewModel: TerminalViewModel? = null,
    terminalUiState: TerminalUiState? = null,
    workspaceViewModel: WorkspaceViewModel? = null,
    workspaceUiState: WorkspaceUiState? = null,
    streamViewModel: StreamViewModel? = null,
    streamUiState: StreamUiState? = null,
    spatialViewModel: SpatialViewModel? = null,
    spatialUiState: SpatialUiState? = null,
    monitoringViewModel: MonitoringViewModel? = null,
    monitoringUiState: MonitoringUiState = MonitoringUiState(),
    treeViewModel: TreeViewModel? = null,
    treeUiState: TreeUiState? = null,
    initialDestination: DashboardDestination = DashboardDestination.HOSTS,
    initialPanelId: String? = null,
    onDetachWindow: (String) -> Unit = {},
    onRequestFullSpace: () -> Unit = { spatialViewModel?.setFullSpace() },
    onRequestHomeSpace: () -> Unit = { spatialViewModel?.setHomeSpace() },
    modifier: Modifier = Modifier,
) {
    if (!uiState.authState.isAuthenticated) {
        AuthScreen(
            sessionState = uiState.authState,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onSignInPasskey = { viewModel.signInWithPasskey(it) },
            onSilentRefresh = { viewModel.silentRefresh() },
            onSignOut = { viewModel.signOut() },
            onUpdateConfig = { viewModel.updateAuthentikConfig(it) },
        )
        return
    }

    if (spatialViewModel == null || spatialUiState == null ||
        streamViewModel == null || streamUiState == null ||
        terminalViewModel == null || terminalUiState == null ||
        workspaceViewModel == null || workspaceUiState == null
    ) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Cockpit initializing...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Glasses connected: the trackpad deck is opt-in (menu entry) with a way back, instead of replacing the
    // whole app for as long as the glasses stay plugged in.
    var showTrackpad by rememberSaveable { mutableStateOf(false) }
    val glasses = spatialUiState.isUrxrGlasses
    if (glasses && showTrackpad) {
        Box(modifier = modifier.fillMaxSize()) {
            PixelTrackpadDeck(
                streamViewModel = streamViewModel,
                terminalViewModel = terminalViewModel,
                dashboardViewModel = viewModel,
                spatialViewModel = spatialViewModel,
                dashboardUiState = uiState,
            )
            OutlinedButton(
                onClick = { showTrackpad = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text("Back to cockpit")
            }
        }
        return
    }

    val layoutMode = LocalCockpitLayoutManager.current?.mode?.collectAsStateWithLifecycle()?.value
        ?: CockpitLayoutMode.PANELS
    val panelsInitialArea = resolvePanelsInitialArea(
        initialDestination = initialDestination,
        streamUiState = streamUiState,
    )

    if (layoutMode == CockpitLayoutMode.TREE && treeViewModel != null && treeUiState != null) {
        HelixTreeApp(
            dashboardViewModel = viewModel,
            dashboardUiState = uiState,
            terminalViewModel = terminalViewModel,
            terminalUiState = terminalUiState,
            streamViewModel = streamViewModel,
            streamUiState = streamUiState,
            spatialViewModel = spatialViewModel,
            spatialUiState = spatialUiState,
            treeViewModel = treeViewModel,
            treeUiState = treeUiState,
            onDetachWindow = onDetachWindow,
            onRequestFullSpace = onRequestFullSpace,
            onRequestHomeSpace = onRequestHomeSpace,
            modifier = modifier,
            initialPanelId = initialPanelId,
            monitoringViewModel = monitoringViewModel,
            monitoringUiState = monitoringUiState,
            onOpenTrackpad = if (glasses) ({ showTrackpad = true }) else null,
        )
    } else {
        HelixPanelsHost(
            dashboardViewModel = viewModel,
            dashboardUiState = uiState,
            terminalViewModel = terminalViewModel,
            terminalUiState = terminalUiState,
            workspaceViewModel = workspaceViewModel,
            workspaceUiState = workspaceUiState,
            streamViewModel = streamViewModel,
            streamUiState = streamUiState,
            spatialViewModel = spatialViewModel,
            spatialUiState = spatialUiState,
            monitoringViewModel = monitoringViewModel,
            monitoringUiState = monitoringUiState,
            initialArea = panelsInitialArea,
            onDetachWindow = onDetachWindow,
            onRequestFullSpace = onRequestFullSpace,
            onRequestHomeSpace = onRequestHomeSpace,
            modifier = modifier,
            onOpenTrackpad = if (glasses) ({ showTrackpad = true }) else null,
        )
    }
}

internal fun isActiveMoonlightStream(streamUiState: StreamUiState): Boolean {
    val s = streamUiState.streamState
    val activeStatus = s.status == StreamConnectionStatus.Connecting ||
        s.status == StreamConnectionStatus.Pairing ||
        s.status == StreamConnectionStatus.Connected
    return activeStatus && s.activeTransport != StreamTransportMode.EXTERNAL_NATIVE
}

internal fun resolvePanelsInitialArea(
    initialDestination: DashboardDestination,
    streamUiState: StreamUiState,
): CockpitArea = when {
    isActiveMoonlightStream(streamUiState) -> CockpitArea.STREAM
    initialDestination == DashboardDestination.TERMINAL -> CockpitArea.TERMINAL
    initialDestination == DashboardDestination.WORKSPACE -> CockpitArea.WORKSPACE
    else -> CockpitArea.STREAM
}

@Composable
fun DisplayBackendPill(backend: WorkspaceDisplayBackend) {
    val (pillBg, iconTint) = when (backend) {
        is WorkspaceDisplayBackend.AndroidXrSpatial -> EmeraldAccent.copy(alpha = 0.2f) to EmeraldAccent
        is WorkspaceDisplayBackend.Urxr3Dof -> CyanPrimary.copy(alpha = 0.2f) to CyanPrimary
        is WorkspaceDisplayBackend.ExternalPresentation -> AmberAccent.copy(alpha = 0.2f) to AmberAccent
        WorkspaceDisplayBackend.Standard2D -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(pillBg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.ViewInAr,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = backend.displayLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = iconTint,
        )
    }
}

@Composable
fun WolStatusBanner(status: WolWakeResult, onDismiss: () -> Unit) {
    val bgColor = if (status.success) EmeraldAccent.copy(alpha = 0.15f) else RoseError.copy(alpha = 0.15f)
    val textColor = if (status.success) EmeraldAccent else RoseError

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.FlashOn, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
            Text(
                text = "WOL: ${status.message ?: if (status.success) "Magic packet transmitted to ${status.deviceName ?: status.macAddress}" else "Failed to send magic packet"}",
                color = textColor,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            )
        }
    }
}

@Composable
fun MetricGaugeCard(
    title: String,
    value: String,
    subtitle: String,
    progress: Double,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = accentColor)
            LinearProgressIndicator(
                progress = { progress.coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accentColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
