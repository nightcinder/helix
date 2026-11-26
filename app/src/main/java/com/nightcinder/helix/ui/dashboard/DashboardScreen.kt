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
fun CockpitTopBar(
    displayBackend: WorkspaceDisplayBackend,
    isLoading: Boolean,
    onRefresh: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "PROJECT HELIX",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                    ),
                    color = CyanPrimary,
                )
                Text(
                    text = "HOMELAB COCKPIT",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Display Backend Pill
                DisplayBackendPill(backend = displayBackend)

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = CyanPrimary,
                    )
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Homelab",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
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

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HostsAdaptiveScaffold(
    uiState: DashboardUiState,
    onSelectHost: (Long) -> Unit,
    onWakeHost: (TermixHost) -> Unit,
    onExecuteSnippet: (Long, Long?) -> Unit,
    onOpenSshTerminal: (TermixHost) -> Unit = {},
    onOpenWorkspace: (Long) -> Unit = {},
    onStreamDesktop: (TermixHost) -> Unit = {},
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>()
    val coroutineScope = rememberCoroutineScope()

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                HostListPane(
                    hosts = uiState.hosts,
                    selectedHostId = uiState.selectedHostId,
                    liveMetrics = uiState.liveMetrics,
                    healthSummary = uiState.healthSummary,
                    onSelectHost = { host ->
                        onSelectHost(host.id)
                        coroutineScope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                        }
                    },
                    onWakeHost = onWakeHost,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                HostDetailPane(
                    host = uiState.selectedHost,
                    metrics = uiState.selectedHostMetrics,
                    snippets = uiState.snippets,
                    onWakeHost = onWakeHost,
                    onExecuteSnippet = onExecuteSnippet,
                    onOpenSshTerminal = onOpenSshTerminal,
                    onOpenWorkspace = onOpenWorkspace,
                    onStreamDesktop = onStreamDesktop,
                    onBackToList = {
                        coroutineScope.launch {
                            navigator.navigateBack()
                        }
                    },
                )
            }
        },
    )
}

