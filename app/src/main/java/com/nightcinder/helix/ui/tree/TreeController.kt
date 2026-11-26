package com.nightcinder.helix.ui.tree

import com.nightcinder.helix.core.model.DockerContainerAction
import com.nightcinder.helix.core.model.DockerStackAction
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.TreeViewModel

/**
 * Glue between tree gestures and the existing ViewModels (terminal, stream, dashboard/WOL, workspace repo).
 * Plain class so every layout (phone, XR Home Space, XR Full Space) shares the same behaviour.
 */
class TreeController(
    private val tree: TreeViewModel,
    private val terminal: TerminalViewModel,
    private val stream: StreamViewModel,
    private val dashboard: DashboardViewModel,
) {
    /** Expand/collapse a row; expanding a host loads its Docker tree. */
    fun toggle(row: TreeRow) {
        val node = row.node
        if (node is TreeNode.Host) tree.expandHost(node.key, node.hostId) else tree.toggleExpanded(node.key)
    }

    /** Row tap: containers open their logs, hosts and stacks expand. Returns true if a tab was opened. */
    fun tap(row: TreeRow): Boolean {
        if (row.node is TreeNode.Container) return perform(row.node, NodeAction.LOGS)
        toggle(row)
        return false
    }

    /** Runs an overflow-menu action. Returns true when it opened or selected a content tab. */
    fun perform(node: TreeNode, action: NodeAction): Boolean {
        val host = dashboard.uiState.value.hosts.firstOrNull { it.id == node.hostId }
        val hostName = host?.displayName ?: node.label
        when (action) {
            NodeAction.TERMINAL -> {
                return when (node) {
                    is TreeNode.Container -> {
                        val terminalId = terminal.openDockerConsoleTab(
                            node.hostId, hostName, node.container.id, node.container.displayName,
                        )
                        activate(
                            contentTab(
                                TabKind.TERMINAL, node.hostId, hostName,
                                containerName = node.container.displayName,
                                terminalTabId = terminalId,
                            ),
                        )
                        true
                    }
                    else -> openTerminal(host ?: return false)
                }
            }
            NodeAction.FILES -> return open(contentTab(TabKind.FILES, node.hostId, hostName))
            NodeAction.METRICS -> return open(contentTab(TabKind.METRICS, node.hostId, hostName))
            NodeAction.STREAM -> {
                host ?: return false
                return open(contentTab(TabKind.STREAM, node.hostId, hostName))
            }
            NodeAction.LOGS -> {
                val c = (node as? TreeNode.Container)?.container ?: return false
                return open(contentTab(TabKind.LOGS, node.hostId, hostName, c.id, c.displayName))
            }
            NodeAction.WAKE -> host?.let { dashboard.wakeHost(it) }
            NodeAction.START -> containerAction(node, DockerContainerAction.START)
            NodeAction.UP -> (node as? TreeNode.Stack)?.let {
                tree.stackAction(it.hostId, it.name, DockerStackAction.UP)
            }
            NodeAction.STOP -> when (node) {
                is TreeNode.Container -> containerAction(node, DockerContainerAction.STOP)
                is TreeNode.Stack -> tree.stackAction(node.hostId, node.name, DockerStackAction.DOWN)
                else -> Unit
            }
            NodeAction.RESTART -> when (node) {
                is TreeNode.Container -> containerAction(node, DockerContainerAction.RESTART)
                is TreeNode.Stack -> tree.stackAction(node.hostId, node.name, DockerStackAction.RESTART)
                else -> Unit
            }
            NodeAction.PULL -> (node as? TreeNode.Stack)?.let {
                tree.stackAction(it.hostId, it.name, DockerStackAction.PULL)
            }
            NodeAction.REFRESH -> tree.loadHost(node.hostId)
        }
        return false
    }

    /** Opens a new SSH terminal tab on [host] and activates its corresponding [ContentTab]. */
    fun openTerminal(host: TermixHost): Boolean {
        if (!host.enableTerminal) return false
        val terminalId = terminal.openHostSshTab(host)
        activate(
            contentTab(
                TabKind.TERMINAL,
                hostId = host.id,
                hostName = host.displayName,
                terminalTabId = terminalId,
            ),
        )
        return true
    }

    fun runSnippet(hostId: Long, snippetId: Long) = dashboard.executeSnippet(snippetId, hostId)

    fun openGlobal(kind: TabKind): Boolean = open(contentTab(kind))

    fun select(tab: ContentTab) = activate(tab)

    fun close(tab: ContentTab) {
        if (tab.kind == TabKind.TERMINAL) tab.terminalTabId?.let { terminal.closeTab(it) }
        // Closing the stream tab ends the stream. There is no clean way to know whether a detached stream
        // window (another Activity with its own StreamViewModel) is showing the same session, so it is
        // stopped regardless; the detached window can reconnect.
        if (tab.kind == TabKind.STREAM) stream.disconnect()
        tree.closeTab(tab.id)
    }

    private fun containerAction(node: TreeNode, action: DockerContainerAction) {
        (node as? TreeNode.Container)?.let { tree.containerAction(it.hostId, it.container.id, action) }
    }

    private fun open(tab: ContentTab): Boolean {
        activate(tab)
        return true
    }

    /** Opens/selects [tab] and syncs the per-kind ViewModel selection (terminal tab, stream host). */
    private fun activate(tab: ContentTab) {
        tree.openTab(tab)
        when (tab.kind) {
            TabKind.TERMINAL -> tab.terminalTabId?.let { terminal.selectTab(it) }
            TabKind.STREAM -> tab.hostId?.let { id ->
                dashboard.uiState.value.hosts.firstOrNull { it.id == id }?.let { stream.selectHost(it) }
            }
            else -> Unit
        }
    }
}
