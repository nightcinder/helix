package com.nightcinder.helix.ui.tree

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CropLandscape
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterDefaults
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.compose.subspace.layout.width
import com.nightcinder.helix.DetachedPanelActivity
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.SnippetExecutionResult
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.WolWakeResult
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import com.nightcinder.helix.ui.auth.AuthScreen
import com.nightcinder.helix.ui.monitoring.AlertCountBadge
import com.nightcinder.helix.ui.monitoring.AlertsFeedPanel
import com.nightcinder.helix.ui.resolveHostStatus
import com.nightcinder.helix.ui.spatial.PixelTrackpadDeck
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
import com.nightcinder.helix.ui.state.TreeUiState
import com.nightcinder.helix.ui.state.TreeViewModel
import com.nightcinder.helix.ui.stream.StreamScreen
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError
import kotlinx.coroutines.launch

private const val TREE_WIDTH_DP = 320
private const val FULL_SPACE_DEFAULT_CENTER_WIDTH_DP = 1280
private const val FULL_SPACE_CENTER_HEIGHT_DP = 780
private const val FULL_SPACE_TREE_WIDTH_DP = 420
private val FULL_SPACE_SURFACE_COLOR = Color(0xFF0A0F14)

/** Everything the tree + tab panes need, built once so every host window (main, detached) wires it the same way. */
class TreeWiring(
    val controller: TreeController,
    val env: TabEnv,
    val treePane: @Composable (Modifier, () -> Unit) -> Unit,
    val tabsPane: @Composable (Modifier, (() -> Unit)?) -> Unit,
)

/**
 * Builds the tree/tabs wiring and keeps the tree tabs in sync with the terminal ViewModel's tab list
 * (terminals opened by the trackpad deck or a docker console appear as tabs).
 */
