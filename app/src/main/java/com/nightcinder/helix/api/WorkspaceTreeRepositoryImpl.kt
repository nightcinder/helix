package com.nightcinder.helix.api

import com.nightcinder.helix.core.api.DockerApi
import com.nightcinder.helix.core.api.SftpApi
import com.nightcinder.helix.core.api.WorkspaceTreeRepository
import com.nightcinder.helix.core.model.DockerActionResult
import com.nightcinder.helix.core.model.DockerComposeFile
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerContainerAction
import com.nightcinder.helix.core.model.DockerLogsResult
import com.nightcinder.helix.core.model.DockerSshSession
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.DockerStackAction
import com.nightcinder.helix.core.model.SftpEntry
import com.nightcinder.helix.core.model.SftpFileContent
import com.nightcinder.helix.core.model.SftpSession
import com.nightcinder.helix.core.model.SftpTransferResult
import com.nightcinder.helix.core.model.TerminalTabState
import com.nightcinder.helix.core.model.TermixApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Production implementation of [WorkspaceTreeRepository] backed by [DockerApi] and [SftpApi].
 *
 * Manages per-host Docker SSH sessions, hierarchical stacks & containers, container logs,
 * SFTP directory browsing & file editing, and active terminal tab state.
 */
class WorkspaceTreeRepositoryImpl(
    private val dockerApi: DockerApi,
    private val sftpApi: SftpApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WorkspaceTreeRepository {

    private val _dockerSessionsByHost = MutableStateFlow<Map<Long, DockerSshSession>>(emptyMap())
    override val dockerSessionsByHost: StateFlow<Map<Long, DockerSshSession>> =
        _dockerSessionsByHost.asStateFlow()

    private val _stacksByHost = MutableStateFlow<Map<Long, List<DockerStack>>>(emptyMap())
    override val stacksByHost: StateFlow<Map<Long, List<DockerStack>>> =
        _stacksByHost.asStateFlow()

    private val _containersByHost = MutableStateFlow<Map<Long, List<DockerContainer>>>(emptyMap())
    override val containersByHost: StateFlow<Map<Long, List<DockerContainer>>> =
        _containersByHost.asStateFlow()

    private val _containerLogs = MutableStateFlow<Map<String, DockerLogsResult>>(emptyMap())
    override val containerLogs: StateFlow<Map<String, DockerLogsResult>> =
        _containerLogs.asStateFlow()

    private val _sftpSessionsByHost = MutableStateFlow<Map<Long, SftpSession>>(emptyMap())
    override val sftpSessionsByHost: StateFlow<Map<Long, SftpSession>> =
        _sftpSessionsByHost.asStateFlow()

    private val _sftpCurrentPathByHost = MutableStateFlow<Map<Long, String>>(emptyMap())
    override val sftpCurrentPathByHost: StateFlow<Map<Long, String>> =
        _sftpCurrentPathByHost.asStateFlow()

    private val _sftpEntriesByHost = MutableStateFlow<Map<Long, List<SftpEntry>>>(emptyMap())
    override val sftpEntriesByHost: StateFlow<Map<Long, List<SftpEntry>>> =
        _sftpEntriesByHost.asStateFlow()

    private val _openFileContent = MutableStateFlow<SftpFileContent?>(null)
    override val openFileContent: StateFlow<SftpFileContent?> = _openFileContent.asStateFlow()

    private val _terminalTabs = MutableStateFlow<List<TerminalTabState>>(emptyList())
    override val terminalTabs: StateFlow<List<TerminalTabState>> = _terminalTabs.asStateFlow()

    private val _activeTerminalTabId = MutableStateFlow<String?>(null)
    override val activeTerminalTabId: StateFlow<String?> = _activeTerminalTabId.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<TermixApiException?>(null)
    override val lastError: StateFlow<TermixApiException?> = _lastError.asStateFlow()

    override suspend fun connectHostWorkspace(hostId: Long): Result<DockerSshSession> =
        withContext(ioDispatcher) {
            try {
                val session = dockerApi.connectSsh(hostId).let {
                    if (it.hostId == 0L) it.copy(hostId = hostId) else it
                }
                _dockerSessionsByHost.update { it + (hostId to session) }
                _lastError.value = null
                Result.success(session)
            } catch (t: Throwable) {
                val apiEx = toTermixApiException(t)
                _lastError.value = apiEx
                Result.failure(apiEx)
            }
        }

    suspend fun keepaliveDockerSession(hostId: Long): Result<Boolean> =
        withContext(ioDispatcher) {
            try {
                val session = ensureDockerSession(hostId)
                val alive = dockerApi.keepaliveSsh(session.sessionId)
                Result.success(alive)
            } catch (t: Throwable) {
                val apiEx = toTermixApiException(t)
                _lastError.value = apiEx
                Result.failure(apiEx)
            }
        }

    override suspend fun refreshDockerTree(hostId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            _isLoading.value = true
            try {
                val session = ensureDockerSession(hostId)
                coroutineScope {
                    val containersDeferred = async { dockerApi.listContainers(session.sessionId, all = true) }
                    val stacksDeferred = async { runCatching { dockerApi.listStacks(session.sessionId) } }

                    val rawContainers = containersDeferred.await().map { container ->
                        container.copy(hostId = container.hostId ?: hostId)
                    }
                    val rawStacks = stacksDeferred.await().getOrElse { emptyList() }

                    // Stacks whose containers use container_name (no <project>- prefix) cannot be matched
                    // by name; ask Termix for their service containers (bounded, failures ignored).
                    val prefixMatched = rawStacks.map { it.name }.filter { s ->
                        rawContainers.any { c ->
                            val n = c.name.removePrefix("/")
                            n.startsWith("$s-") || n.startsWith("${s}_")
                        }
                    }.toSet()
                    val needLookup = rawStacks.filter {
                        it.containers.isEmpty() && it.serviceCount > 0 && it.name !in prefixMatched
                    }
                    val gate = kotlinx.coroutines.sync.Semaphore(4)
                    val ownerByContainer: Map<String, String> = needLookup.map { stack ->
                        async {
                            gate.acquire()
                            try {
                                runCatching { dockerApi.listStackContainerNames(session.sessionId, stack.name) }
                                    .getOrDefault(emptyList())
                                    .map { it.removePrefix("/") to stack.name }
                            } finally {
                                gate.release()
                            }
                        }
                    }.flatMap { it.await() }.toMap()
                    val labelledContainers = rawContainers.map { c ->
                        val owner = ownerByContainer[c.name.removePrefix("/")]
                        if (c.stackName.isNullOrBlank() && owner != null) c.copy(stackName = owner) else c
                    }

                    val enrichedStacks = mergeStacksAndContainers(hostId, rawStacks, labelledContainers)
                    // Keep the flat list consistent with the stacks (no stack member shows as standalone).
                    val stackOf = enrichedStacks.flatMap { s -> s.containers.map { it.id to s.name } }.toMap()
                    val finalContainers = labelledContainers.map { c ->
                        if (c.stackName.isNullOrBlank()) stackOf[c.id]?.let { c.copy(stackName = it) } ?: c else c
                    }

                    _containersByHost.update { it + (hostId to finalContainers) }
                    _stacksByHost.update { it + (hostId to enrichedStacks) }
                    _lastError.value = null
                    Result.success(Unit)
                }
            } catch (t: Throwable) {
                val apiEx = toTermixApiException(t)
                _lastError.value = apiEx
                Result.failure(apiEx)
            } finally {
                _isLoading.value = false
            }
        }

    override suspend fun performContainerAction(
        hostId: Long,
        containerId: String,
        action: DockerContainerAction,
    ): Result<DockerActionResult> = withContext(ioDispatcher) {
        try {
            val session = ensureDockerSession(hostId)
            val result = dockerApi.containerAction(session.sessionId, containerId, action)
            // Refresh container/stack state after lifecycle mutation
            refreshDockerTree(hostId)
            _lastError.value = null
            Result.success(result)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun fetchContainerLogs(
        hostId: Long,
        containerId: String,
        tail: Int,
    ): Result<DockerLogsResult> = withContext(ioDispatcher) {
        try {
            val session = ensureDockerSession(hostId)
            val logs = dockerApi.getContainerLogs(session.sessionId, containerId, tail)
            _containerLogs.update { it + (containerId to logs) }
            _lastError.value = null
            Result.success(logs)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun performStackAction(
        hostId: Long,
        stackName: String,
        action: DockerStackAction,
    ): Result<DockerActionResult> = withContext(ioDispatcher) {
        try {
            val session = ensureDockerSession(hostId)
            val result = dockerApi.stackAction(session.sessionId, stackName, action)
            refreshDockerTree(hostId)
            _lastError.value = null
            Result.success(result)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun getStackCompose(
        hostId: Long,
        stackName: String,
    ): Result<DockerComposeFile> = withContext(ioDispatcher) {
        try {
            val session = ensureDockerSession(hostId)
            val compose = dockerApi.getStackCompose(session.sessionId, stackName)
            _lastError.value = null
            Result.success(compose)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun updateStackCompose(
        hostId: Long,
        stackName: String,
        content: String,
    ): Result<DockerComposeFile> = withContext(ioDispatcher) {
        try {
            val session = ensureDockerSession(hostId)
            val updated = dockerApi.updateStackCompose(session.sessionId, stackName, content)
            _lastError.value = null
            Result.success(updated)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun browseSftpDirectory(
        hostId: Long,
        path: String,
    ): Result<List<SftpEntry>> = withContext(ioDispatcher) {
        _isLoading.value = true
        try {
            val session = ensureSftpSession(hostId)
            val targetPath = path.ifBlank { session.homePath.ifBlank { "/" } }
            val entries = resolveSymlinkDirectories(
                sessionId = session.sessionId,
                parentPath = targetPath,
                entries = sftpApi.listFiles(session.sessionId, targetPath),
            )
            _sftpCurrentPathByHost.update { it + (hostId to targetPath) }
            _sftpEntriesByHost.update { it + (hostId to entries) }
            _lastError.value = null
            Result.success(entries)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Termix reports symlinks without their target type. Probe each symlink's resolved target with
     * a listing: when it succeeds the link points at a directory, so mark it as one (navigable).
     * Probe failures (file, dangling link) leave the entry as-is.
     */
    private suspend fun resolveSymlinkDirectories(
        sessionId: String,
        parentPath: String,
        entries: List<SftpEntry>,
    ): List<SftpEntry> = entries.map { entry ->
        if (!entry.isSymlink || entry.isDirectory) {
            entry
        } else {
            val isDirectoryTarget = try {
                sftpApi.listFiles(sessionId, entry.resolvedLinkPath(parentPath))
                true
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            if (isDirectoryTarget) entry.copy(isDirectory = true) else entry
        }
    }

    override suspend fun readSftpFile(
        hostId: Long,
        path: String,
    ): Result<SftpFileContent> = withContext(ioDispatcher) {
        try {
            val session = ensureSftpSession(hostId)
            val fileContent = sftpApi.readFile(session.sessionId, path)
            _openFileContent.value = fileContent
            _lastError.value = null
            Result.success(fileContent)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun writeSftpFile(
        hostId: Long,
        path: String,
        content: String,
    ): Result<SftpTransferResult> = withContext(ioDispatcher) {
        try {
            val session = ensureSftpSession(hostId)
            val transfer = sftpApi.writeFile(session.sessionId, path, content)
            _openFileContent.value = SftpFileContent(
                path = path,
                content = content,
                sizeBytes = content.encodeToByteArray().size.toLong(),
            )
            _lastError.value = null
            Result.success(transfer)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun transferSftpFileToHost(
        sourceHostId: Long,
        sourcePath: String,
        targetHostId: Long,
        targetPath: String,
    ): Result<SftpTransferResult> = withContext(ioDispatcher) {
        try {
            val sourceSession = ensureSftpSession(sourceHostId)
            val result = sftpApi.transferToHost(
                sourceSessionId = sourceSession.sessionId,
                sourcePath = sourcePath,
                targetHostId = targetHostId,
                targetPath = targetPath,
            )
            _lastError.value = null
            Result.success(result)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override fun upsertTerminalTab(tab: TerminalTabState) {
        var addedNew = false
        _terminalTabs.update { current ->
            val idx = current.indexOfFirst { it.id == tab.id }
            if (idx >= 0) {
                current.toMutableList().also { it[idx] = tab }
            } else {
                addedNew = true
                current + tab
            }
        }
        if (addedNew || _activeTerminalTabId.value == null) {
            _activeTerminalTabId.value = tab.id
        }
    }

    override fun selectTerminalTab(tabId: String) {
        if (_terminalTabs.value.any { it.id == tabId }) {
            _activeTerminalTabId.value = tabId
        }
    }

    override fun closeTerminalTab(tabId: String) {
        _terminalTabs.update { current ->
            current.filterNot { it.id == tabId }
        }
        if (_activeTerminalTabId.value == tabId) {
            _activeTerminalTabId.value = _terminalTabs.value.lastOrNull()?.id
        }
    }

    override fun clearWorkspace() {
        _dockerSessionsByHost.value = emptyMap()
        _stacksByHost.value = emptyMap()
        _containersByHost.value = emptyMap()
        _containerLogs.value = emptyMap()
        _sftpSessionsByHost.value = emptyMap()
        _sftpCurrentPathByHost.value = emptyMap()
        _sftpEntriesByHost.value = emptyMap()
        _openFileContent.value = null
        _terminalTabs.value = emptyList()
        _activeTerminalTabId.value = null
        _isLoading.value = false
        _lastError.value = null
    }

    // --- Internal Session & Tree Helpers ---

    private suspend fun ensureDockerSession(hostId: Long): DockerSshSession {
        val existing = _dockerSessionsByHost.value[hostId]
        if (existing != null && existing.connected && existing.sessionId.isNotBlank()) {
            return existing
        }
        val created = dockerApi.connectSsh(hostId).let {
            if (it.hostId == 0L) it.copy(hostId = hostId) else it
        }
        _dockerSessionsByHost.update { it + (hostId to created) }
        return created
    }

    private suspend fun ensureSftpSession(hostId: Long): SftpSession {
        val existing = _sftpSessionsByHost.value[hostId]
        if (existing != null && existing.connected && existing.sessionId.isNotBlank()) {
            return existing
        }
        val created = sftpApi.connect(hostId).let {
            if (it.hostId == 0L) it.copy(hostId = hostId) else it
        }
        _sftpSessionsByHost.update { it + (hostId to created) }
        return created
    }

    private fun mergeStacksAndContainers(
        hostId: Long,
        rawStacks: List<DockerStack>,
        containers: List<DockerContainer>,
    ): List<DockerStack> {
        // Termix lists containers via `docker ps --format` without compose labels, so infer the stack
        // from compose's container naming (<project>-<service>-N, or <project>_<service>_N for v1).
        val stackNamesLongestFirst = rawStacks.map { it.name }.filter { it.isNotBlank() }.sortedByDescending { it.length }
        val labelled = containers.map { c ->
            if (!c.stackName.isNullOrBlank()) return@map c
            val bare = c.name.removePrefix("/")
            val owner = stackNamesLongestFirst.firstOrNull { s ->
                bare.startsWith("$s-") || bare.startsWith("${s}_")
            }
            if (owner != null) c.copy(stackName = owner) else c
        }
        val groupedByStack = labelled
            .filter { !it.stackName.isNullOrBlank() }
            .groupBy { it.stackName!!.trim() }

        val knownStackNames = mutableSetOf<String>()
        val result = mutableListOf<DockerStack>()

        for (stack in rawStacks) {
            knownStackNames.add(stack.name)
            val matchedContainers = if (stack.containers.isNotEmpty()) {
                stack.containers.map { it.copy(hostId = it.hostId ?: hostId) }
            } else {
                groupedByStack[stack.name].orEmpty()
            }
            val runningCount = if (matchedContainers.isNotEmpty()) {
                matchedContainers.count { it.isRunning }
            } else {
                stack.runningCount
            }
            val serviceCount = if (matchedContainers.isNotEmpty()) {
                matchedContainers.size
            } else {
                stack.serviceCount
            }
            val status = stack.status.ifBlank {
                when {
                    serviceCount == 0 -> "empty"
                    runningCount == serviceCount -> "running"
                    runningCount > 0 -> "partial"
                    else -> "stopped"
                }
            }
            result.add(
                stack.copy(
                    hostId = stack.hostId ?: hostId,
                    containers = matchedContainers,
                    serviceCount = serviceCount,
                    runningCount = runningCount,
                    status = status,
                )
            )
        }

        // Synthesize stacks discovered via container stackName label that were not in rawStacks
        for ((stackName, stackContainers) in groupedByStack) {
            if (stackName !in knownStackNames) {
                val running = stackContainers.count { it.isRunning }
                val total = stackContainers.size
                val status = when {
                    running == total -> "running"
                    running > 0 -> "partial"
                    else -> "stopped"
                }
                result.add(
                    DockerStack(
                        name = stackName,
                        status = status,
                        serviceCount = total,
                        runningCount = running,
                        containers = stackContainers,
                        hostId = hostId,
                    )
                )
            }
        }

        return result
    }

    private fun toTermixApiException(throwable: Throwable): TermixApiException {
        return when (throwable) {
            is TermixApiException -> throwable
            is java.io.IOException -> TermixApiException.NetworkError(
                message = throwable.message ?: "Network error",
                cause = throwable,
            )
            is kotlinx.serialization.SerializationException -> TermixApiException.ParseError(
                message = throwable.message ?: "JSON serialization error",
                cause = throwable,
            )
            else -> TermixApiException.HttpError(
                httpStatus = -1,
                message = throwable.message ?: "Unexpected error",
            )
        }
    }
}
