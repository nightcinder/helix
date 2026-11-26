package com.nightcinder.helix.core.api

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
import kotlinx.coroutines.flow.StateFlow

/**
 * Termix v26.11.2 Docker Manager REST contract (`/plugin-api/docker/...`).
 *
 * Mutating requests must include `X-Electron-App: true` and map `401` / `DATA_EXPIRED`
 * responses to [TermixApiException.DataExpired].
 */
interface DockerApi {
    suspend fun connectSsh(hostId: Long): DockerSshSession

    suspend fun keepaliveSsh(sessionId: String): Boolean

    suspend fun listContainers(
        sessionId: String,
        all: Boolean = true,
    ): List<DockerContainer>

    suspend fun containerAction(
        sessionId: String,
        containerId: String,
        action: DockerContainerAction,
    ): DockerActionResult

    suspend fun getContainerLogs(
        sessionId: String,
        containerId: String,
        tail: Int = 200,
    ): DockerLogsResult

    suspend fun listStacks(sessionId: String): List<DockerStack>

    /** Container names of one stack's services (`compose ps`), used when names don't follow compose naming. */
    suspend fun listStackContainerNames(sessionId: String, stackName: String): List<String> = emptyList()

    suspend fun getStackCompose(
        sessionId: String,
        stackName: String,
    ): DockerComposeFile

    suspend fun updateStackCompose(
        sessionId: String,
        stackName: String,
        content: String,
    ): DockerComposeFile

    suspend fun stackAction(
        sessionId: String,
        stackName: String,
        action: DockerStackAction,
    ): DockerActionResult
}

/**
 * Termix v26.11.2 File Manager (SFTP) REST contract (`/plugin-api/file-manager/...`).
 *
 * Mutating requests must include `X-Electron-App: true` and map `401` / `DATA_EXPIRED`
 * responses to [TermixApiException.DataExpired].
 */
interface SftpApi {
    suspend fun connect(hostId: Long): SftpSession

    suspend fun listFiles(
        sessionId: String,
        path: String = "/",
    ): List<SftpEntry>

    suspend fun readFile(
        sessionId: String,
        path: String,
    ): SftpFileContent

    suspend fun writeFile(
        sessionId: String,
        path: String,
        content: String,
    ): SftpTransferResult

    suspend fun downloadFile(
        sessionId: String,
        path: String,
    ): ByteArray

    suspend fun uploadFile(
        sessionId: String,
        path: String,
        bytes: ByteArray,
    ): SftpTransferResult

    suspend fun transferToHost(
        sourceSessionId: String,
        sourcePath: String,
        targetHostId: Long,
        targetPath: String,
    ): SftpTransferResult
}

/**
 * Reactive XPipe-style hierarchical workspace repository managing per-host Docker SSH sessions,
 * Compose stacks, containers, logs, SFTP directory browsing/editing, and active terminal tabs.
 */
interface WorkspaceTreeRepository {
    val dockerSessionsByHost: StateFlow<Map<Long, DockerSshSession>>
    val stacksByHost: StateFlow<Map<Long, List<DockerStack>>>
    val containersByHost: StateFlow<Map<Long, List<DockerContainer>>>
    val containerLogs: StateFlow<Map<String, DockerLogsResult>>
    val sftpSessionsByHost: StateFlow<Map<Long, SftpSession>>
    val sftpCurrentPathByHost: StateFlow<Map<Long, String>>
    val sftpEntriesByHost: StateFlow<Map<Long, List<SftpEntry>>>
    val openFileContent: StateFlow<SftpFileContent?>
    val terminalTabs: StateFlow<List<TerminalTabState>>
    val activeTerminalTabId: StateFlow<String?>
    val isLoading: StateFlow<Boolean>
    val lastError: StateFlow<TermixApiException?>

    suspend fun connectHostWorkspace(hostId: Long): Result<DockerSshSession>

    suspend fun refreshDockerTree(hostId: Long): Result<Unit>

    suspend fun performContainerAction(
        hostId: Long,
        containerId: String,
        action: DockerContainerAction,
    ): Result<DockerActionResult>

    suspend fun fetchContainerLogs(
        hostId: Long,
        containerId: String,
        tail: Int = 200,
    ): Result<DockerLogsResult>

    suspend fun performStackAction(
        hostId: Long,
        stackName: String,
        action: DockerStackAction,
    ): Result<DockerActionResult>

    suspend fun getStackCompose(
        hostId: Long,
        stackName: String,
    ): Result<DockerComposeFile>

    suspend fun updateStackCompose(
        hostId: Long,
        stackName: String,
        content: String,
    ): Result<DockerComposeFile>

    suspend fun browseSftpDirectory(
        hostId: Long,
        path: String = "/",
    ): Result<List<SftpEntry>>

    suspend fun readSftpFile(
        hostId: Long,
        path: String,
    ): Result<SftpFileContent>

    suspend fun writeSftpFile(
        hostId: Long,
        path: String,
        content: String,
    ): Result<SftpTransferResult>

    suspend fun transferSftpFileToHost(
        sourceHostId: Long,
        sourcePath: String,
        targetHostId: Long,
        targetPath: String,
    ): Result<SftpTransferResult>

    fun upsertTerminalTab(tab: TerminalTabState)

    fun selectTerminalTab(tabId: String)

    fun closeTerminalTab(tabId: String)

    fun clearWorkspace()
}