@Composable
fun rememberTreeWiring(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    terminalViewModel: TerminalViewModel,
    terminalUiState: TerminalUiState,
    streamViewModel: StreamViewModel,
    streamUiState: StreamUiState,
    treeViewModel: TreeViewModel,
    treeUiState: TreeUiState,
    effectiveTabs: TabsState = treeUiState.tabs,
    inFullSpace: Boolean = false,
    onToggleStreamPopOut: (() -> Unit)? = null,
    monitoringViewModel: MonitoringViewModel? = null,
    monitoringUiState: MonitoringUiState = MonitoringUiState(),
    onEnsureTabsVisible: (() -> Unit)? = null,
): TreeWiring {
    val controller = remember(treeViewModel, terminalViewModel, streamViewModel, dashboardViewModel) {
        TreeController(treeViewModel, terminalViewModel, streamViewModel, dashboardViewModel)
    }
    val terminalIds = terminalUiState.tabs.map { it.id }
    LaunchedEffect(terminalIds) { treeViewModel.syncTerminalTabs(terminalUiState.tabs) }

    val health = dashboardUiState.healthSummary
    val hostUpByKey = monitoringUiState.snapshot.hostUpByKey
    val rows = remember(
        dashboardUiState.hosts,
        treeUiState.stacksByHost,
        treeUiState.containersByHost,
        treeUiState.expanded,
        treeUiState.hostFilter,
        health,
        hostUpByKey,
    ) {
        flattenTree(
            hosts = dashboardUiState.hosts,
            stacksByHost = treeUiState.stacksByHost,
            containersByHost = treeUiState.containersByHost,
            expanded = treeUiState.expanded,
            hostStatus = { host ->
                resolveHostStatus(host = host, health = health, hostUpByKey = hostUpByKey)
            },
            query = treeUiState.hostFilter,
        )
    }
    val activeTab = effectiveTabs.active
    val highlight = remember(activeTab) {
        setOfNotNull(
            activeTab?.containerId?.let { cid -> activeTab.hostId?.let { containerKey(it, cid) } },
            activeTab?.takeIf { it.containerId == null }?.hostId?.let { hostKey(it) },
        )
    }
    val env = TabEnv(
        tree = treeViewModel,
        treeState = treeUiState,
        terminal = terminalViewModel,
        terminalState = terminalUiState,
        stream = streamViewModel,
        streamState = streamUiState,
        dashboard = dashboardViewModel,
        dashboardState = dashboardUiState,
        onCloseTab = controller::close,
        onToggleStreamPopOut = onToggleStreamPopOut,
        monitoring = monitoringViewModel,
        monitoringState = monitoringUiState,
        onOpenHostTerminal = { host ->
            if (controller.openTerminal(host)) {
                onEnsureTabsVisible?.invoke()
            }
        },
        suppressDialogsInFullSpace = inFullSpace,
    )
    val preferredStreamHost = dashboardUiState.hosts.firstOrNull { isStreamHost(it) }
        ?: dashboardUiState.hosts.firstOrNull()
    val treePane: @Composable (Modifier, () -> Unit) -> Unit = { mod, afterOpen ->
        TreePane(
            rows = rows,
            loadingHosts = treeUiState.loadingHosts,
            snippets = dashboardUiState.snippets,
            highlightKeys = highlight,
            onTap = { if (controller.tap(it)) afterOpen() },
            onAction = { node, action -> if (controller.perform(node, action)) afterOpen() },
            onRunSnippet = controller::runSnippet,
            modifier = mod,
            filter = treeUiState.hostFilter,
            onFilterChange = treeViewModel::setHostFilter,
            onRequestConfirm = if (inFullSpace) {
                { node, action -> treeViewModel.requestNodeConfirmation(node, action) }
            } else {
                null
            },
        )
    }
    val tabsPane: @Composable (Modifier, (() -> Unit)?) -> Unit = { mod, onBack ->
        TabbedContent(
            tabs = effectiveTabs,
            onSelect = controller::select,
            onClose = controller::close,
            onBack = onBack,
            modifier = mod,
            alertCount = monitoringUiState.activeAlertCount,
            hasCriticalAlerts = monitoringUiState.hasCriticalAlerts,
            onPivot = { tab, pivot ->
                val hostId = tab.hostId ?: return@TabbedContent
                val host = dashboardUiState.hosts.firstOrNull { it.id == hostId }
                val hostName = host?.displayName ?: tab.title.substringAfter(" · ", tab.title)
                when (pivot) {
                    TabPivotAction.TERMINAL -> {
                        val existing = effectiveTabs.tabs.firstOrNull {
                            it.kind == TabKind.TERMINAL && it.hostId == hostId && it.containerId == null
                        }
                        if (existing != null) controller.select(existing)
                        else if (host != null) controller.openTerminal(host)
                    }
                    TabPivotAction.NEW_SSH -> if (host != null) {
                        controller.openTerminal(host)
                    }
                    TabPivotAction.FILES -> controller.select(contentTab(TabKind.FILES, hostId, hostName))
                    TabPivotAction.METRICS -> controller.select(contentTab(TabKind.METRICS, hostId, hostName))
                    TabPivotAction.STREAM -> controller.select(contentTab(TabKind.STREAM, hostId, hostName))
                    TabPivotAction.LOGS -> {
                        val cid = tab.containerId ?: return@TabbedContent
                        val cname = tab.title.substringAfter(" · ", cid)
                        controller.select(contentTab(TabKind.LOGS, hostId, hostName, cid, cname))
                    }
                    TabPivotAction.DOCKER_CONSOLE -> {
                        val cid = tab.containerId ?: return@TabbedContent
                        val cname = tab.title.substringAfter(" · ", cid)
                        val termId = terminalViewModel.openDockerConsoleTab(hostId, hostName, cid, cname)
                        controller.select(
                            contentTab(
                                TabKind.TERMINAL,
                                hostId = hostId,
                                hostName = hostName,
                                containerName = cname,
                                terminalTabId = termId,
                            ),
                        )
                    }
                }
            },
            emptyContent = {
                TreeEmptyLaunchSurface(
                    preferredStreamHost = preferredStreamHost,
                    hosts = dashboardUiState.hosts,
                    onStreamHost = { host ->
                        controller.perform(TreeNode.Host(host, host.status), NodeAction.STREAM)
                        onEnsureTabsVisible?.invoke()
                    },
                    onOpenTerminal = { host ->
                        if (controller.openTerminal(host)) onEnsureTabsVisible?.invoke()
                    },
                    onOpenAlerts = {
                        if (inFullSpace) treeViewModel.setShowAlertsModal(true)
                        else controller.openGlobal(TabKind.ALERTS)
                    },
                    onOpenSnippets = { controller.openGlobal(TabKind.SNIPPETS) },
                )
            },
        ) { tab -> TabContent(tab, env) }
    }
    return TreeWiring(controller, env, treePane, tabsPane)
}

@Composable
private fun TreeEmptyLaunchSurface(
    preferredStreamHost: TermixHost?,
    hosts: List<TermixHost>,
    onStreamHost: (TermixHost) -> Unit,
    onOpenTerminal: (TermixHost) -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSnippets: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Tv, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(44.dp))
        Text(
            text = "Moonlight Desktop & Workspace",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Launch your desktop stream or jump straight into SSH, Docker, or SFTP from the tree.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (preferredStreamHost != null) {
            Button(
                onClick = { onStreamHost(preferredStreamHost) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Stream Desktop (${preferredStreamHost.displayName})", fontWeight = FontWeight.Bold)
            }
        }
        val sshHosts = hosts.filter { it.enableTerminal }.take(4)
        if (sshHosts.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sshHosts.forEach { host ->
                    OutlinedButton(onClick = { onOpenTerminal(host) }) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  SSH ${host.displayName}")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenAlerts) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  Alerts")
            }
            TextButton(onClick = onOpenSnippets) {
                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  Snippets")
            }
        }
    }
}

/**
 * Concept A root: sign-in gate, then the connection tree plus tabbed content in one of three layouts
 * (phone/tablet list-detail, XR Home Space split panel, XR Full Space spatial panels).
 */
