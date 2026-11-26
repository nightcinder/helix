package com.nightcinder.helix.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nightcinder.helix.core.api.WorkspaceTreeRepository
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerContainerAction
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.DockerStackAction
import com.nightcinder.helix.core.model.SftpEntry
import com.nightcinder.helix.core.model.TerminalTabState
import com.nightcinder.helix.ui.tree.ContentTab
import com.nightcinder.helix.ui.tree.NodeAction
import com.nightcinder.helix.ui.tree.TabsState
import com.nightcinder.helix.ui.tree.TreeNode
import com.nightcinder.helix.ui.tree.mergeMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogsState(
    val text: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class FileEdit(
    val hostId: Long,
    val path: String,
    val name: String,
    val content: String,
)

data class PendingNodeConfirmation(
    val node: TreeNode,
    val action: NodeAction,
)

data class TreeUiState(
    val expanded: Set<String> = emptySet(),
    val stacksByHost: Map<Long, List<DockerStack>> = emptyMap(),
    val containersByHost: Map<Long, List<DockerContainer>> = emptyMap(),
    val loadingHosts: Set<Long> = emptySet(),
    val tabs: TabsState = TabsState(),
    val logsByTab: Map<String, LogsState> = emptyMap(),
    val sftpPathByHost: Map<Long, String> = emptyMap(),
    val sftpEntriesByHost: Map<Long, List<SftpEntry>> = emptyMap(),
    val fileEdit: FileEdit? = null,
    val streamPoppedOut: Boolean = false,
    val treeVisible: Boolean = true,
    val tabsVisible: Boolean = true,
    val showAlertsModal: Boolean = false,
    val showSessionModal: Boolean = false,
    val pendingNodeConfirmation: PendingNodeConfirmation? = null,
    val message: String? = null,
    val hostFilter: String = "",
)

/**
 * State for the concept-A cockpit: expanded tree nodes, per-host Docker/SFTP data (straight from
 * [WorkspaceTreeRepository]) and the open content tabs.
 */
class TreeViewModel(
    private val workspaceRepo: WorkspaceTreeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TreeUiState())
    val uiState: StateFlow<TreeUiState> = _uiState.asStateFlow()

    /** Terminal ids ever seen in the terminal ViewModel; only those may be dropped by [syncTerminalTabs]. */
    private var seenTerminalIds: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            workspaceRepo.stacksByHost.collect { map -> _uiState.update { it.copy(stacksByHost = map) } }
        }
        viewModelScope.launch {
            workspaceRepo.containersByHost.collect { map -> _uiState.update { it.copy(containersByHost = map) } }
        }
        viewModelScope.launch {
            workspaceRepo.sftpEntriesByHost.collect { map -> _uiState.update { it.copy(sftpEntriesByHost = map) } }
        }
        viewModelScope.launch {
            workspaceRepo.sftpCurrentPathByHost.collect { map -> _uiState.update { it.copy(sftpPathByHost = map) } }
        }
        viewModelScope.launch {
            workspaceRepo.lastError.collect { error ->
                // Per-action messages wrap this same error; mergeMessage keeps only one of them.
                error?.message?.let { postMessage(it) }
            }
        }
    }

    // --- tree -------------------------------------------------------------------------------

    fun toggleExpanded(key: String) {
        _uiState.update {
            it.copy(expanded = if (key in it.expanded) it.expanded - key else it.expanded + key)
        }
    }

    /** Expands a host node and loads its Docker tree on first expansion. */
    fun expandHost(key: String, hostId: Long) {
        val state = _uiState.value
        val wasExpanded = key in state.expanded
        toggleExpanded(key)
        if (!wasExpanded && state.stacksByHost[hostId] == null && state.containersByHost[hostId] == null) {
            loadHost(hostId)
        }
    }

    fun loadHost(hostId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingHosts = it.loadingHosts + hostId) }
            workspaceRepo.connectHostWorkspace(hostId)
                .onFailure { e -> postMessage("Docker connect failed: ${e.message}") }
            workspaceRepo.refreshDockerTree(hostId)
                .onFailure { e -> postMessage("Docker refresh failed: ${e.message}") }
            _uiState.update { it.copy(loadingHosts = it.loadingHosts - hostId) }
        }
    }

    fun containerAction(hostId: Long, containerId: String, action: DockerContainerAction) {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingHosts = it.loadingHosts + hostId) }
            workspaceRepo.performContainerAction(hostId, containerId, action)
                .onFailure { e -> postMessage("${action.wireName} failed: ${e.message}") }
            workspaceRepo.refreshDockerTree(hostId)
            _uiState.update { it.copy(loadingHosts = it.loadingHosts - hostId) }
        }
    }

    fun stackAction(hostId: Long, stackName: String, action: DockerStackAction) {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingHosts = it.loadingHosts + hostId) }
            workspaceRepo.performStackAction(hostId, stackName, action)
                .onFailure { e -> postMessage("${action.wireName} $stackName failed: ${e.message}") }
            workspaceRepo.refreshDockerTree(hostId)
            _uiState.update { it.copy(loadingHosts = it.loadingHosts - hostId) }
        }
    }

    // --- tabs -------------------------------------------------------------------------------

    fun openTab(tab: ContentTab) {
        _uiState.update { it.copy(tabs = it.tabs.open(tab)) }
    }

    /** Mirrors the terminal ViewModel's tab list into the tree tabs (see [syncTerminalTabs]). */
    fun syncTerminalTabs(terminals: List<TerminalTabState>) {
        _uiState.update {
            val (tabs, seen) = com.nightcinder.helix.ui.tree.syncTerminalTabs(it.tabs, terminals, seenTerminalIds)
            seenTerminalIds = seen
            if (tabs == it.tabs) it else it.copy(tabs = tabs)
        }
    }

    fun selectTab(id: String) {
        _uiState.update { it.copy(tabs = it.tabs.select(id)) }
    }

    fun closeTab(id: String) {
        _uiState.update {
            it.copy(
                tabs = it.tabs.close(id),
                logsByTab = it.logsByTab - id,
                streamPoppedOut = if (id.startsWith("stream:")) false else it.streamPoppedOut,
            )
        }
    }

    // --- logs -------------------------------------------------------------------------------

    fun refreshLogs(tabId: String, hostId: Long, containerId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(logsByTab = it.logsByTab + (tabId to (it.logsByTab[tabId] ?: LogsState()).copy(isLoading = true, error = null)))
            }
            val result = workspaceRepo.fetchContainerLogs(hostId, containerId, tail = 300)
            _uiState.update {
                val next = result.fold(
                    onSuccess = { LogsState(text = it.combinedText) },
                    onFailure = { e -> LogsState(error = e.message ?: "Failed to load logs") },
                )
                it.copy(logsByTab = it.logsByTab + (tabId to next))
            }
        }
    }

    // --- files ------------------------------------------------------------------------------

    fun browse(hostId: Long, path: String) {
        viewModelScope.launch {
            workspaceRepo.browseSftpDirectory(hostId, path)
                .onFailure { e -> postMessage("SFTP: ${e.message}") }
        }
    }

    fun openFile(hostId: Long, entry: SftpEntry) {
        viewModelScope.launch {
            workspaceRepo.readSftpFile(hostId, entry.path).fold(
                onSuccess = { content ->
                    _uiState.update {
                        it.copy(fileEdit = FileEdit(hostId, entry.path, entry.name, content.content))
                    }
                },
                onFailure = { e -> postMessage("Open ${entry.name} failed: ${e.message}") },
            )
        }
    }

    fun saveFile(edit: FileEdit, content: String) {
        viewModelScope.launch {
            workspaceRepo.writeSftpFile(edit.hostId, edit.path, content).fold(
                onSuccess = { dismissFile() },
                onFailure = { e -> postMessage("Save ${edit.name} failed: ${e.message}") },
            )
        }
    }

    fun dismissFile() {
        _uiState.update { it.copy(fileEdit = null) }
    }

    // --- misc -------------------------------------------------------------------------------

    fun setStreamPoppedOut(poppedOut: Boolean) {
        _uiState.update { it.copy(streamPoppedOut = poppedOut) }
    }

    fun toggleTreeVisible() {
        _uiState.update { it.copy(treeVisible = !it.treeVisible) }
    }

    fun setTreeVisible(visible: Boolean) {
        _uiState.update { it.copy(treeVisible = visible) }
    }

    fun toggleTabsVisible() {
        _uiState.update { it.copy(tabsVisible = !it.tabsVisible) }
    }

    fun setTabsVisible(visible: Boolean) {
        _uiState.update { it.copy(tabsVisible = visible) }
    }

    fun setShowAlertsModal(show: Boolean) {
        _uiState.update { it.copy(showAlertsModal = show) }
    }

    fun setShowSessionModal(show: Boolean) {
        _uiState.update { it.copy(showSessionModal = show) }
    }

    fun requestNodeConfirmation(node: TreeNode, action: NodeAction) {
        _uiState.update { it.copy(pendingNodeConfirmation = PendingNodeConfirmation(node, action)) }
    }

    fun dismissNodeConfirmation() {
        _uiState.update { it.copy(pendingNodeConfirmation = null) }
    }

    fun setHostFilter(filter: String) {
        _uiState.update { it.copy(hostFilter = filter) }
    }

    /** Posts a message, folding an equal or wrapped duplicate into the one already shown. */
    fun postMessage(message: String) {
        _uiState.update { it.copy(message = mergeMessage(it.message, message)) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        fun provideFactory(
            workspaceRepo: WorkspaceTreeRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TreeViewModel(workspaceRepo) as T
        }
    }
}