@Composable
fun HostListPane(
    hosts: List<TermixHost>,
    selectedHostId: Long?,
    liveMetrics: Map<Long, LiveHostMetrics>,
    healthSummary: com.nightcinder.helix.core.model.HealthSummary?,
    onSelectHost: (TermixHost) -> Unit,
    onWakeHost: (TermixHost) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredHosts = remember(hosts, searchQuery) {
        if (searchQuery.isBlank()) hosts
        else hosts.filter { host ->
            host.name.contains(searchQuery, ignoreCase = true) ||
                host.ip.contains(searchQuery, ignoreCase = true) ||
                host.hostname?.contains(searchQuery, ignoreCase = true) == true ||
                host.tags.any { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter hosts...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            // Host Cards List
            if (filteredHosts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (hosts.isEmpty()) "No hosts available. Check Authentik SSO / Termix session." else "No matching hosts found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredHosts, key = { it.id }) { host ->
                        val isSelected = host.id == selectedHostId
                        val hostMetrics = liveMetrics[host.id]
                        val healthItem = healthSummary?.hosts?.firstOrNull { it.hostId == host.id }

                        HostListItemCard(
                            host = host,
                            metrics = hostMetrics,
                            healthItem = healthItem,
                            isSelected = isSelected,
                            onClick = { onSelectHost(host) },
                            onWake = { onWakeHost(host) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HostListItemCard(
    host: TermixHost,
    metrics: LiveHostMetrics?,
    healthItem: HostHealthItem?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onWake: () -> Unit,
) {
    val status = healthItem?.resolvedStatus?.takeIf { it != HostStatus.UNKNOWN } ?: host.status
    val isOffline = status == HostStatus.OFFLINE || status == HostStatus.SLEEPING
    val isQuickWakeHost = host.effectiveHomelabProfile?.highlightWhenOffline == true

    val statusColor = when (status) {
        HostStatus.ONLINE -> EmeraldAccent
        HostStatus.DEGRADED -> AmberAccent
        HostStatus.OFFLINE -> RoseError
        HostStatus.SLEEPING -> AmberAccent
        HostStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(14.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, CyanPrimary) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = host.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    host.effectiveHomelabProfile?.let { profile ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = profile.slug,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                // WOL Quick Action if offline or the configured quick-wake host
                if (isOffline || isQuickWakeHost || host.macAddress != null) {
                    IconButton(
                        onClick = onWake,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = "Wake on LAN",
                            tint = if (isOffline && isQuickWakeHost) AmberAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // IP & Distro row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${host.displayAddress}:${host.port}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = host.os ?: host.distro ?: "Linux",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // CPU & RAM Quick Bars
            if (metrics != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    metrics.cpuPercent?.let { cpu ->
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("CPU", style = MaterialTheme.typography.labelSmall)
                                Text("${cpu.toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { (cpu / 100f).coerceIn(0.0, 1.0).toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = if (cpu > 85) RoseError else CyanPrimary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }

                    metrics.memoryPercent?.let { mem ->
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("RAM", style = MaterialTheme.typography.labelSmall)
                                Text("${mem.toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { (mem / 100f).coerceIn(0.0, 1.0).toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = if (mem > 85) RoseError else EmeraldAccent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HostDetailPane(
    host: TermixHost?,
    metrics: LiveHostMetrics?,
    snippets: List<TermixSnippet>,
    onWakeHost: (TermixHost) -> Unit,
    onExecuteSnippet: (Long, Long?) -> Unit,
    onOpenSshTerminal: (TermixHost) -> Unit = {},
    onOpenWorkspace: (Long) -> Unit = {},
    onStreamDesktop: (TermixHost) -> Unit = {},
    onBackToList: () -> Unit,
) {
    if (host == null) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Select a host to view cockpit telemetry & controls", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Host Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = host.displayName,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = CyanPrimary,
                            )
                            Text(
                                text = "${host.displayAddress} Â· Port ${host.port} Â· ${host.os ?: "Linux"}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // Wake-on-LAN Button (Dynamic lookup)
                        Button(
                            onClick = { onWakeHost(host) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmberAccent,
                                contentColor = Color.Black,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Wake Host", fontWeight = FontWeight.Bold)
                        }
                    }

                    // Host Quick-Launch actions (SSH Terminal, XPipe / Docker, Moonlight Desktop)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { onOpenSshTerminal(host) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyanPrimary,
                                    contentColor = Color.Black,
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open SSH Terminal", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { onOpenWorkspace(host.id) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("XPipe / Docker", fontWeight = FontWeight.Bold)
                            }
                        }

                        // Stream Desktop (Moonlight) button, highlighted on the recommended stream host or when available
                        val isRecommendedStreamHost = SiteConfig.isRecommendedStreamHost(host.name, host.displayName)
                        Button(
                            onClick = { onStreamDesktop(host) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecommendedStreamHost) EmeraldAccent else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isRecommendedStreamHost) Color.Black else MaterialTheme.colorScheme.onSurface,
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stream Desktop (Moonlight)", fontWeight = FontWeight.Bold)
                        }
                    }

                    host.effectiveHomelabProfile?.let { profile ->
                        Text(
                            text = profile.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Live Metrics Gauges Grid
            Text(
                text = "Live Telemetry & Metrics",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricGaugeCard(
                    title = "CPU",
                    value = metrics?.cpuPercent?.let { "${it.toInt()}%" } ?: "N/A",
                    subtitle = metrics?.cpuCores?.let { "$it cores" } ?: "Load",
                    progress = (metrics?.cpuPercent ?: 0.0) / 100.0,
                    accentColor = CyanPrimary,
                    modifier = Modifier.weight(1f),
                )
                MetricGaugeCard(
                    title = "Memory",
                    value = metrics?.memoryPercent?.let { "${it.toInt()}%" } ?: "N/A",
                    subtitle = metrics?.memoryUsedBytes?.let { "${it / (1024 * 1024 * 1024)} GB" } ?: "RAM",
                    progress = (metrics?.memoryPercent ?: 0.0) / 100.0,
                    accentColor = EmeraldAccent,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricGaugeCard(
                    title = "Disk",
                    value = metrics?.diskPercent?.let { "${it.toInt()}%" } ?: "N/A",
                    subtitle = metrics?.diskUsedBytes?.let { "${it / (1024 * 1024 * 1024)} GB" } ?: "Storage",
                    progress = (metrics?.diskPercent ?: 0.0) / 100.0,
                    accentColor = AmberAccent,
                    modifier = Modifier.weight(1f),
                )
                MetricGaugeCard(
                    title = "Network",
                    value = metrics?.networkRxBytesPerSec?.let { "${it / 1024} KB/s" } ?: "0 KB/s",
                    subtitle = metrics?.networkTxBytesPerSec?.let { "Tx: ${it / 1024} KB/s" } ?: "Rx / Tx",
                    progress = 0.5,
                    accentColor = CyanPrimary,
                    modifier = Modifier.weight(1f),
                )
            }

            // Quick Snippet Bar
            Text(
                text = "Quick Snippets on ${host.displayName}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )

            if (snippets.isEmpty()) {
                Text(
                    text = "No snippets configured in Termix",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(snippets, key = { it.id }) { snippet ->
                        OutlinedButton(
                            onClick = { onExecuteSnippet(snippet.id, host.id) },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(snippet.name)
                        }
                    }
                }
            }

            // Top Processes Table
            if (!metrics?.topProcesses.isNullOrEmpty()) {
                Text(
                    text = "Top Host Processes",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        metrics?.topProcesses?.take(5)?.forEach { proc ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = proc.name.ifBlank { proc.command ?: "process" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "CPU ${proc.cpuPercent?.toInt() ?: 0}% Â· RAM ${proc.memoryPercent?.toInt() ?: 0}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
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