@Composable
fun HelixTreeApp(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    terminalViewModel: TerminalViewModel,
    terminalUiState: TerminalUiState,
    streamViewModel: StreamViewModel,
    streamUiState: StreamUiState,
    spatialViewModel: SpatialViewModel,
    spatialUiState: SpatialUiState,
    treeViewModel: TreeViewModel,
    treeUiState: TreeUiState,
    onDetachWindow: (String) -> Unit,
    onRequestFullSpace: () -> Unit,
    onRequestHomeSpace: () -> Unit,
    modifier: Modifier = Modifier,
    initialPanelId: String? = null,
    monitoringViewModel: MonitoringViewModel? = null,
    monitoringUiState: MonitoringUiState = MonitoringUiState(),
    onOpenTrackpad: (() -> Unit)? = null,
) {
    // Sign-in stays the entry gate.
    if (!dashboardUiState.authState.isAuthenticated) {
        AuthScreen(
            sessionState = dashboardUiState.authState,
            isLoading = dashboardUiState.isLoading,
            errorMessage = dashboardUiState.errorMessage,
            onSignInPasskey = { dashboardViewModel.signInWithPasskey(it) },
            onSilentRefresh = { dashboardViewModel.silentRefresh() },
            onSignOut = { dashboardViewModel.signOut() },
            onUpdateConfig = { dashboardViewModel.updateAuthentikConfig(it) },
            modifier = modifier,
        )
        return
    }

    var showTrackpad by remember { mutableStateOf(false) }
    if (spatialUiState.isUrxrGlasses && showTrackpad) {
        PixelTrackpadDeck(
            streamViewModel = streamViewModel,
            terminalViewModel = terminalViewModel,
            dashboardViewModel = dashboardViewModel,
            spatialViewModel = spatialViewModel,
            dashboardUiState = dashboardUiState,
        )
        IconButtonFloatingExit { showTrackpad = false }
        return
    }

    val inFullSpace = runCatching { LocalSpatialCapabilities.current.isSpatialUiEnabled }.getOrDefault(false)
    // Synchronous frame-1 active-stream auto-focus (PANELS -> TREE) so SurfaceView attachSurface
    // runs without a 1-frame null detach.
    val effectiveTabs = resolveEffectiveTreeTabs(
        tabs = treeUiState.tabs,
        streamUiState = streamUiState,
        hosts = dashboardUiState.hosts,
    )
    LaunchedEffect(effectiveTabs) {
        if (effectiveTabs != treeUiState.tabs) {
            effectiveTabs.active?.let { treeViewModel.openTab(it) }
        }
    }

    val wiring = rememberTreeWiring(
        dashboardViewModel = dashboardViewModel,
        dashboardUiState = dashboardUiState,
        terminalViewModel = terminalViewModel,
        terminalUiState = terminalUiState,
        streamViewModel = streamViewModel,
        streamUiState = streamUiState,
        treeViewModel = treeViewModel,
        treeUiState = treeUiState,
        effectiveTabs = effectiveTabs,
        inFullSpace = inFullSpace,
        onToggleStreamPopOut = if (inFullSpace) {
            { treeViewModel.setStreamPoppedOut(!treeUiState.streamPoppedOut) }
        } else {
            null
        },
        monitoringViewModel = monitoringViewModel,
        monitoringUiState = monitoringUiState,
        onEnsureTabsVisible = if (inFullSpace) {
            { treeViewModel.setTabsVisible(true) }
        } else {
            null
        },
    )
    val controller = wiring.controller

    // Honour the helix_panel_id intent extra once: "terminal" selects the first terminal tab,
    // "stream" opens the stream tab (HELIX_STREAM_HOST_NAME preferred); "workspace" is the tree itself.
    var panelHandled by remember { mutableStateOf(initialPanelId == null) }
    LaunchedEffect(initialPanelId, dashboardUiState.hosts, terminalUiState.tabs.size) {
        if (panelHandled) return@LaunchedEffect
        when (initialPanelId) {
            DetachedPanelActivity.PANEL_TERMINAL -> terminalUiState.tabs.firstOrNull()?.let {
                controller.select(terminalContentTab(it))
                panelHandled = true
            }
            DetachedPanelActivity.PANEL_STREAM -> {
                val hosts = dashboardUiState.hosts
                val host = hosts.firstOrNull { isStreamHost(it) } ?: hosts.firstOrNull()
                if (host != null) {
                    controller.perform(TreeNode.Host(host, host.status), NodeAction.STREAM)
                    panelHandled = true
                }
            }
            else -> panelHandled = true
        }
    }

    val topBar: @Composable (() -> Unit, @Composable RowScope.() -> Unit) -> Unit = { afterOpen, trailing ->
        HelixTopBar(
            backend = dashboardUiState.displayBackend,
            loading = dashboardUiState.isLoading || monitoringUiState.isLoading,
            alertCount = monitoringUiState.activeAlertCount,
            hasCriticalAlerts = monitoringUiState.hasCriticalAlerts,
            onRefresh = { dashboardViewModel.refreshHomelabData() },
            onAlerts = {
                if (inFullSpace) {
                    treeViewModel.setShowAlertsModal(true)
                } else if (controller.openGlobal(TabKind.ALERTS)) {
                    afterOpen()
                }
            },
            onSnippets = {
                if (controller.openGlobal(TabKind.SNIPPETS)) {
                    if (inFullSpace) {
                        treeViewModel.setTabsVisible(true)
                    }
                    afterOpen()
                }
            },
            onSession = {
                if (inFullSpace) {
                    treeViewModel.setShowSessionModal(true)
                } else if (controller.openGlobal(TabKind.SESSION)) {
                    afterOpen()
                }
            },
            trailing = {
                if (spatialUiState.isUrxrGlasses) {
                    IconButton(onClick = { onOpenTrackpad?.invoke() ?: run { showTrackpad = true } }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Mouse, contentDescription = "Trackpad deck", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                trailing()
            },
        )
    }
    val banners: @Composable () -> Unit = {
        TreeBanners(dashboardViewModel, dashboardUiState, treeViewModel, treeUiState)
    }

    when {
        inFullSpace -> FullSpaceLayout(
            spatialViewModel = spatialViewModel,
            dashboardViewModel = dashboardViewModel,
            dashboardUiState = dashboardUiState,
            streamUiState = streamUiState,
            treeViewModel = treeViewModel,
            treeState = treeUiState,
            effectiveTabs = effectiveTabs,
            env = wiring.env,
            alertCount = monitoringUiState.activeAlertCount,
            hasCriticalAlerts = monitoringUiState.hasCriticalAlerts,
            onOpenStream = {
                val host = dashboardUiState.hosts.firstOrNull { isStreamHost(it) }
                    ?: dashboardUiState.hosts.firstOrNull()
                if (host != null) {
                    controller.perform(TreeNode.Host(host, host.status), NodeAction.STREAM)
                    treeViewModel.setTabsVisible(true)
                }
            },
            onConfirmPendingNode = { pending ->
                controller.perform(pending.node, pending.action)
                treeViewModel.dismissNodeConfirmation()
            },
            topBar = { topBar({}, it) },
            banners = banners,
            treePane = wiring.treePane,
            tabsPane = wiring.tabsPane,
            onRequestHomeSpace = onRequestHomeSpace,
            onRecenter = { spatialViewModel.recenter3Dof() },
        )
        // A Home Space panel resized to phone width cannot fit the fixed tree column plus tabs: use the
        // phone layout there (single pane with back navigation); widen the panel to get detach/Full Space back.
        spatialUiState.isAndroidXr && isNarrowWindow() -> AdaptiveTreeLayout(
            modifier = modifier,
            tabs = effectiveTabs,
            onSelectTab = controller::select,
            header = { afterOpen -> topBar(afterOpen) {} },
            banners = banners,
            treePane = wiring.treePane,
            tabsPane = wiring.tabsPane,
            alertCount = monitoringUiState.activeAlertCount,
            hasCriticalAlerts = monitoringUiState.hasCriticalAlerts,
        )
        spatialUiState.isAndroidXr -> Column(modifier.fillMaxSize()) {
            topBar({}) {
                DetachButton("Terminal") { onDetachWindow(DetachedPanelActivity.PANEL_TERMINAL) }
                DetachButton("Tree") { onDetachWindow(DetachedPanelActivity.PANEL_WORKSPACE) }
                DetachButton("Stream") { onDetachWindow(DetachedPanelActivity.PANEL_STREAM) }
                Button(
                    onClick = onRequestFullSpace,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(" Full Space", fontSize = 12.sp)
                }
            }
            banners()
            HomeSpaceLayout(wiring.treePane, wiring.tabsPane)
        }
        else -> AdaptiveTreeLayout(
            modifier = modifier,
            tabs = effectiveTabs,
            onSelectTab = controller::select,
            header = { afterOpen -> topBar(afterOpen) {} },
            banners = banners,
            treePane = wiring.treePane,
            tabsPane = wiring.tabsPane,
            alertCount = monitoringUiState.activeAlertCount,
            hasCriticalAlerts = monitoringUiState.hasCriticalAlerts,
        )
    }
}

/**
 * Content of a detached "workspace" window in concept A: the tree plus its tab content, opening nodes in
 * that window. Uses its own ViewModels (the window is a separate Activity) over the shared repositories.
 */
@Composable
fun DetachedTreeWorkspace(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    terminalViewModel: TerminalViewModel,
    terminalUiState: TerminalUiState,
    streamViewModel: StreamViewModel,
    streamUiState: StreamUiState,
    treeViewModel: TreeViewModel,
    treeUiState: TreeUiState,
    modifier: Modifier = Modifier,
    monitoringViewModel: MonitoringViewModel? = null,
    monitoringUiState: MonitoringUiState = MonitoringUiState(),
) {
    val wiring = rememberTreeWiring(
        dashboardViewModel = dashboardViewModel,
        dashboardUiState = dashboardUiState,
        terminalViewModel = terminalViewModel,
        terminalUiState = terminalUiState,
        streamViewModel = streamViewModel,
        streamUiState = streamUiState,
        treeViewModel = treeViewModel,
        treeUiState = treeUiState,
        monitoringViewModel = monitoringViewModel,
        monitoringUiState = monitoringUiState,
    )
    AdaptiveTreeLayout(
        modifier = modifier,
        tabs = treeUiState.tabs,
        onSelectTab = wiring.controller::select,
        header = {},
        banners = { TreeBanners(dashboardViewModel, dashboardUiState, treeViewModel, treeUiState) },
        treePane = wiring.treePane,
        tabsPane = wiring.tabsPane,
        alertCount = monitoringUiState.activeAlertCount,
        hasCriticalAlerts = monitoringUiState.hasCriticalAlerts,
    )
}

@Composable
private fun TreeBanners(
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    treeViewModel: TreeViewModel,
    treeUiState: TreeUiState,
) {
    StatusBanners(
        wol = dashboardUiState.wolStatus,
        snippet = dashboardUiState.lastSnippetOutput,
        error = dashboardUiState.errorMessage,
        onClearError = { dashboardViewModel.clearError() },
        message = treeUiState.message,
        onClearMessage = { treeViewModel.clearMessage() },
    )
}

/**
 * 1. Phone/tablet/window: tree in the list pane, tabbed content in the detail pane (single pane on compact).
 * [header] receives a callback that switches to the detail pane, so top-bar tabs (Alerts, Web, Session,
 * Snippets) are visible on compact screens. On compact screens the open tabs also show as a switcher under the tree.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun AdaptiveTreeLayout(
    tabs: TabsState,
    onSelectTab: (ContentTab) -> Unit,
    header: @Composable (afterOpen: () -> Unit) -> Unit,
    banners: @Composable () -> Unit,
    treePane: @Composable (Modifier, () -> Unit) -> Unit,
    tabsPane: @Composable (Modifier, (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
    alertCount: Int = 0,
    hasCriticalAlerts: Boolean = false,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>()
    val scope = rememberCoroutineScope()
    val singlePane = navigator.scaffoldDirective.maxHorizontalPartitions == 1
    val showDetail: () -> Unit = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) } }
    val hasTabs = tabs.tabs.isNotEmpty()

    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch { navigator.navigateBack() }
    }
    // Closing the last tab on a compact screen returns to the tree.
    LaunchedEffect(hasTabs) {
        if (!hasTabs && navigator.canNavigateBack()) navigator.navigateBack()
    }

    Column(modifier.fillMaxSize()) {
        header(showDetail)
        banners()
        ListDetailPaneScaffold(
            modifier = Modifier.weight(1f),
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = {
                AnimatedPane {
                    Column(Modifier.fillMaxSize()) {
                        treePane(Modifier.weight(1f).fillMaxWidth(), showDetail)
                        if (singlePane) {
                            OpenTabsStrip(
                                tabs = tabs,
                                onSelect = onSelectTab,
                                onOpen = showDetail,
                                alertCount = alertCount,
                                hasCriticalAlerts = hasCriticalAlerts,
                            )
                        }
                    }
                }
            },
            detailPane = {
                AnimatedPane {
                    tabsPane(
                        Modifier.fillMaxSize(),
                        if (singlePane) {
                            { scope.launch { navigator.navigateBack() } }
                        } else {
                            null
                        },
                    )
                }
            },
        )
    }
}

/** 2. Android XR Home Space: one panel, tree on the left, tab content on the right. */
@Composable
private fun HomeSpaceLayout(
    treePane: @Composable (Modifier, () -> Unit) -> Unit,
    tabsPane: @Composable (Modifier, (() -> Unit)?) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        treePane(Modifier.width(TREE_WIDTH_DP.dp).fillMaxHeight()) {}
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
        tabsPane(Modifier.weight(1f).fillMaxHeight(), null)
    }
}

/**
 * 3. Android XR Full Space: tree in a left SpatialPanel, tabbed content in a large center SpatialPanel,
 * an optional floating media SpatialPanel and a pop-out stream panel sized from the stream resolution.
 * Panel visibility reuses [SpatialPanelId]: LEFT_TERMINAL = tree, CENTER_DESKTOP = tabs, FLOATING_MEDIA = media.
 * Session/permission gating lives in XrSessionController and is untouched.
 */
@Composable
private fun FullSpaceLayout(
    spatialViewModel: SpatialViewModel,
    dashboardViewModel: DashboardViewModel,
    dashboardUiState: DashboardUiState,
    streamUiState: StreamUiState,
    treeViewModel: TreeViewModel,
    treeState: TreeUiState,
    effectiveTabs: TabsState,
    env: TabEnv,
    alertCount: Int,
    hasCriticalAlerts: Boolean,
    onOpenStream: () -> Unit,
    onConfirmPendingNode: (com.nightcinder.helix.ui.state.PendingNodeConfirmation) -> Unit,
    topBar: @Composable (@Composable RowScope.() -> Unit) -> Unit,
    banners: @Composable () -> Unit,
    treePane: @Composable (Modifier, () -> Unit) -> Unit,
    tabsPane: @Composable (Modifier, (() -> Unit)?) -> Unit,
    onRequestHomeSpace: () -> Unit,
    onRecenter: () -> Unit,
) {
    val resolution = streamUiState.config.resolution
    val streamWidthDp = resolution.spatialPanelWidthDp(
        videoHeightDp = FULL_SPACE_CENTER_HEIGHT_DP,
        chromeHorizontalDp = 0,
        minWidthDp = 1120,
        maxWidthDp = 1680,
    )
    val isActivelyStreaming = (streamUiState.streamState.status == StreamConnectionStatus.Connected ||
        streamUiState.streamState.status == StreamConnectionStatus.Connecting ||
        streamUiState.streamState.status == StreamConnectionStatus.Pairing) &&
        streamUiState.streamState.activeTransport != StreamTransportMode.EXTERNAL_NATIVE

    val treeVisible = treeState.treeVisible
    val tabsVisible = treeState.tabsVisible
    val isCenterStream = effectiveTabs.active?.kind == TabKind.STREAM && !treeState.streamPoppedOut
    val centerWidthDp = if (isCenterStream) streamWidthDp else FULL_SPACE_DEFAULT_CENTER_WIDTH_DP
    val treeOffsetX = -resolution.spatialWingOffsetXDp(
        centerWidthDp = centerWidthDp,
        wingWidthDp = FULL_SPACE_TREE_WIDTH_DP,
        gapDp = 20,
    )

    // Capture fresh state & callbacks for Orbiter subcomposition (Orbiter lambdas can capture stale closures).
    val currentTreeVisible by rememberUpdatedState(treeVisible)
    val currentTabsVisible by rememberUpdatedState(tabsVisible)
    val currentShowAlerts by rememberUpdatedState(treeState.showAlertsModal)
    val currentShowSession by rememberUpdatedState(treeState.showSessionModal)
    val currentStreamPoppedOut by rememberUpdatedState(treeState.streamPoppedOut)
    val currentCanPopOutStream by rememberUpdatedState(
        effectiveTabs.active?.kind == TabKind.STREAM || treeState.streamPoppedOut,
    )
    val currentOnOpenStream by rememberUpdatedState(onOpenStream)
    val currentOnRequestHomeSpace by rememberUpdatedState(onRequestHomeSpace)
    val currentOnRecenter by rememberUpdatedState(onRecenter)

    val controls: @Composable () -> Unit = {
        FullSpaceControls(
            treeVisible = currentTreeVisible,
            tabsVisible = currentTabsVisible,
            showAlerts = currentShowAlerts,
            showSession = currentShowSession,
            canPopOutStream = currentCanPopOutStream,
            streamPoppedOut = currentStreamPoppedOut,
            alertCount = alertCount,
            hasCriticalAlerts = hasCriticalAlerts,
            onOpenStream = { currentOnOpenStream() },
            onToggleAlerts = { treeViewModel.setShowAlertsModal(!currentShowAlerts) },
            onToggleSession = { treeViewModel.setShowSessionModal(!currentShowSession) },
            onToggleStreamPopOut = { treeViewModel.setStreamPoppedOut(!currentStreamPoppedOut) },
            onToggleTree = { treeViewModel.toggleTreeVisible() },
            onToggleTabs = { treeViewModel.toggleTabsVisible() },
            onRequestHomeSpace = { currentOnRequestHomeSpace() },
            onRecenter = { currentOnRecenter() },
        )
    }
    val controlsOrbiter: @Composable () -> Unit = {
        Orbiter(position = OrbiterPosition.TopCenter(), shape = OrbiterDefaults.Shape) { controls() }
    }

    Subspace(modifier = SubspaceModifier) {
        if (treeVisible) SpatialPanel(
            modifier = SubspaceModifier
                .width(FULL_SPACE_TREE_WIDTH_DP.dp)
                .height(FULL_SPACE_CENTER_HEIGHT_DP.dp)
                .offset(treeOffsetX.dp, 0.dp, 60.dp)
                .rotate(0f, 14f, 0f)
                .movable()
                .resizable(),
        ) {
            Surface(
                Modifier.fillMaxSize(),
                color = FULL_SPACE_SURFACE_COLOR,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    topBar {}
                    banners()
                    treePane(Modifier.weight(1f).fillMaxWidth()) {}
                }
                // The controls live on the tabs panel; fall back to this panel when tabs are hidden.
                if (!tabsVisible) controlsOrbiter()
            }
        }

        if (tabsVisible) SpatialPanel(
            modifier = SubspaceModifier
                .width(centerWidthDp.dp)
                .height(FULL_SPACE_CENTER_HEIGHT_DP.dp)
                .offset(0.dp, 0.dp, 0.dp)
                .movable()
                .resizable(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = FULL_SPACE_SURFACE_COLOR,
                shape = RoundedCornerShape(if (isCenterStream && isActivelyStreaming) 0.dp else 12.dp),
            ) {
                Box(Modifier.fillMaxSize()) {
                    tabsPane(Modifier.fillMaxSize(), null)
                    controlsOrbiter()
                }
            }
        }

        if (!treeVisible && !tabsVisible) SpatialPanel(
            modifier = SubspaceModifier.width(720.dp).height(96.dp).movable(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = FULL_SPACE_SURFACE_COLOR,
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { controls() }
            }
        }

        if (treeState.streamPoppedOut) {
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(streamWidthDp.dp)
                    .height(560.dp)
                    .offset(0.dp, 620.dp, 40.dp)
                    .movable()
                    .resizable(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = FULL_SPACE_SURFACE_COLOR,
                    shape = RoundedCornerShape(if (isActivelyStreaming) 0.dp else 12.dp),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        StreamScreen(
                            viewModel = env.stream,
                            uiState = env.streamState,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (!isActivelyStreaming) {
                            IconButton(
                                onClick = { env.onToggleStreamPopOut?.invoke() },
                                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(36.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Dock stream back", tint = CyanPrimary)
                            }
                        }
                    }
                }
            }
        }

        if (treeState.showAlertsModal) SpatialPanel(
            modifier = SubspaceModifier
                .width(780.dp)
                .height(580.dp)
                .offset(0.dp, 0.dp, 180.dp)
                .movable()
                .resizable(),
        ) {
            Surface(
                color = FULL_SPACE_SURFACE_COLOR,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = { treeViewModel.setShowAlertsModal(false) }) {
                            Icon(Icons.Default.Close, contentDescription = "Close alerts")
                        }
                        Text(
                            text = "Homelab Alerts & Telemetry",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    AlertsFeedPanel(
                        uiState = env.monitoringState,
                        hosts = dashboardUiState.hosts,
                        onSelectFilter = { env.monitoring?.selectSourceFilter(it) },
                        onRefresh = {
                            val mon = env.monitoring
                            if (mon != null && dashboardUiState.hosts.isNotEmpty()) {
                                mon.refresh(dashboardUiState.hosts, dashboardUiState.wolDevices)
                            } else {
                                dashboardViewModel.refreshHomelabData()
                            }
                        },
                        onOpenHostTerminal = { host ->
                            env.onOpenHostTerminal(host)
                            treeViewModel.setTabsVisible(true)
                            treeViewModel.setShowAlertsModal(false)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (treeState.showSessionModal) SpatialPanel(
            modifier = SubspaceModifier
                .width(780.dp)
                .height(580.dp)
                .offset(0.dp, 0.dp, 180.dp)
                .movable()
                .resizable(),
        ) {
            Surface(
                color = FULL_SPACE_SURFACE_COLOR,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = { treeViewModel.setShowSessionModal(false) }) {
                            Icon(Icons.Default.Close, contentDescription = "Close session")
                        }
                        Text(
                            text = "Session, Passkey & Layout",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    AuthScreen(
                        sessionState = dashboardUiState.authState,
                        isLoading = dashboardUiState.isLoading,
                        errorMessage = dashboardUiState.errorMessage,
                        onSignInPasskey = { dashboardViewModel.signInWithPasskey(it) },
                        onSilentRefresh = { dashboardViewModel.silentRefresh() },
                        onSignOut = {
                            treeViewModel.setShowSessionModal(false)
                            dashboardViewModel.signOut()
                        },
                        onUpdateConfig = { dashboardViewModel.updateAuthentikConfig(it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        treeState.pendingNodeConfirmation?.let { pending ->
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(520.dp)
                    .height(240.dp)
                    .offset(0.dp, 0.dp, 200.dp)
                    .movable(),
            ) {
                Surface(
                    color = FULL_SPACE_SURFACE_COLOR,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = actionLabel(pending.node, pending.action),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = CyanPrimary,
                            )
                            Text(
                                text = confirmationMessage(pending.node),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { treeViewModel.dismissNodeConfirmation() }) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = { onConfirmPendingNode(pending) },
                                colors = ButtonDefaults.buttonColors(containerColor = RoseError, contentColor = Color.White),
                            ) {
                                Text("Confirm")
                            }
                        }
                    }
                }
            }
        }

        treeState.fileEdit?.let { edit ->
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(680.dp)
                    .height(480.dp)
                    .offset(0.dp, 0.dp, 190.dp)
                    .movable()
                    .resizable(),
            ) {
                var text by remember(edit.path, edit.content) { mutableStateOf(edit.content) }
                Surface(
                    color = FULL_SPACE_SURFACE_COLOR,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Edit: ${edit.name}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = CyanPrimary,
                        )
                        Text(
                            text = edit.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { treeViewModel.dismissFile() }) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = { treeViewModel.saveFile(edit, text) },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullSpaceControls(
    treeVisible: Boolean,
    tabsVisible: Boolean,
    showAlerts: Boolean,
    showSession: Boolean,
    canPopOutStream: Boolean,
    streamPoppedOut: Boolean,
    alertCount: Int,
    hasCriticalAlerts: Boolean,
    onOpenStream: () -> Unit,
    onToggleAlerts: () -> Unit,
    onToggleSession: () -> Unit,
    onToggleStreamPopOut: () -> Unit,
    onToggleTree: () -> Unit,
    onToggleTabs: () -> Unit,
    onRequestHomeSpace: () -> Unit,
    onRecenter: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.82f),
        modifier = Modifier.padding(8.dp),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRequestHomeSpace,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937), contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Icon(Icons.Default.CropLandscape, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(" Home Space", fontSize = 11.sp)
            }
            IconButton(onClick = onRecenter, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Recenter", tint = CyanPrimary, modifier = Modifier.size(16.dp))
            }
            Button(
                onClick = onOpenStream,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(" Desktop", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onToggleAlerts,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(14.dp),
                )
                Text(if (showAlerts) " Hide Alerts" else " Alerts", fontSize = 11.sp, color = CyanPrimary)
                if (alertCount > 0) {
                    AlertCountBadge(
                        count = alertCount,
                        hasCritical = hasCriticalAlerts,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            PanelToggle(if (showSession) "Hide Session" else "Session", onToggleSession)
            if (canPopOutStream) {
                PanelToggle(if (streamPoppedOut) "Dock Stream" else "Pop Out", onToggleStreamPopOut)
            }
            PanelToggle(if (treeVisible) "Hide Tree" else "Show Tree", onToggleTree)
            PanelToggle(if (tabsVisible) "Hide Tabs" else "Show Tabs", onToggleTabs)
        }
    }
}

@Composable
private fun PanelToggle(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.height(30.dp),
    ) {
        Text(label, fontSize = 11.sp, color = CyanPrimary)
    }
}

@Composable
private fun DetachButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
        Text(" $label", fontSize = 12.sp)
    }
}

@Composable
private fun IconButtonFloatingExit(onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        IconButton(onClick = onExit, modifier = Modifier.padding(8.dp).size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to tree", tint = CyanPrimary)
        }
    }
}

@Composable
private fun HelixTopBar(
    backend: WorkspaceDisplayBackend,
    loading: Boolean,
    alertCount: Int,
    hasCriticalAlerts: Boolean,
    onRefresh: () -> Unit,
    onAlerts: () -> Unit,
    onSnippets: () -> Unit,
    onSession: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "HELIX",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp),
                    color = CyanPrimary,
                    maxLines = 1,
                )
                BackendPill(backend)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                trailing()
                if (loading) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 6.dp).size(18.dp), strokeWidth = 2.dp, color = CyanPrimary)
                } else {
                    BarIcon(Icons.Default.Refresh, "Refresh homelab", onRefresh)
                }
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onAlerts)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Alerts"
                        }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    if (alertCount > 0) {
                        AlertCountBadge(
                            count = alertCount,
                            hasCritical = hasCriticalAlerts,
                        )
                    }
                }
                BarIcon(Icons.Default.Code, "Snippets", onSnippets)
                BarIcon(Icons.Default.Security, "Session and sign out", onSession)
            }
        }
    }
}

