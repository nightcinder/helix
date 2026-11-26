package com.nightcinder.helix.ui.tree

import com.nightcinder.helix.core.model.SiteConfig
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.TermixHost

/** A node of the connection tree: host > Docker stack > container. */
sealed interface TreeNode {
    val key: String
    val hostId: Long
    val label: String

    data class Host(val host: TermixHost, val status: HostStatus) : TreeNode {
        override val key get() = hostKey(host.id)
        override val hostId get() = host.id
        override val label get() = host.displayName
    }

    data class Stack(
        override val hostId: Long,
        val hostName: String,
        val name: String,
        val running: Int,
        val total: Int,
    ) : TreeNode {
        override val key get() = stackKey(hostId, name)
        override val label get() = name
    }

    data class Container(
        override val hostId: Long,
        val hostName: String,
        val container: DockerContainer,
    ) : TreeNode {
        override val key get() = containerKey(hostId, container.id)
        override val label get() = container.displayName
    }
}

fun hostKey(hostId: Long) = "h:$hostId"
fun stackKey(hostId: Long, stack: String) = "s:$hostId:$stack"
fun containerKey(hostId: Long, containerId: String) = "c:$hostId:$containerId"

data class TreeRow(
    val node: TreeNode,
    val depth: Int,
    val expandable: Boolean,
    val expanded: Boolean,
)

/**
 * Host filter: name, address, hostname, folder, tags, OS, or any loaded stack/container name contains
 * [query] (case-insensitive). A blank query matches everything.
 */
fun hostMatches(
    host: TermixHost,
    query: String,
    stacks: List<DockerStack> = emptyList(),
    containers: List<DockerContainer> = emptyList(),
): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val fields = listOfNotNull(host.name, host.ip, host.hostname, host.folder, host.os, host.distro) +
        host.tags + stacks.map { it.name } + containers.map { it.displayName }
    return fields.any { it.contains(q, ignoreCase = true) }
}

/**
 * Flattens hosts > stacks > containers into visible rows. Containers that report a stack name but are
 * not listed inside the stack are still attached to it; containers without a matching stack are shown
 * directly under the host. Hosts not matching [query] are left out.
 */
fun flattenTree(
    hosts: List<TermixHost>,
    stacksByHost: Map<Long, List<DockerStack>>,
    containersByHost: Map<Long, List<DockerContainer>>,
    expanded: Set<String>,
    query: String = "",
    hostStatus: (TermixHost) -> HostStatus = { it.status },
): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    val visibleHosts = hosts.filter {
        hostMatches(it, query, stacksByHost[it.id].orEmpty(), containersByHost[it.id].orEmpty())
    }
    for (host in visibleHosts) {
        val hostNode = TreeNode.Host(host, hostStatus(host))
        val hostOpen = hostNode.key in expanded
        rows += TreeRow(hostNode, depth = 0, expandable = true, expanded = hostOpen)
        if (!hostOpen) continue

        val stacks = stacksByHost[host.id].orEmpty()
        val all = containersByHost[host.id].orEmpty()
        val stackNames = stacks.map { it.name }.toSet()

        for (stack in stacks) {
            val members = (stack.containers + all.filter { it.stackName == stack.name })
                .distinctBy { it.id }
            val node = TreeNode.Stack(
                hostId = host.id,
                hostName = host.displayName,
                name = stack.name,
                running = if (members.isEmpty()) stack.runningCount else members.count { it.isRunning },
                total = if (members.isEmpty()) stack.serviceCount else members.size,
            )
            val stackOpen = node.key in expanded
            rows += TreeRow(node, depth = 1, expandable = members.isNotEmpty(), expanded = stackOpen)
            if (stackOpen) {
                members.forEach { rows += containerRow(host, it, depth = 2) }
            }
        }
        all.filter { it.stackName.isNullOrBlank() || it.stackName !in stackNames }
            .forEach { rows += containerRow(host, it, depth = 1) }
    }
    return rows
}

