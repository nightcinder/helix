package com.nightcinder.helix.ui.tree

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.layout.width
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.LiveHostMetrics
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.ui.auth.AuthScreen
import com.nightcinder.helix.ui.monitoring.AlertCountBadge
import com.nightcinder.helix.ui.monitoring.AlertsFeedPanel
import com.nightcinder.helix.ui.monitoring.PrometheusHostMetricsSection
import com.nightcinder.helix.ui.snippets.SnippetsScreen
import com.nightcinder.helix.ui.state.DashboardUiState
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.MonitoringUiState
import com.nightcinder.helix.ui.state.MonitoringViewModel
import com.nightcinder.helix.ui.state.StreamUiState
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalUiState
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.TreeUiState
import com.nightcinder.helix.ui.state.TreeViewModel
import com.nightcinder.helix.ui.stream.StreamScreen
import com.nightcinder.helix.ui.terminal.TerminalScreen
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError
import com.nightcinder.helix.ui.workspace.SftpFileManagerPanel
import kotlinx.coroutines.delay

/** Everything the tab contents need; one object so each layout wires it once. */
class TabEnv(
    val tree: TreeViewModel,
    val treeState: TreeUiState,
    val terminal: TerminalViewModel,
    val terminalState: TerminalUiState,
    val stream: StreamViewModel,
    val streamState: StreamUiState,
    val dashboard: DashboardViewModel,
    val dashboardState: DashboardUiState,
    val onCloseTab: (ContentTab) -> Unit,
    /** Non-null only in XR Full Space, where the stream can live in its own panel. */
    val onToggleStreamPopOut: (() -> Unit)? = null,
    val monitoring: MonitoringViewModel? = null,
    val monitoringState: MonitoringUiState = MonitoringUiState(),
    val onOpenHostTerminal: (TermixHost) -> Unit = {},
    val suppressDialogsInFullSpace: Boolean = false,
)

enum class TabPivotAction(val label: String) {
    TERMINAL("Terminal"),
    FILES("Files"),
    METRICS("Metrics"),
    STREAM("Stream"),
    NEW_SSH("+ SSH"),
    LOGS("Logs"),
    DOCKER_CONSOLE("Console"),
}

fun TabKind.icon(): ImageVector = when (this) {
    TabKind.TERMINAL -> Icons.Default.Terminal
    TabKind.FILES -> Icons.Default.Folder
    TabKind.LOGS -> Icons.Default.Description
    TabKind.STREAM -> Icons.Default.Tv
    TabKind.METRICS -> Icons.Default.Speed
    TabKind.ALERTS -> Icons.Default.Notifications
    TabKind.SESSION -> Icons.Default.Security
    TabKind.SNIPPETS -> Icons.Default.Code
}

/**
 * Compact (single-pane) tab switcher shown under the tree: every open tab is one chip, tap selects it
 * and [onOpen] navigates to the detail pane.
 */
@Composable
fun OpenTabsStrip(
    tabs: TabsState,
    onSelect: (ContentTab) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    alertCount: Int = 0,
    hasCriticalAlerts: Boolean = false,
) {
    if (tabs.tabs.isEmpty()) return
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Text(
            "Open tabs (${tabs.tabs.size})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.tabs.forEach { tab ->
                val selected = tab.id == tabs.activeId
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        onSelect(tab)
                        onOpen()
                    },
                    color = if (selected) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    border = if (selected) BorderStroke(1.dp, CyanPrimary) else null,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(tab.kind.icon(), null, Modifier.size(14.dp), tint = if (selected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tab.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        if (tab.kind == TabKind.ALERTS) {
                            AlertCountBadge(count = alertCount, hasCritical = hasCriticalAlerts)
                        }
                    }
                }
            }
        }
    }
}