@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun BackendPill(backend: WorkspaceDisplayBackend) {
    val (bg, fg) = when (backend) {
        is WorkspaceDisplayBackend.AndroidXrSpatial -> EmeraldAccent.copy(alpha = 0.2f) to EmeraldAccent
        is WorkspaceDisplayBackend.Urxr3Dof -> CyanPrimary.copy(alpha = 0.2f) to CyanPrimary
        is WorkspaceDisplayBackend.ExternalPresentation -> AmberAccent.copy(alpha = 0.2f) to AmberAccent
        WorkspaceDisplayBackend.Standard2D -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Default.ViewInAr, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        Text(
            backend.displayLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Wake-on-LAN, snippet, error and tree-message banners. WOL and snippet results are tracked by identity
 * ([BannerTracker]) and dismissed by event id, so an identical repeated result shows (and can be dismissed) again.
 */
@Composable
private fun StatusBanners(
    wol: WolWakeResult?,
    snippet: SnippetExecutionResult?,
    error: String?,
    onClearError: () -> Unit,
    message: String?,
    onClearMessage: () -> Unit,
) {
    val wolTracker = remember { BannerTracker<WolWakeResult>() }
    val snippetTracker = remember { BannerTracker<SnippetExecutionResult>() }
    val wolEvent = wolTracker.observe(wol)
    val snippetEvent = snippetTracker.observe(snippet)
    var dismissedWol by remember { mutableStateOf(0L) }
    var dismissedSnippet by remember { mutableStateOf(0L) }

    AnimatedVisibility(visible = wolEvent != null && wolEvent.id != dismissedWol) {
        wolEvent?.let { event ->
            val it = event.value
            Banner(
                text = "WOL: ${it.message ?: if (it.success) "Magic packet sent to ${it.deviceName ?: it.macAddress}" else "Failed to send magic packet"}",
                color = if (it.success) EmeraldAccent else RoseError,
                icon = true,
            ) { dismissedWol = event.id }
        }
    }
    AnimatedVisibility(visible = snippetEvent != null && snippetEvent.id != dismissedSnippet) {
        snippetEvent?.let { event ->
            SnippetBanner(event.value) { dismissedSnippet = event.id }
        }
    }
    AnimatedVisibility(visible = error != null) { error?.let { Banner(it, RoseError, onDismiss = onClearError) } }
    AnimatedVisibility(visible = message != null) { message?.let { Banner(it, AmberAccent, onDismiss = onClearMessage) } }
}

/** Full snippet output in a scrollable, monospace box. */
@Composable
private fun SnippetBanner(result: SnippetExecutionResult, onDismiss: () -> Unit) {
    val color = if (result.success) EmeraldAccent else RoseError
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f))
            .padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Snippet ${if (result.success) "ok" else "failed"}" + (result.exitCode?.let { " (exit $it)" } ?: ""),
                color = color,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) { Text("✕", color = color) }
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 180.dp).padding(end = 12.dp).verticalScroll(rememberScrollState())) {
            Text(
                result.combinedOutput.ifBlank { "(no output)" },
                color = color,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun Banner(text: String, color: Color, icon: Boolean = false, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon) Icon(Icons.Default.FlashOn, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 6.dp), maxLines = 3)
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Text("✕", color = color)
        }
    }
}

/** Window narrower than the Material compact breakpoint (a phone, or a Home Space panel resized that small). */
@Composable
private fun isNarrowWindow(): Boolean = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP

private const val COMPACT_WIDTH_DP = 600
