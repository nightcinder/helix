package com.nightcinder.helix.terminal

import com.nightcinder.helix.core.terminal.TerminalEvent
import com.nightcinder.helix.core.terminal.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the per-tab [Vt100ScreenBuffer]s so every window (main + detached panels) shows the same
 * terminal state. Output is written exactly once per event by a single collector per transport
 * ([attach]); UI state is published at most every [PUBLISH_INTERVAL_MS].
 */
class TerminalBufferRegistry(
    private val defaultScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val buffers = ConcurrentHashMap<String, Vt100ScreenBuffer>()
    private val dirtyTabs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val publishScheduled = AtomicBoolean(false)
    private val attachedTransports: MutableSet<TerminalTransport> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private val _snapshots = MutableStateFlow<Map<String, TerminalScreenSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, TerminalScreenSnapshot>> = _snapshots.asStateFlow()

    fun bufferFor(tabId: String): Vt100ScreenBuffer =
        buffers.getOrPut(tabId) { Vt100ScreenBuffer() }

    /** Full scrollback text, computed on demand only (O(scrollback)). */
    fun fullText(tabId: String): String {
        val buffer = buffers[tabId] ?: return ""
        return buffer.getFullTextWithScrollback().ifBlank { buffer.getVisibleText() }
    }

    /** Idempotent per transport: the first caller starts the single collector. */
    fun attach(transport: TerminalTransport, scope: CoroutineScope = defaultScope) {
        if (!attachedTransports.add(transport)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { event ->
                if (event is TerminalEvent.Output) {
                    write(scope, event.tabId, event.data)
                }
            }
        }
    }

    private fun write(scope: CoroutineScope, tabId: String, data: String) {
        bufferFor(tabId).writeWithoutPublishing(data)
        dirtyTabs.add(tabId)
        if (publishScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(PUBLISH_INTERVAL_MS)
                publishScheduled.set(false)
                publishDirty()
            }
        }
    }

    /** Publishes snapshots for tabs written since the last publish. */
    fun publishDirty() {
        val tabs = dirtyTabs.toList()
        if (tabs.isEmpty()) return
        dirtyTabs.removeAll(tabs.toSet())
        val updates = HashMap<String, TerminalScreenSnapshot>(tabs.size)
        for (tabId in tabs) {
            val buffer = buffers[tabId] ?: continue
            buffer.publishSnapshot()
            updates[tabId] = buffer.snapshot.value
        }
        if (updates.isNotEmpty()) _snapshots.update { it + updates }
    }

    fun resize(tabId: String, cols: Int, rows: Int) {
        val buffer = buffers[tabId] ?: return
        buffer.resize(cols, rows)
        _snapshots.update { it + (tabId to buffer.snapshot.value) }
    }

    fun remove(tabId: String) {
        buffers.remove(tabId)
        dirtyTabs.remove(tabId)
        _snapshots.update { it - tabId }
    }

    companion object {
        const val PUBLISH_INTERVAL_MS = 33L

        val shared = TerminalBufferRegistry()
    }
}
