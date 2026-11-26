@file:OptIn(ExperimentalMaterial3Api::class)

package com.nightcinder.helix.ui.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.ui.auth.AuthScreen
import com.nightcinder.helix.ui.snippets.SnippetsScreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import com.nightcinder.helix.ui.dashboard.DisplayBackendPill
import com.nightcinder.helix.ui.dashboard.MetricGaugeCard
import com.nightcinder.helix.ui.dashboard.WolStatusBanner
import com.nightcinder.helix.ui.monitoring.AlertCountBadge
import com.nightcinder.helix.ui.monitoring.AlertsFeedPanel
import com.nightcinder.helix.ui.monitoring.PrometheusHostMetricsSection
import com.nightcinder.helix.ui.state.DashboardUiState
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.MonitoringUiState
import com.nightcinder.helix.ui.state.MonitoringViewModel
import com.nightcinder.helix.ui.state.StreamUiState
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalUiState
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.WorkspaceUiState
import com.nightcinder.helix.ui.state.WorkspaceViewModel
import com.nightcinder.helix.ui.stream.StreamScreen
import com.nightcinder.helix.ui.terminal.TerminalScreen
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError
import com.nightcinder.helix.ui.workspace.WorkspaceTreeScreen
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Status strip
// ---------------------------------------------------------------------------------------------

/** Re-checks LAN/WAN every [intervalMs] so moving between Wi-Fi and mobile data is reflected. */
@Composable
fun rememberNetworkLocation(
    intervalMs: Long = 30_000L,
    probeHost: String = NetworkLocationClassifier.DEFAULT_HOST,
): NetworkLocation {
    val classifier = remember(probeHost) { NetworkLocationClassifier(host = probeHost) }
    val state = produceState(NetworkLocation.CHECKING, classifier) {
        while (true) {
            value = classifier.check()
            delay(intervalMs)
        }
    }
    return state.value
}

@Composable
fun CockpitStatusStrip(
    streamUiState: StreamUiState,
    location: NetworkLocation,
    displayBackend: WorkspaceDisplayBackend,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onOpenSession: (() -> Unit)? = null,
    onOpenTrackpad: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val status = streamUiState.streamState.status
    val telemetry = streamUiState.telemetry
    val statusColor = when (status) {
        StreamConnectionStatus.Connected -> EmeraldAccent
        StreamConnectionStatus.Connecting, StreamConnectionStatus.Reconnecting,
        StreamConnectionStatus.Pairing -> AmberAccent
        StreamConnectionStatus.NotPaired -> MaterialTheme.colorScheme.outline
        StreamConnectionStatus.DataExpired, StreamConnectionStatus.Error -> RoseError
        StreamConnectionStatus.Disconnected -> MaterialTheme.colorScheme.outline
    }
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "HELIX",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp),
                    color = CyanPrimary,
                )
                if (displayBackend != WorkspaceDisplayBackend.Standard2D) {
                    DisplayBackendPill(backend = displayBackend)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Text(status.name, style = MaterialTheme.typography.labelMedium, color = statusColor, maxLines = 1)
                }
                if (status == StreamConnectionStatus.Connected && telemetry.hasData) {
                    StatText(String.format("%.0f fps", telemetry.fps))
                    StatText(String.format("%.1f Mbps", telemetry.bitrateKbps / 1000.0))
                    StatText("${telemetry.rttMs} ms")
                }
            }

            LocationChip(location)
            trailing()

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CyanPrimary)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Refresh homelab") }, onClick = { menuOpen = false; onRefresh() })
                    onOpenSession?.let { open ->
                        DropdownMenuItem(text = { Text("Session") }, onClick = { menuOpen = false; open() })
                    }
                    onOpenTrackpad?.let { open ->
                        DropdownMenuItem(text = { Text("Trackpad deck") }, onClick = { menuOpen = false; open() })
                    }
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { menuOpen = false; onSignOut() })
                }
            }
        }
    }
    errorMessage?.let { ErrorBanner(message = it, onDismiss = onDismissError) }
    }
}

/** Dismissible error line shown under the status strip (sign-in, WOL, snippet and homelab errors). */
@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = RoseError.copy(alpha = 0.18f), modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = RoseError,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss error", tint = RoseError, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Session management as a full-size dialog, so opening it never tears down the stream SurfaceView or the
 * terminal underneath.
 */
