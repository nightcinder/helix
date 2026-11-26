package com.nightcinder.helix.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nightcinder.helix.core.api.WorkspaceTreeRepository
import com.nightcinder.helix.core.model.TerminalConnectionState
import com.nightcinder.helix.core.model.TerminalSessionConfig
import com.nightcinder.helix.core.model.TerminalTabState
import com.nightcinder.helix.core.model.TerminalTarget
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.terminal.TerminalEvent
import com.nightcinder.helix.core.terminal.TerminalTransport
import com.nightcinder.helix.terminal.TerminalScreenSnapshot
import com.nightcinder.helix.terminal.Vt100ArrowKey
import com.nightcinder.helix.terminal.Vt100KeyEncoder
import com.nightcinder.helix.terminal.TerminalBufferRegistry
import com.nightcinder.helix.terminal.Vt100SpecialKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class TerminalUiState(
    val tabs: List<TerminalTabState> = emptyList(),
    val activeTabId: String? = null,
    // Full scrollback text is no longer built per chunk (O(scrollback)); the UI renders [snapshots].
    // Kept for API compatibility; use TerminalBufferRegistry.fullText(tabId) when text is needed.
    val buffers: Map<String, String> = emptyMap(),
    val snapshots: Map<String, TerminalScreenSnapshot> = emptyMap(),
    val isCtrlActive: Boolean = false,
    val isAltActive: Boolean = false,
    val errorMessage: String? = null,
) {
    val activeTab: TerminalTabState?
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()

    val activeBufferText: String
        get() = activeTabId?.let { buffers[it] } ?: ""

    val activeSnapshot: TerminalScreenSnapshot?
        get() = activeTabId?.let { snapshots[it] }
}