/** Tab bar + content for the active tab. [onBack] is shown only in single-pane (compact) layouts. */
@Composable
fun TabbedContent(
    tabs: TabsState,
    onSelect: (ContentTab) -> Unit,
    onClose: (ContentTab) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    alertCount: Int = 0,
    hasCriticalAlerts: Boolean = false,
    onPivot: ((ContentTab, TabPivotAction) -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    content: @Composable (ContentTab) -> Unit,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to tree")
                }
            }
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.tabs.forEach { tab ->
                    ContentTabChip(
                        tab = tab,
                        selected = tab.id == tabs.activeId,
                        alertCount = if (tab.kind == TabKind.ALERTS) alertCount else 0,
                        hasCriticalAlerts = hasCriticalAlerts,
                        onSelect = { onSelect(tab) },
                        onClose = { onClose(tab) },
                    )
                }
            }
        }
        val active = tabs.active
        if (active != null && active.hostId != null && onPivot != null) {
            val pivotActions = if (active.containerId != null) {
                listOf(TabPivotAction.LOGS, TabPivotAction.DOCKER_CONSOLE)
            } else {
                listOf(
                    TabPivotAction.TERMINAL,
                    TabPivotAction.FILES,
                    TabPivotAction.METRICS,
                    TabPivotAction.STREAM,
                    TabPivotAction.NEW_SSH,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pivotActions.forEach { action ->
                    val isCurrent = (action == TabPivotAction.TERMINAL && active.kind == TabKind.TERMINAL) ||
                        (action == TabPivotAction.FILES && active.kind == TabKind.FILES) ||
                        (action == TabPivotAction.METRICS && active.kind == TabKind.METRICS) ||
                        (action == TabPivotAction.STREAM && active.kind == TabKind.STREAM) ||
                        (action == TabPivotAction.LOGS && active.kind == TabKind.LOGS)
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onPivot(active, action) },
                        color = if (isCurrent) CyanPrimary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        border = if (isCurrent) BorderStroke(1.dp, CyanPrimary) else null,
                    ) {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = if (isCurrent) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (active == null) {
                if (emptyContent != null) {
                    emptyContent()
                } else {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Pick a node in the tree. Use its menu to open a terminal, files, logs or metrics.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                content(active)
            }
        }
    }
}

@Composable
private fun ContentTabChip(
    tab: ContentTab,
    selected: Boolean,
    alertCount: Int,
    hasCriticalAlerts: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSelect),
        color = if (selected) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = if (selected) BorderStroke(1.dp, CyanPrimary) else null,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                tab.kind.icon(),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (selected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                tab.title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                color = if (selected) CyanPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (alertCount > 0) {
                AlertCountBadge(count = alertCount, hasCritical = hasCriticalAlerts)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close ${tab.title}", modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun TabContent(tab: ContentTab, env: TabEnv) {
    when (tab.kind) {
        TabKind.TERMINAL -> TerminalTabContent(tab, env)
        TabKind.FILES -> FilesTabContent(tab, env)
        TabKind.LOGS -> LogsTabContent(tab, env)
        TabKind.STREAM -> StreamTabContent(env)
        TabKind.METRICS -> MetricsTabContent(tab, env)
        TabKind.ALERTS -> AlertsTabContent(env)
        TabKind.SNIPPETS -> SnippetsScreen(
            snippets = env.dashboardState.snippets,
            hosts = env.dashboardState.hosts,
            selectedHostId = env.dashboardState.selectedHostId,
            lastOutput = env.dashboardState.lastSnippetOutput,
            isLoading = env.dashboardState.isLoading,
            onSelectHost = { env.dashboard.selectHost(it) },
            onExecuteSnippet = { snippetId, hostId -> env.dashboard.executeSnippet(snippetId, hostId) },
        )
        TabKind.SESSION -> AuthScreen(
            sessionState = env.dashboardState.authState,
            isLoading = env.dashboardState.isLoading,
            errorMessage = env.dashboardState.errorMessage,
            onSignInPasskey = { env.dashboard.signInWithPasskey(it) },
            onSilentRefresh = { env.dashboard.silentRefresh() },
            onSignOut = { env.dashboard.signOut() },
            onUpdateConfig = { env.dashboard.updateAuthentikConfig(it) },
        )
    }
}

@Composable
private fun TerminalTabContent(tab: ContentTab, env: TabEnv) {
    LaunchedEffect(tab.terminalTabId) {
        tab.terminalTabId?.let { env.terminal.selectTab(it) }
    }
    TerminalScreen(viewModel = env.terminal, uiState = env.terminalState, showTabBar = false)
}

@Composable
private fun StreamTabContent(env: TabEnv) {
    val popOut = env.onToggleStreamPopOut
    val s = env.streamState.streamState
    val isActivelyStreaming = (s.status == StreamConnectionStatus.Connected ||
        s.status == StreamConnectionStatus.Connecting ||
        s.status == StreamConnectionStatus.Pairing) &&
        s.activeTransport != StreamTransportMode.EXTERNAL_NATIVE
    if (popOut != null && env.treeState.streamPoppedOut) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Stream is open in its own panel.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = popOut,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
            ) { Text("Dock back into tab") }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            StreamScreen(
                viewModel = env.stream,
                uiState = env.streamState,
                modifier = Modifier.fillMaxSize(),
            )
            if (popOut != null && !isActivelyStreaming) {
                IconButton(
                    onClick = popOut,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(36.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Pop out stream", tint = CyanPrimary)
                }
            }
        }
    }
}