@Composable
fun SessionDialog(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    onClose: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            SessionPanel(dashboardViewModel, dashboardUiState, onClose)
        }
    }
}

/** Session management (silent refresh, Authentik config, sign out) reachable while signed in. */
@Composable
fun SessionPanel(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close session")
            }
            Text("Session", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        }
        AuthScreen(
            sessionState = dashboardUiState.authState,
            isLoading = dashboardUiState.isLoading,
            errorMessage = dashboardUiState.errorMessage,
            onSignInPasskey = { dashboardViewModel.signInWithPasskey(it) },
            onSilentRefresh = { dashboardViewModel.silentRefresh() },
            onSignOut = { dashboardViewModel.signOut() },
            onUpdateConfig = { dashboardViewModel.updateAuthentikConfig(it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun LocationChip(location: NetworkLocation) {
    val color = when (location) {
        NetworkLocation.LAN -> EmeraldAccent
        NetworkLocation.WAN -> AmberAccent
        NetworkLocation.CHECKING -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Text(
            text = location.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun PanelTabRow(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<Int, @Composable () -> Unit> = emptyMap(),
) {
    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = CyanPrimary,
        modifier = modifier,
    ) {
        titles.forEachIndexed { index, title ->
            val badge = badges[index]
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                text = {
                    if (badge == null) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            badge()
                        }
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Center panel: Moonlight stream (edge-to-edge while actively streaming)
// ---------------------------------------------------------------------------------------------

@Composable
fun CenterPanel(
    streamViewModel: StreamViewModel,
    streamUiState: StreamUiState,
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    val isActivelyStreaming = streamUiState.streamState.status == StreamConnectionStatus.Connected &&
        streamUiState.streamState.activeTransport != StreamTransportMode.EXTERNAL_NATIVE

    Column(modifier = modifier.fillMaxSize()) {
        if (!isActivelyStreaming) {
            HostPicker(
                dashboardViewModel = dashboardViewModel,
                dashboardUiState = dashboardUiState,
                selectedId = streamUiState.selectedHost?.id,
                onSelect = { streamViewModel.selectHost(it) },
                compact = true,
            )
        }
        // Unconditional second slot keeps StreamScreen and its SurfaceView mounted across connects.
        StreamScreen(
            viewModel = streamViewModel,
            uiState = streamUiState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Left wing: terminal with host + snippet quick bar
// ---------------------------------------------------------------------------------------------

@Composable
fun TerminalPanel(
    terminalViewModel: TerminalViewModel,
    terminalUiState: TerminalUiState,
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    nav: PanelNavState,
    onNavChange: (PanelNavState) -> Unit,
    modifier: Modifier = Modifier,
    showSnippets: Boolean = true,
) {
    // Snippets run on the ACTIVE terminal tab's host, not the dashboard selection.
    val targetHostId = PanelNavState.snippetTargetHostId(
        activeTabHostId = terminalUiState.activeTab?.target?.hostId,
        selectedHostId = dashboardUiState.selectedHost?.id,
    )
    Column(modifier = modifier.fillMaxSize()) {
        PanelTabRow(
            titles = LeftTab.entries.map { it.title },
            selectedIndex = nav.leftTab.ordinal,
            onSelect = { onNavChange(nav.copy(leftTab = LeftTab.entries[it])) },
        )
        if (nav.leftTab == LeftTab.SNIPPETS) {
            SnippetsScreen(
                snippets = dashboardUiState.snippets,
                hosts = dashboardUiState.hosts,
                selectedHostId = targetHostId,
                lastOutput = dashboardUiState.lastSnippetOutput,
                isLoading = dashboardUiState.isLoading,
                onSelectHost = { dashboardViewModel.selectHost(it) },
                onExecuteSnippet = { snippetId, hostId -> dashboardViewModel.executeSnippet(snippetId, hostId) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            TerminalQuickBar(
                hosts = dashboardUiState.hosts,
                snippets = if (showSnippets) dashboardUiState.snippets else emptyList(),
                onOpenSsh = { terminalViewModel.openHostSshTab(it) },
                onRunSnippet = { snippetId ->
                    targetHostId?.let { dashboardViewModel.executeSnippet(snippetId, it) }
                },
                lastResult = dashboardUiState.lastSnippetOutput,
                onDismissResult = { dashboardViewModel.clearSnippetOutput() },
                onWake = { dashboardViewModel.wakeHost(it) },
            )
            TerminalScreen(
                viewModel = terminalViewModel,
                uiState = terminalUiState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TerminalQuickBar(
    hosts: List<TermixHost>,
    snippets: List<com.nightcinder.helix.core.model.TermixSnippet>,
    onOpenSsh: (TermixHost) -> Unit,
    onRunSnippet: (Long) -> Unit,
    lastResult: com.nightcinder.helix.core.model.SnippetExecutionResult?,
    onDismissResult: () -> Unit,
    onWake: (TermixHost) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(hosts, key = { "h${it.id}" }) { host ->
                    AssistChip(
                        onClick = { onOpenSsh(host) },
                        label = { Text(host.displayName, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = "SSH to ${host.displayName}", modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            IconButton(onClick = { onWake(host) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.FlashOn, contentDescription = "Wake ${host.displayName}", tint = AmberAccent, modifier = Modifier.size(16.dp))
                            }
                        },
                    )
                }
                items(snippets, key = { "s${it.id}" }) { snippet ->
                    AssistChip(
                        onClick = { onRunSnippet(snippet.id) },
                        label = { Text(snippet.name, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = "Run ${snippet.name}", modifier = Modifier.size(16.dp), tint = CyanPrimary) },
                    )
                }
            }
            lastResult?.let { result ->
                val color = if (result.success) EmeraldAccent else RoseError
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (result.success) "snippet ok" else "snippet failed",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = color,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismissResult, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss snippet output", modifier = Modifier.size(14.dp))
                        }
                    }
                    // Full output, scrollable (previously only the first line was shown).
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                    ) {
                        Text(
                            text = result.combinedOutput.ifBlank { "(no output)" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Right wing: Workspace | Alerts | Metrics
// ---------------------------------------------------------------------------------------------

@Composable
fun RightWingPanel(
    nav: PanelNavState,
    onNavChange: (PanelNavState) -> Unit,
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    workspaceViewModel: WorkspaceViewModel,
    workspaceUiState: WorkspaceUiState,
    onOpenDockerConsole: (Long, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    monitoringViewModel: MonitoringViewModel? = null,
    monitoringUiState: MonitoringUiState = MonitoringUiState(),
    onOpenHostTerminal: (TermixHost) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        PanelTabRow(
            titles = RightTab.entries.map { it.title },
            selectedIndex = nav.rightTab.ordinal,
            onSelect = { onNavChange(nav.copy(rightTab = RightTab.entries[it])) },
            badges = mapOf(
                RightTab.ALERTS.ordinal to {
                    AlertCountBadge(
                        count = monitoringUiState.activeAlertCount,
                        hasCritical = monitoringUiState.hasCriticalAlerts,
                    )
                },
            ),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (nav.rightTab) {
                RightTab.WORKSPACE -> WorkspaceTab(
                    dashboardViewModel = dashboardViewModel,
                    dashboardUiState = dashboardUiState,
                    workspaceViewModel = workspaceViewModel,
                    workspaceUiState = workspaceUiState,
                    onOpenDockerConsole = onOpenDockerConsole,
                )
                RightTab.ALERTS -> AlertsTab(
                    dashboardViewModel = dashboardViewModel,
                    dashboardUiState = dashboardUiState,
                    monitoringViewModel = monitoringViewModel,
                    monitoringUiState = monitoringUiState,
                    onOpenHostTerminal = onOpenHostTerminal,
                )
                RightTab.METRICS -> MetricsTab(
                    dashboardViewModel = dashboardViewModel,
                    dashboardUiState = dashboardUiState,
                    monitoringViewModel = monitoringViewModel,
                    monitoringUiState = monitoringUiState,
                )
            }
        }
    }
}

/**
 * Resolves displayed host status: prefers non-UNKNOWN [HealthSummary] status, falling back to [TermixHost.status]
 * (which carries Prometheus `up` / WOL / TCP probe resolution so hosts without a Termix status never stay grey).
 */
internal fun resolveDisplayedHostStatus(
    host: TermixHost,
    healthSummary: HealthSummary?,
): HostStatus {
    val healthStatus = healthSummary?.hosts?.firstOrNull { it.hostId == host.id }?.resolvedStatus
    return healthStatus?.takeIf { it != HostStatus.UNKNOWN } ?: host.status
}

/**
 * Shared host chip row: status dot, filter/search, CPU/RAM mini bars (unless [compact]) and a Wake-on-LAN
 * button on every chip. Also shows the WOL result banner. Used wherever hosts are listed.
 */
@Composable
fun HostPicker(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    selectedId: Long?,
    onSelect: (TermixHost) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    val hosts = remember(dashboardUiState.hosts, query) { filterHosts(dashboardUiState.hosts, query) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter hosts") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = "Filter hosts", modifier = Modifier.size(18.dp))
            }
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 8.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(hosts, key = { it.id }) { host ->
                    val status = resolveDisplayedHostStatus(host, dashboardUiState.healthSummary)
                    val dotColor = when (status) {
                        HostStatus.ONLINE -> EmeraldAccent
                        HostStatus.DEGRADED, HostStatus.SLEEPING -> AmberAccent
                        HostStatus.OFFLINE -> RoseError
                        HostStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
                    }
                    val metrics = dashboardUiState.liveMetrics[host.id]
                    FilterChip(
                        selected = host.id == selectedId,
                        onClick = { onSelect(host) },
                        leadingIcon = { Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor)) },
                        label = {
                            Column {
                                Text(host.displayName, maxLines = 1)
                                if (!compact && metrics != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        MiniBar(metrics.cpuPercent, CyanPrimary)
                                        MiniBar(metrics.memoryPercent, EmeraldAccent)
                                    }
                                }
                            }
                        },
                        trailingIcon = {
                            IconButton(onClick = { dashboardViewModel.wakeHost(host) }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Default.FlashOn,
                                    contentDescription = "Wake ${host.displayName}",
                                    tint = AmberAccent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
        dashboardUiState.wolStatus?.let {
            WolStatusBanner(status = it, onDismiss = { dashboardViewModel.clearWolStatus() })
        }
    }
}

/** Case-insensitive host filter over name, address, hostname and tags (same fields as main's host list). */
internal fun filterHosts(hosts: List<TermixHost>, query: String): List<TermixHost> =
    if (query.isBlank()) hosts else hosts.filter { host ->
        host.name.contains(query, ignoreCase = true) ||
            host.ip.contains(query, ignoreCase = true) ||
            host.hostname?.contains(query, ignoreCase = true) == true ||
            host.tags.any { it.contains(query, ignoreCase = true) }
    }

@Composable
private fun MiniBar(percent: Double?, color: androidx.compose.ui.graphics.Color) {
    val fraction = ((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    androidx.compose.material3.LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
        color = if (fraction > 0.85f) RoseError else color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/** Compact host detail (address, OS, status, profile notes) for the selected host. */
@Composable
private fun HostInfoCard(host: TermixHost, dashboardUiState: DashboardUiState) {
    val status = resolveDisplayedHostStatus(host, dashboardUiState.healthSummary)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(host.displayName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = CyanPrimary)
            Text(
                "${host.displayAddress}:${host.port} - ${host.os ?: host.distro ?: "Linux"} - ${status.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            host.effectiveHomelabProfile?.let { profile ->
                Text(profile.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorkspaceTab(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    workspaceViewModel: WorkspaceViewModel,
    workspaceUiState: WorkspaceUiState,
    onOpenDockerConsole: (Long, String, String, String) -> Unit,
) {
    val hosts = dashboardUiState.hosts
    // ONE selected host (the dashboard's) drives Workspace, Metrics, snippets fallback and the tree.
    val selectedId = dashboardUiState.selectedHost?.id
    LaunchedEffect(selectedId, workspaceUiState.selectedHostId) {
        PanelNavState.workspaceHostToSync(selectedId, workspaceUiState.selectedHostId)?.let {
            workspaceViewModel.selectHost(it)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HostPicker(
            dashboardViewModel = dashboardViewModel,
            dashboardUiState = dashboardUiState,
            selectedId = selectedId,
            onSelect = { dashboardViewModel.selectHost(it.id) },
        )
        WorkspaceTreeScreen(
            viewModel = workspaceViewModel,
            uiState = workspaceUiState,
            hosts = hosts,
            onOpenDockerConsole = onOpenDockerConsole,
            showHostPicker = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricsTab(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    monitoringViewModel: MonitoringViewModel? = null,
    monitoringUiState: MonitoringUiState = MonitoringUiState(),
) {
    val host = dashboardUiState.selectedHost
    val metrics = dashboardUiState.selectedHostMetrics
    val promMetrics = host?.let { monitoringUiState.metricsForHost(it) }
    val currentHosts by rememberUpdatedState(dashboardUiState.hosts)
    val currentWol by rememberUpdatedState(dashboardUiState.wolDevices)

    Column(modifier = Modifier.fillMaxSize()) {
        HostPicker(
            dashboardViewModel = dashboardViewModel,
            dashboardUiState = dashboardUiState,
            selectedId = host?.id,
            onSelect = { dashboardViewModel.selectHost(it.id) },
        )
        if (host == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hosts available", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        LaunchedEffect(host.id) {
            var tick = 0
            while (true) {
                dashboardViewModel.refreshMetricsForHost(host.id)
                if (tick % 3 == 0 && currentHosts.isNotEmpty()) {
                    monitoringViewModel?.refresh(currentHosts, currentWol)
                }
                tick++
                delay(10_000L)
            }
        }

        val cpuPct = metrics?.cpuPercent ?: promMetrics?.cpuPercent
        val memPct = metrics?.memoryPercent ?: promMetrics?.memoryPercent
        val memUsed = metrics?.memoryUsedBytes ?: promMetrics?.memoryUsedBytes
        val diskPct = metrics?.diskPercent ?: promMetrics?.diskPercent
        val diskUsed = metrics?.diskUsedBytes ?: promMetrics?.diskUsedBytes
        val netRx = metrics?.networkRxBytesPerSec ?: promMetrics?.networkRxBytesPerSec
        val netTx = metrics?.networkTxBytesPerSec ?: promMetrics?.networkTxBytesPerSec

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HostInfoCard(host = host, dashboardUiState = dashboardUiState)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricGaugeCard(
                    title = "CPU",
                    value = cpuPct?.let { "${it.toInt()}%" } ?: "N/A",
                    subtitle = metrics?.cpuCores?.let { "$it cores" } ?: "Load",
                    progress = (cpuPct ?: 0.0) / 100.0,
                    accentColor = CyanPrimary,
                    modifier = Modifier.weight(1f),
                )
                MetricGaugeCard(
                    title = "Memory",
                    value = memPct?.let { "${it.toInt()}%" } ?: "N/A",
                    subtitle = memUsed?.let { "${it / (1024L * 1024 * 1024)} GB" } ?: "RAM",
                    progress = (memPct ?: 0.0) / 100.0,
                    accentColor = EmeraldAccent,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricGaugeCard(
                    title = "Disk",
                    value = diskPct?.let { "${it.toInt()}%" } ?: "N/A",
                    subtitle = diskUsed?.let { "${it / (1024L * 1024 * 1024)} GB" } ?: "Storage",
                    progress = (diskPct ?: 0.0) / 100.0,
                    accentColor = AmberAccent,
                    modifier = Modifier.weight(1f),
                )
                MetricGaugeCard(
                    title = "Network",
                    value = netRx?.let { "${it / 1024} KB/s" } ?: "0 KB/s",
                    subtitle = netTx?.let { "Tx: ${it / 1024} KB/s" } ?: "Rx / Tx",
                    progress = 0.5,
                    accentColor = CyanPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
            PrometheusHostMetricsSection(
                host = host,
                metrics = promMetrics,
                mergedLiveMetrics = metrics,
            )
            if (metrics == null && promMetrics == null) {
                Text(
                    "Waiting for live metrics from ${host.displayName}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!metrics?.topProcesses.isNullOrEmpty()) {
                Text("Top processes", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        metrics?.topProcesses?.take(5)?.forEach { proc ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = proc.name.ifBlank { proc.command ?: "process" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "CPU ${proc.cpuPercent?.toInt() ?: 0}% / RAM ${proc.memoryPercent?.toInt() ?: 0}%",
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
private fun AlertsTab(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    monitoringViewModel: MonitoringViewModel?,
    monitoringUiState: MonitoringUiState,
    onOpenHostTerminal: (TermixHost) -> Unit,
) {
    AlertsFeedPanel(
        uiState = monitoringUiState,
        hosts = dashboardUiState.hosts,
        onSelectFilter = { source -> monitoringViewModel?.selectSourceFilter(source) },
        onRefresh = {
            if (monitoringViewModel != null && dashboardUiState.hosts.isNotEmpty()) {
                monitoringViewModel.refresh(dashboardUiState.hosts, dashboardUiState.wolDevices)
            } else {
                dashboardViewModel.refreshHomelabData()
            }
        },
        onOpenHostTerminal = onOpenHostTerminal,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Unused-height helper kept private to avoid import churn in callers. */
@Composable
internal fun PanelDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
}