class TerminalViewModel(
    private val transport: TerminalTransport,
    private val workspaceRepo: WorkspaceTreeRepository,
    private val bufferRegistry: TerminalBufferRegistry = TerminalBufferRegistry.shared,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TerminalUiState(snapshots = bufferRegistry.snapshots.value)
    )
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    // Only the focused window may resize; MainActivity never calls setWindowFocused so it stays true.
    @Volatile
    private var windowFocused = true
    private var lastSize: Pair<Int, Int>? = null

    init {
        // Buffers are written once per Output event by the registry, shared across windows.
        bufferRegistry.attach(transport)
        observeBufferSnapshots()
        observeTerminalEvents()
        observeWorkspaceTabs()
    }

    private fun observeBufferSnapshots() {
        viewModelScope.launch {
            bufferRegistry.snapshots.collect { snaps ->
                _uiState.update { it.copy(snapshots = snaps) }
            }
        }
    }

    private fun observeTerminalEvents() {
        viewModelScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is TerminalEvent.Output -> Unit // handled by TerminalBufferRegistry
                    is TerminalEvent.StateChanged -> {
                        _uiState.update { current ->
                            val updatedTabs = current.tabs.map { tab ->
                                if (tab.id == event.tabId) {
                                    tab.copy(state = event.state, sessionId = event.sessionId ?: tab.sessionId)
                                } else tab
                            }
                            current.copy(tabs = updatedTabs)
                        }
                    }
                    is TerminalEvent.Error -> {
                        _uiState.update { it.copy(errorMessage = event.message) }
                    }
                    is TerminalEvent.DataExpired -> {
                        _uiState.update { current ->
                            val updatedTabs = current.tabs.map { tab ->
                                if (tab.id == event.tabId) {
                                    tab.copy(state = TerminalConnectionState.DataExpired, errorMessage = "DATA_EXPIRED")
                                } else tab
                            }
                            current.copy(tabs = updatedTabs, errorMessage = "Session DEK expired. Re-authentication required.")
                        }
                    }
                }
            }
        }
    }

    private fun observeWorkspaceTabs() {
        viewModelScope.launch {
            workspaceRepo.terminalTabs.collect { tabs ->
                _uiState.update { current ->
                    current.copy(
                        tabs = tabs,
                        activeTabId = current.activeTabId ?: tabs.firstOrNull()?.id
                    )
                }
            }
        }
    }

    /** Opens an SSH tab and returns its tab id. */
    fun openHostSshTab(host: TermixHost, tmuxSession: String? = null): String {
        val tabId = UUID.randomUUID().toString()
        val target = TerminalTarget.SshHost(
            hostId = host.id,
            hostName = host.displayName,
            tmuxSession = tmuxSession,
        )
        val tab = TerminalTabState(
            id = tabId,
            title = target.defaultTitle,
            target = target,
            state = TerminalConnectionState.Connecting,
        )
        workspaceRepo.upsertTerminalTab(tab)
        selectTab(tabId)

        viewModelScope.launch {
            transport.connect(
                TerminalSessionConfig(
                    tabId = tabId,
                    target = target,
                    hostIp = host.ip,
                    hostPort = host.port,
                    hostUsername = host.username,
                )
            )
        }
        return tabId
    }

    /** Opens a docker exec console tab and returns its tab id. */
    fun openDockerConsoleTab(hostId: Long, hostName: String, containerId: String, containerName: String): String {
        val tabId = UUID.randomUUID().toString()
        val target = TerminalTarget.DockerConsole(
            hostId = hostId,
            hostName = hostName,
            containerId = containerId,
            containerName = containerName,
        )
        val tab = TerminalTabState(
            id = tabId,
            title = target.defaultTitle,
            target = target,
            state = TerminalConnectionState.Connecting,
        )
        workspaceRepo.upsertTerminalTab(tab)
        selectTab(tabId)

        viewModelScope.launch {
            transport.connect(
                TerminalSessionConfig(
                    tabId = tabId,
                    target = target,
                )
            )
        }
        return tabId
    }

    fun selectTab(tabId: String) {
        val previous = _uiState.value.activeTabId
        _uiState.update { it.copy(activeTabId = tabId) }
        workspaceRepo.selectTerminalTab(tabId)
        if (previous != tabId && windowFocused) {
            lastSize?.let { (cols, rows) -> applySize(tabId, cols, rows) }
        }
    }

    fun setWindowFocused(focused: Boolean) {
        val gained = focused && !windowFocused
        windowFocused = focused
        if (gained) {
            val activeId = _uiState.value.activeTabId ?: return
            lastSize?.let { (cols, rows) -> applySize(activeId, cols, rows) }
        }
    }

    private fun applySize(tabId: String, cols: Int, rows: Int) {
        bufferRegistry.resize(tabId, cols, rows)
        viewModelScope.launch {
            transport.resize(tabId, cols, rows)
        }
    }

    fun closeTab(tabId: String) {
        viewModelScope.launch {
            transport.disconnect(tabId)
            bufferRegistry.remove(tabId)
            _uiState.update { current ->
                val remaining = current.tabs.filterNot { it.id == tabId }
                val nextActive = if (current.activeTabId == tabId) remaining.firstOrNull()?.id else current.activeTabId
                val newBuffers = current.buffers - tabId
                val newSnapshots = current.snapshots - tabId
                current.copy(
                    tabs = remaining,
                    activeTabId = nextActive,
                    buffers = newBuffers,
                    snapshots = newSnapshots,
                )
            }
            workspaceRepo.closeTerminalTab(tabId)
        }
    }

    fun sendInput(data: String) {
        val activeId = _uiState.value.activeTabId ?: return
        viewModelScope.launch {
            val toSend = Vt100KeyEncoder.encodeModifiedInput(
                input = data,
                ctrlActive = _uiState.value.isCtrlActive,
                altActive = _uiState.value.isAltActive,
            )
            _uiState.update { it.copy(isCtrlActive = false, isAltActive = false) }
            transport.sendInput(activeId, toSend)
        }
    }

    fun sendRawInput(raw: String) {
        if (raw.isEmpty()) return
        val activeId = _uiState.value.activeTabId ?: return
        viewModelScope.launch {
            transport.sendInput(activeId, raw)
        }
    }

    fun sendSpecialKey(key: SpecialTerminalKey) {
        val activeId = _uiState.value.activeTabId ?: return
        viewModelScope.launch {
            when (key) {
                SpecialTerminalKey.ESC -> transport.sendInput(activeId, Vt100KeyEncoder.encodeSpecial(Vt100SpecialKey.ESC))
                SpecialTerminalKey.TAB -> transport.sendInput(activeId, Vt100KeyEncoder.encodeSpecial(Vt100SpecialKey.TAB))
                SpecialTerminalKey.CTRL_C -> transport.sendInput(activeId, Vt100KeyEncoder.encodeSpecial(Vt100SpecialKey.CTRL_C))
                SpecialTerminalKey.UP -> transport.sendInput(activeId, Vt100KeyEncoder.encodeArrow(Vt100ArrowKey.UP))
                SpecialTerminalKey.DOWN -> transport.sendInput(activeId, Vt100KeyEncoder.encodeArrow(Vt100ArrowKey.DOWN))
                SpecialTerminalKey.LEFT -> transport.sendInput(activeId, Vt100KeyEncoder.encodeArrow(Vt100ArrowKey.LEFT))
                SpecialTerminalKey.RIGHT -> transport.sendInput(activeId, Vt100KeyEncoder.encodeArrow(Vt100ArrowKey.RIGHT))
                SpecialTerminalKey.ENTER -> transport.sendInput(activeId, Vt100KeyEncoder.encodeSpecial(Vt100SpecialKey.ENTER))
                SpecialTerminalKey.TOGGLE_CTRL -> _uiState.update { it.copy(isCtrlActive = !it.isCtrlActive) }
                SpecialTerminalKey.TOGGLE_ALT -> _uiState.update { it.copy(isAltActive = !it.isAltActive) }
                SpecialTerminalKey.TMUX_ATTACH -> transport.attachTmux(activeId, "helix")
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        val activeId = _uiState.value.activeTabId ?: return
        val clampedCols = cols.coerceAtLeast(10)
        val clampedRows = rows.coerceAtLeast(5)
        // Remember this window's size even while unfocused; it is applied once focus is gained.
        lastSize = clampedCols to clampedRows
        if (!windowFocused) return
        applySize(activeId, clampedCols, clampedRows)
    }

    companion object {
        fun provideFactory(
            transport: TerminalTransport,
            workspaceRepo: WorkspaceTreeRepository,
            bufferRegistry: TerminalBufferRegistry = TerminalBufferRegistry.shared,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TerminalViewModel(transport, workspaceRepo, bufferRegistry) as T
            }
        }
    }
}

enum class SpecialTerminalKey {
    ESC, TAB, CTRL_C, UP, DOWN, LEFT, RIGHT, ENTER, TOGGLE_CTRL, TOGGLE_ALT, TMUX_ATTACH
}