private fun containerRow(host: TermixHost, container: DockerContainer, depth: Int) = TreeRow(
    node = TreeNode.Container(host.id, host.displayName, container),
    depth = depth,
    expandable = false,
    expanded = false,
)

/** Actions offered in a node's overflow menu. */
enum class NodeAction(val label: String) {
    TERMINAL("Open terminal"),
    FILES("Open files"),
    LOGS("Open logs"),
    STREAM("Stream desktop"),
    METRICS("Info & metrics"),
    WAKE("Wake on LAN"),
    START("Start"),
    UP("Up (start stack)"),
    STOP("Stop"),
    RESTART("Restart"),
    PULL("Pull images"),
    REFRESH("Refresh"),
}

/** Streaming is offered on every host; the HELIX_STREAM_HOST_NAME host is the recommended (highlighted) one. */
fun isStreamHost(host: TermixHost): Boolean =
    SiteConfig.isRecommendedStreamHost(host.name, host.displayName)

fun canWake(host: TermixHost, status: HostStatus): Boolean =
    host.macAddress != null ||
        host.effectiveHomelabProfile?.wolMacAddress != null ||
        status == HostStatus.OFFLINE ||
        status == HostStatus.SLEEPING

fun actionsFor(node: TreeNode): List<NodeAction> = when (node) {
    is TreeNode.Host -> buildList {
        add(NodeAction.TERMINAL)
        add(NodeAction.FILES)
        add(NodeAction.METRICS)
        add(NodeAction.STREAM)
        if (canWake(node.host, node.status)) add(NodeAction.WAKE)
        add(NodeAction.REFRESH)
    }
    is TreeNode.Stack -> listOf(
        NodeAction.UP, NodeAction.RESTART, NodeAction.PULL, NodeAction.STOP, NodeAction.REFRESH,
    )
    is TreeNode.Container ->
        if (node.container.isRunning) {
            listOf(NodeAction.TERMINAL, NodeAction.LOGS, NodeAction.RESTART, NodeAction.STOP)
        } else {
            listOf(NodeAction.LOGS, NodeAction.START)
        }
}

/** Menu label for [action] on [node]; the recommended stream host (HELIX_STREAM_HOST_NAME) is flagged. */
fun actionLabel(node: TreeNode, action: NodeAction): String = when {
    action == NodeAction.STREAM && node is TreeNode.Host && isStreamHost(node.host) -> "Stream desktop (recommended)"
    action == NodeAction.STOP && node is TreeNode.Stack -> "Down (stop stack)"
    else -> action.label
}

/** Destructive actions (container stop, stack down) must be confirmed before they run. */
fun needsConfirmation(node: TreeNode, action: NodeAction): Boolean =
    action == NodeAction.STOP && (node is TreeNode.Container || node is TreeNode.Stack)

fun confirmationMessage(node: TreeNode): String = when (node) {
    is TreeNode.Stack -> "Run docker compose down for stack \"${node.label}\"? This stops and removes its containers."
    is TreeNode.Container -> "Stop container \"${node.label}\"?"
    is TreeNode.Host -> "Continue?"
}

/** One banner occurrence. A fresh [id] is issued for every distinct emission, even when the payload is equal. */
data class BannerEvent<T>(val id: Long, val value: T)

/**
 * Tracks emissions by identity, so an identical repeated result (a new object that equals the old one)
 * still yields a new, dismissible event instead of staying hidden behind an equality check.
 */
class BannerTracker<T : Any> {
    private var counter = 0L
    private var current: BannerEvent<T>? = null

    fun observe(value: T?): BannerEvent<T>? {
        if (value == null) {
            current = null
            return null
        }
        val existing = current
        if (existing != null && existing.value === value) return existing
        val next = BannerEvent(++counter, value)
        current = next
        return next
    }
}

/**
 * Merges an incoming error message into the current one so the same failure posted twice (a generic
 * repository error plus a per-action message wrapping it) is shown once, preferring the contextual text.
 */
fun mergeMessage(current: String?, incoming: String): String = when {
    current == null -> incoming
    current.contains(incoming) -> current
    else -> incoming
}