@Composable
private fun FilesTabContent(tab: ContentTab, env: TabEnv) {
    val hostId = tab.hostId ?: return
    val path = env.treeState.sftpPathByHost[hostId] ?: "/"
    val entries = env.treeState.sftpEntriesByHost[hostId].orEmpty()
    LaunchedEffect(tab.id) { env.tree.browse(hostId, path) }

    Box(Modifier.fillMaxSize().padding(12.dp)) {
        SftpFileManagerPanel(
            currentPath = path,
            entries = entries,
            onBrowse = { env.tree.browse(hostId, it) },
            onOpenFile = { env.tree.openFile(hostId, it) },
        )
    }

    if (!env.suppressDialogsInFullSpace) {
        env.treeState.fileEdit?.takeIf { it.hostId == hostId }?.let { edit ->
        var text by remember(edit.path, edit.content) { mutableStateOf(edit.content) }
        AlertDialog(
            onDismissRequest = { env.tree.dismissFile() },
            title = { Text("Edit: ${edit.name}", color = CyanPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(edit.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { env.tree.saveFile(edit, text) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { env.tree.dismissFile() }) { Text("Cancel") } },
        )
        }
    }
}

@Composable
private fun LogsTabContent(tab: ContentTab, env: TabEnv) {
    val hostId = tab.hostId ?: return
    val containerId = tab.containerId ?: return
    val logs = env.treeState.logsByTab[tab.id]
    LaunchedEffect(tab.id) { env.tree.refreshLogs(tab.id, hostId, containerId) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tab.title, style = MaterialTheme.typography.titleSmall, color = CyanPrimary)
            if (logs?.isLoading == true) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CyanPrimary)
            } else {
                IconButton(onClick = { env.tree.refreshLogs(tab.id, hostId, containerId) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh logs", modifier = Modifier.size(18.dp))
                }
            }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(logs?.text) { scroll.scrollTo(scroll.maxValue) }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF030507))
                .padding(10.dp)
                .verticalScroll(scroll),
        ) {
            val body = when {
                logs == null -> "Loading…"
                logs.error != null -> "Error: ${logs.error}"
                logs.text.isBlank() -> if (logs.isLoading) "Loading…" else "No logs available"
                else -> logs.text
            }
            Text(
                body,
                color = if (logs?.error != null) RoseError else Color(0xFFE2E8F0),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun MetricsTabContent(tab: ContentTab, env: TabEnv) {
    val hostId = tab.hostId ?: return
    val currentEnv by rememberUpdatedState(env)
    // Poll while the tab is on screen; the repositories own the actual fetch.
    LaunchedEffect(hostId) {
        var tick = 0
        while (true) {
            currentEnv.dashboard.refreshMetricsForHost(hostId)
            if (tick % 3 == 0 && currentEnv.dashboardState.hosts.isNotEmpty()) {
                currentEnv.monitoring?.refresh(
                    currentEnv.dashboardState.hosts,
                    currentEnv.dashboardState.wolDevices,
                )
            }
            tick++
            delay(10_000)
        }
    }
    val host = env.dashboardState.hosts.firstOrNull { it.id == hostId }
    val metrics: LiveHostMetrics? = env.dashboardState.liveMetrics[hostId]
    val promMetrics = host?.let { env.monitoringState.metricsForHost(it) }
    val cpuPercent = metrics?.cpuPercent ?: promMetrics?.cpuPercent
    val memoryPercent = metrics?.memoryPercent ?: promMetrics?.memoryPercent
    val memoryUsedBytes = metrics?.memoryUsedBytes ?: promMetrics?.memoryUsedBytes
    val diskPercent = metrics?.diskPercent ?: promMetrics?.diskPercent
    val diskUsedBytes = metrics?.diskUsedBytes ?: promMetrics?.diskUsedBytes
    val netRxBytesPerSec = metrics?.networkRxBytesPerSec ?: promMetrics?.networkRxBytesPerSec
    val netTxBytesPerSec = metrics?.networkTxBytesPerSec ?: promMetrics?.networkTxBytesPerSec

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        host?.let {
            val resolvedStatus = resolveHostStatus(
                host = it,
                health = env.dashboardState.healthSummary,
                hostUpByKey = env.monitoringState.snapshot.hostUpByKey,
            )
            HostInfoCard(it, status = resolvedStatus)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricGauge(
                "CPU",
                cpuPercent?.let { "${it.toInt()}%" } ?: "N/A",
                metrics?.cpuCores?.let { "$it cores" } ?: "Load",
                (cpuPercent ?: 0.0) / 100.0,
                CyanPrimary,
                Modifier.weight(1f),
            )
            MetricGauge(
                "Memory",
                memoryPercent?.let { "${it.toInt()}%" } ?: "N/A",
                memoryUsedBytes?.let { "${it / (1024 * 1024 * 1024)} GB" } ?: "RAM",
                (memoryPercent ?: 0.0) / 100.0,
                EmeraldAccent,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricGauge(
                "Disk",
                diskPercent?.let { "${it.toInt()}%" } ?: "N/A",
                diskUsedBytes?.let { "${it / (1024 * 1024 * 1024)} GB" } ?: "Storage",
                (diskPercent ?: 0.0) / 100.0,
                AmberAccent,
                Modifier.weight(1f),
            )
            MetricGauge(
                "Network",
                netRxBytesPerSec?.let { "${it / 1024} KB/s" } ?: "0 KB/s",
                netTxBytesPerSec?.let { "Tx: ${it / 1024} KB/s" } ?: "Rx / Tx",
                0.5,
                CyanPrimary,
                Modifier.weight(1f),
            )
        }
        if (host != null) {
            PrometheusHostMetricsSection(
                host = host,
                metrics = promMetrics,
                mergedLiveMetrics = metrics,
            )
        }
        if (!metrics?.topProcesses.isNullOrEmpty()) {
            Text("Top processes", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    metrics?.topProcesses?.take(5)?.forEach { proc ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                proc.name.ifBlank { proc.command ?: "process" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "CPU ${proc.cpuPercent?.toInt() ?: 0}% · RAM ${proc.memoryPercent?.toInt() ?: 0}%",
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

/** Host details from the Termix host record: status, address, port, user, OS, tags and notes. */
@Composable
private fun HostInfoCard(host: TermixHost, status: HostStatus = host.status) {
    val profile = host.effectiveHomelabProfile
    val statusColor = when (status) {
        HostStatus.ONLINE -> EmeraldAccent
        HostStatus.OFFLINE -> RoseError
        HostStatus.DEGRADED, HostStatus.SLEEPING -> AmberAccent
        HostStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val rows = listOfNotNull(
        "Status" to status.name.lowercase(),
        "Address" to "${host.displayAddress}:${host.port}",
        host.username?.takeIf { it.isNotBlank() }?.let { "User" to it },
        host.authType?.takeIf { it.isNotBlank() }?.let { "Auth" to it },
        (host.os ?: host.distro)?.takeIf { it.isNotBlank() }?.let { "OS" to it },
        host.folder?.takeIf { it.isNotBlank() }?.let { "Folder" to it },
        host.tags.takeIf { it.isNotEmpty() }?.let { "Tags" to it.joinToString(", ") },
        host.macAddress?.takeIf { it.isNotBlank() }?.let { "MAC" to it },
        profile?.let { "Role" to it.displayRole },
        (host.notes?.takeIf { it.isNotBlank() } ?: profile?.notes?.takeIf { it.isNotBlank() })?.let { "Notes" to it },
    )
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(host.displayName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = CyanPrimary)
            rows.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (label == "Status") FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (label == "Status") statusColor else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricGauge(
    title: String,
    value: String,
    subtitle: String,
    progress: Double,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = accent)
            LinearProgressIndicator(
                progress = { progress.coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AlertsTabContent(env: TabEnv) {
    AlertsFeedPanel(
        uiState = env.monitoringState,
        hosts = env.dashboardState.hosts,
        onSelectFilter = { env.monitoring?.selectSourceFilter(it) },
        onRefresh = {
            val mon = env.monitoring
            if (mon != null && env.dashboardState.hosts.isNotEmpty()) {
                mon.refresh(env.dashboardState.hosts, env.dashboardState.wolDevices)
            } else {
                env.dashboard.refreshHomelabData()
            }
        },
        onOpenHostTerminal = { host -> env.onOpenHostTerminal(host) },
    )
}
