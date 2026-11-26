package com.nightcinder.helix.ui.tree

import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.TerminalTabState
import com.nightcinder.helix.core.model.TerminalTarget
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.ui.state.StreamUiState

/** Kinds of content a tab in the detail area can show (concept A: connection tree + tabs). */
enum class TabKind(val label: String) {
    TERMINAL("Terminal"),
    FILES("Files"),
    LOGS("Logs"),
    STREAM("Stream"),
    METRICS("Metrics"),
    ALERTS("Alerts"),
    SESSION("Session"),
    SNIPPETS("Snippets"),
    ;

    /** Global tabs are not bound to a tree node and exist at most once. */
    val isGlobal: Boolean get() = this == ALERTS || this == SESSION || this == SNIPPETS
}

/**
 * One open tab. [id] is stable for non-terminal kinds, so re-opening the same view from the tree
 * selects the existing tab instead of stacking duplicates. Terminal tabs wrap a TerminalViewModel
 * tab ([terminalTabId]); several terminals may be open for the same node.
 */
data class ContentTab(
    val id: String,
    val kind: TabKind,
    val title: String,
    val hostId: Long? = null,
    val containerId: String? = null,
    val terminalTabId: String? = null,
)

fun contentTab(
    kind: TabKind,
    hostId: Long? = null,
    hostName: String = "",
    containerId: String? = null,
    containerName: String? = null,
    terminalTabId: String? = null,
): ContentTab {
    val id = when {
        kind == TabKind.TERMINAL -> "term:${terminalTabId.orEmpty()}"
        kind.isGlobal -> kind.name.lowercase()
        else -> listOf(kind.name.lowercase(), hostId?.toString() ?: "-", containerId ?: "-").joinToString(":")
    }
    val subject = containerName?.takeIf { it.isNotBlank() } ?: hostName.takeIf { it.isNotBlank() }
    val title = if (kind.isGlobal || subject == null) kind.label else "${kind.label} · $subject"
    return ContentTab(
        id = id,
        kind = kind,
        title = title,
        hostId = hostId,
        containerId = containerId,
        terminalTabId = terminalTabId,
    )
}

/** Content tab wrapping a terminal ViewModel tab that may have been opened outside the tree. */
fun terminalContentTab(tab: TerminalTabState): ContentTab {
    val target = tab.target
    return contentTab(
        TabKind.TERMINAL,
        hostId = target.hostId,
        hostName = target.hostName,
        containerName = (target as? TerminalTarget.DockerConsole)?.containerName,
        terminalTabId = tab.id,
    )
}

/**
 * Mirrors the terminal ViewModel tab list into the tree tabs: terminals opened elsewhere (trackpad deck,
 * docker console) get a tab, and tabs whose terminal was closed elsewhere are dropped. A tab is dropped only
 * once its terminal id has been seen in the terminal list, so a tab opened in the tree just before the
 * terminal list catches up survives. New tabs never steal the active selection. Returns the new tabs and
 * the updated seen set.
 */
fun syncTerminalTabs(
    tabs: TabsState,
    terminals: List<TerminalTabState>,
    seen: Set<String>,
): Pair<TabsState, Set<String>> {
    val ids = terminals.map { it.id }.toSet()
    var next = tabs
    tabs.tabs
        .filter { it.kind == TabKind.TERMINAL && it.terminalTabId in seen && it.terminalTabId !in ids }
        .forEach { next = next.close(it.id) }
    val known = next.tabs.mapNotNull { it.terminalTabId }.toSet()
    terminals.filter { it.id !in known }.forEach { t ->
        val active = next.activeId
        next = next.open(terminalContentTab(t))
        if (active != null) next = next.select(active)
    }
    return next to (seen + ids)
}

/** Immutable open-tabs state: insertion order only (tabs are never reordered). */
data class TabsState(
    val tabs: List<ContentTab> = emptyList(),
    val activeId: String? = null,
) {
    val active: ContentTab? get() = tabs.firstOrNull { it.id == activeId }

    /** Opens [tab], or just selects it when a tab with the same id is already open. */
    fun open(tab: ContentTab): TabsState =
        if (tabs.any { it.id == tab.id }) copy(activeId = tab.id) else TabsState(tabs + tab, tab.id)

    fun select(id: String): TabsState = if (tabs.any { it.id == id }) copy(activeId = id) else this

    /** Closing the active tab activates its right neighbour, else its left one. */
    fun close(id: String): TabsState {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = tabs.filterNot { it.id == id }
        val nextActive = when {
            activeId != id -> activeId
            else -> (remaining.getOrNull(index) ?: remaining.lastOrNull())?.id
        }
        return TabsState(remaining, nextActive)
    }
}

/**
 * Synchronously resolves frame-1 Tree tabs when switching from Panels to Tree while an in-app
 * Moonlight stream is Connecting, Pairing, or Connected (`activeTransport != EXTERNAL_NATIVE`).
 */
fun resolveEffectiveTreeTabs(
    tabs: TabsState,
    streamUiState: StreamUiState,
    hosts: List<TermixHost>,
): TabsState {
    val s = streamUiState.streamState
    val isActiveStream = (s.status == StreamConnectionStatus.Connecting ||
        s.status == StreamConnectionStatus.Pairing ||
        s.status == StreamConnectionStatus.Connected) &&
        s.activeTransport != StreamTransportMode.EXTERNAL_NATIVE
    if (!isActiveStream || tabs.active?.kind == TabKind.STREAM) {
        return tabs
    }
    val targetIp = s.targetHostIp.ifBlank { streamUiState.config.hostIp }
    val streamHost = hosts.firstOrNull { it.ip == targetIp }
        ?: hosts.firstOrNull { isStreamHost(it) }
        ?: hosts.firstOrNull()
    val streamTab = contentTab(
        kind = TabKind.STREAM,
        hostId = streamHost?.id,
        hostName = streamHost?.displayName ?: targetIp,
    )
    return tabs.open(streamTab)
}
