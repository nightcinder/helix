package com.nightcinder.helix.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nightcinder.helix.core.api.WorkspaceTreeRepository
import com.nightcinder.helix.core.model.DockerActionResult
import com.nightcinder.helix.core.model.DockerComposeFile
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerContainerAction
import com.nightcinder.helix.core.model.DockerLogsResult
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.DockerStackAction
import com.nightcinder.helix.core.model.SftpEntry
import com.nightcinder.helix.core.model.SftpFileContent
import com.nightcinder.helix.core.model.TermixHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val selectedHostId: Long? = null,
    val stacks: List<DockerStack> = emptyList(),
    val containers: List<DockerContainer> = emptyList(),
    val selectedContainerLogs: DockerLogsResult? = null,
    val selectedComposeFile: DockerComposeFile? = null,
    val sftpCurrentPath: String = "/",
    val sftpEntries: List<SftpEntry> = emptyList(),
    val openFileContent: SftpFileContent? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val activeDialog: WorkspaceDialog? = null,
)

sealed class WorkspaceDialog {
    data class ContainerLogs(val container: DockerContainer) : WorkspaceDialog()
    data class EditCompose(val stackName: String, val content: String) : WorkspaceDialog()
    data class EditFile(val entry: SftpEntry, val content: String) : WorkspaceDialog()
}

class WorkspaceViewModel(
    private val workspaceRepo: WorkspaceTreeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        observeWorkspaceData()
    }

    private fun observeWorkspaceData() {
        viewModelScope.launch {
            workspaceRepo.stacksByHost.collect { stacksMap ->
                val currentHostId = _uiState.value.selectedHostId
                _uiState.update { current ->
                    current.copy(stacks = currentHostId?.let { stacksMap[it] } ?: emptyList())
                }
            }
        }
        viewModelScope.launch {
            workspaceRepo.containersByHost.collect { containersMap ->
                val currentHostId = _uiState.value.selectedHostId
                _uiState.update { current ->
                    current.copy(containers = currentHostId?.let { containersMap[it] } ?: emptyList())
                }
            }
        }
        viewModelScope.launch {
            workspaceRepo.sftpEntriesByHost.collect { entriesMap ->
                val currentHostId = _uiState.value.selectedHostId
                _uiState.update { current ->
                    current.copy(sftpEntries = currentHostId?.let { entriesMap[it] } ?: emptyList())
                }
            }
        }
        viewModelScope.launch {
            workspaceRepo.sftpCurrentPathByHost.collect { pathMap ->
                val currentHostId = _uiState.value.selectedHostId
                _uiState.update { current ->
                    current.copy(sftpCurrentPath = currentHostId?.let { pathMap[it] } ?: "/")
                }
            }
        }
        viewModelScope.launch {
            workspaceRepo.openFileContent.collect { fileContent ->
                _uiState.update { it.copy(openFileContent = fileContent) }
            }
        }
        viewModelScope.launch {
            workspaceRepo.isLoading.collect { loading ->
                _uiState.update { it.copy(isLoading = loading) }
            }
        }
        viewModelScope.launch {
            workspaceRepo.lastError.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(errorMessage = error.message) }
                }
            }
        }
    }

    fun selectHost(hostId: Long) {
        _uiState.update { it.copy(selectedHostId = hostId) }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            workspaceRepo.connectHostWorkspace(hostId)
            workspaceRepo.refreshDockerTree(hostId)
            workspaceRepo.browseSftpDirectory(hostId, "/")
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun performContainerAction(containerId: String, action: DockerContainerAction) {
        val hostId = _uiState.value.selectedHostId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = workspaceRepo.performContainerAction(hostId, containerId, action)
            _uiState.update { it.copy(isLoading = false) }
            result.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun viewContainerLogs(container: DockerContainer) {
        val hostId = _uiState.value.selectedHostId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = workspaceRepo.fetchContainerLogs(hostId, container.id, tail = 200)
            _uiState.update { it.copy(isLoading = false) }
            result.fold(
                onSuccess = { logs ->
                    _uiState.update {
                        it.copy(
                            selectedContainerLogs = logs,
                            activeDialog = WorkspaceDialog.ContainerLogs(container)
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message) }
                }
            )
        }
    }

    fun performStackAction(stackName: String, action: DockerStackAction) {
        val hostId = _uiState.value.selectedHostId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = workspaceRepo.performStackAction(hostId, stackName, action)
            _uiState.update { it.copy(isLoading = false) }
            result.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun browseDirectory(path: String) {
        val hostId = _uiState.value.selectedHostId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = workspaceRepo.browseSftpDirectory(hostId, path)
            _uiState.update { it.copy(isLoading = false) }
            result.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun openFile(entry: SftpEntry) {
        val hostId = _uiState.value.selectedHostId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = workspaceRepo.readSftpFile(hostId, entry.path)
            _uiState.update { it.copy(isLoading = false) }
            result.fold(
                onSuccess = { content ->
                    _uiState.update {
                        it.copy(
                            openFileContent = content,
                            activeDialog = WorkspaceDialog.EditFile(entry, content.content)
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message) }
                }
            )
        }
    }

    fun saveFile(path: String, content: String) {
        val hostId = _uiState.value.selectedHostId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = workspaceRepo.writeSftpFile(hostId, path, content)
            _uiState.update { it.copy(isLoading = false) }
            result.fold(
                onSuccess = {
                    dismissDialog()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message) }
                }
            )
        }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(activeDialog = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun provideFactory(
            workspaceRepo: WorkspaceTreeRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkspaceViewModel(workspaceRepo) as T
            }
        }
    }
}
