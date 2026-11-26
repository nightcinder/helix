package com.nightcinder.helix.ui

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
import com.nightcinder.helix.core.model.TerminalConnectionState
import com.nightcinder.helix.core.model.TerminalSessionConfig
import com.nightcinder.helix.core.model.TerminalTabState
import com.nightcinder.helix.core.model.TerminalTarget
import com.nightcinder.helix.core.model.TermixApiException
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.terminal.TerminalEvent
import com.nightcinder.helix.core.terminal.TerminalTransport
import com.nightcinder.helix.ui.state.SpecialTerminalKey
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.WorkspaceDialog
import com.nightcinder.helix.ui.state.WorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeTerminalTransport : TerminalTransport {
    val _events = MutableSharedFlow<TerminalEvent>()
    override val events: Flow<TerminalEvent> = _events

    var lastConnectedConfig: TerminalSessionConfig? = null
    var lastSentInput: Pair<String, String>? = null
    var lastResized: Triple<String, Int, Int>? = null
    var disconnectedTabId: String? = null

    override suspend fun connect(config: TerminalSessionConfig) {
        lastConnectedConfig = config
        _events.emit(TerminalEvent.StateChanged(config.tabId, TerminalConnectionState.Connected))
    }

    override suspend fun sendInput(tabId: String, data: String) {
        lastSentInput = Pair(tabId, data)
    }

    override suspend fun resize(tabId: String, cols: Int, rows: Int) {
        lastResized = Triple(tabId, cols, rows)
    }

    override suspend fun attachTmux(tabId: String, session: String) {
        lastSentInput = Pair(tabId, "tmux attach -t $session\r")
    }

    override suspend fun detachTmux(tabId: String) {
        lastSentInput = Pair(tabId, "\u0002d")
    }

    override suspend fun disconnect(tabId: String) {
        disconnectedTabId = tabId
        _events.emit(TerminalEvent.StateChanged(tabId, TerminalConnectionState.Disconnected))
    }
}

class FakeWorkspaceTreeRepository : WorkspaceTreeRepository {
    val _dockerSessionsByHost = MutableStateFlow<Map<Long, DockerSshSession>>(emptyMap())
    override val dockerSessionsByHost: StateFlow<Map<Long, DockerSshSession>> = _dockerSessionsByHost.asStateFlow()

    val _stacksByHost = MutableStateFlow<Map<Long, List<DockerStack>>>(emptyMap())
    override val stacksByHost: StateFlow<Map<Long, List<DockerStack>>> = _stacksByHost.asStateFlow()

    val _containersByHost = MutableStateFlow<Map<Long, List<DockerContainer>>>(emptyMap())
    override val containersByHost: StateFlow<Map<Long, List<DockerContainer>>> = _containersByHost.asStateFlow()

    val _containerLogs = MutableStateFlow<Map<String, DockerLogsResult>>(emptyMap())
    override val containerLogs: StateFlow<Map<String, DockerLogsResult>> = _containerLogs.asStateFlow()

    val _sftpSessionsByHost = MutableStateFlow<Map<Long, SftpSession>>(emptyMap())
    override val sftpSessionsByHost: StateFlow<Map<Long, SftpSession>> = _sftpSessionsByHost.asStateFlow()

    val _sftpCurrentPathByHost = MutableStateFlow<Map<Long, String>>(emptyMap())
    override val sftpCurrentPathByHost: StateFlow<Map<Long, String>> = _sftpCurrentPathByHost.asStateFlow()

    val _sftpEntriesByHost = MutableStateFlow<Map<Long, List<SftpEntry>>>(emptyMap())
    override val sftpEntriesByHost: StateFlow<Map<Long, List<SftpEntry>>> = _sftpEntriesByHost.asStateFlow()

    val _openFileContent = MutableStateFlow<SftpFileContent?>(null)
    override val openFileContent: StateFlow<SftpFileContent?> = _openFileContent.asStateFlow()

    val _terminalTabs = MutableStateFlow<List<TerminalTabState>>(emptyList())
    override val terminalTabs: StateFlow<List<TerminalTabState>> = _terminalTabs.asStateFlow()

    val _activeTerminalTabId = MutableStateFlow<String?>(null)
    override val activeTerminalTabId: StateFlow<String?> = _activeTerminalTabId.asStateFlow()

    val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val _lastError = MutableStateFlow<TermixApiException?>(null)
    override val lastError: StateFlow<TermixApiException?> = _lastError.asStateFlow()

    var lastContainerAction: Triple<Long, String, DockerContainerAction>? = null
    var lastWrittenFile: Triple<Long, String, String>? = null

    override suspend fun connectHostWorkspace(hostId: Long): Result<DockerSshSession> {
        val session = DockerSshSession(sessionId = "sess_$hostId", hostId = hostId)
        _dockerSessionsByHost.value = _dockerSessionsByHost.value + (hostId to session)
        return Result.success(session)
    }

    override suspend fun refreshDockerTree(hostId: Long): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun performContainerAction(hostId: Long, containerId: String, action: DockerContainerAction): Result<DockerActionResult> {
        lastContainerAction = Triple(hostId, containerId, action)
        return Result.success(DockerActionResult(success = true))
    }

    override suspend fun fetchContainerLogs(hostId: Long, containerId: String, tail: Int): Result<DockerLogsResult> {
        val result = DockerLogsResult(containerId = containerId, logs = "Container log sample line\n")
        return Result.success(result)
    }

    override suspend fun performStackAction(hostId: Long, stackName: String, action: DockerStackAction): Result<DockerActionResult> {
        return Result.success(DockerActionResult(success = true))
    }

    override suspend fun getStackCompose(hostId: Long, stackName: String): Result<DockerComposeFile> {
        return Result.success(DockerComposeFile(stackName = stackName, content = "services:\n  app:\n    image: nginx"))
    }

    override suspend fun updateStackCompose(hostId: Long, stackName: String, content: String): Result<DockerComposeFile> {
        return Result.success(DockerComposeFile(stackName = stackName, content = content))
    }

    override suspend fun browseSftpDirectory(hostId: Long, path: String): Result<List<SftpEntry>> {
        val entries = listOf(SftpEntry(name = "test.txt", path = "$path/test.txt", isDirectory = false, sizeBytes = 1024))
        _sftpEntriesByHost.value = _sftpEntriesByHost.value + (hostId to entries)
        _sftpCurrentPathByHost.value = _sftpCurrentPathByHost.value + (hostId to path)
        return Result.success(entries)
    }

    override suspend fun readSftpFile(hostId: Long, path: String): Result<SftpFileContent> {
        val content = SftpFileContent(path = path, content = "Hello Helix SFTP")
        _openFileContent.value = content
        return Result.success(content)
    }

    override suspend fun writeSftpFile(hostId: Long, path: String, content: String): Result<SftpTransferResult> {
        lastWrittenFile = Triple(hostId, path, content)
        return Result.success(SftpTransferResult(success = true, sourcePath = path, targetPath = path))
    }

    override suspend fun transferSftpFileToHost(sourceHostId: Long, sourcePath: String, targetHostId: Long, targetPath: String): Result<SftpTransferResult> {
        return Result.success(SftpTransferResult(success = true))
    }

    override fun upsertTerminalTab(tab: TerminalTabState) {
        val updated = _terminalTabs.value.filterNot { it.id == tab.id } + tab
        _terminalTabs.value = updated
    }

    override fun selectTerminalTab(tabId: String) {
        _activeTerminalTabId.value = tabId
    }

    override fun closeTerminalTab(tabId: String) {
        _terminalTabs.value = _terminalTabs.value.filterNot { it.id == tabId }
    }

    override fun clearWorkspace() {
        _dockerSessionsByHost.value = emptyMap()
        _stacksByHost.value = emptyMap()
        _containersByHost.value = emptyMap()
        _sftpEntriesByHost.value = emptyMap()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class Phase2ViewModelsTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transport: FakeTerminalTransport
    private lateinit var workspaceRepo: FakeWorkspaceTreeRepository
    private lateinit var terminalViewModel: TerminalViewModel
    private lateinit var workspaceViewModel: WorkspaceViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        transport = FakeTerminalTransport()
        workspaceRepo = FakeWorkspaceTreeRepository()
        terminalViewModel = TerminalViewModel(transport, workspaceRepo)
        workspaceViewModel = WorkspaceViewModel(workspaceRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun terminalViewModel_openHostSshTab_connectsAndSelects() = runTest(testDispatcher) {
        val host = TermixHost(id = 1L, name = "core", ip = "192.168.1.100")
        terminalViewModel.openHostSshTab(host)
        advanceUntilIdle()

        val activeTab = terminalViewModel.uiState.value.activeTab
        assertNotNull(activeTab)
        assertEquals("core", activeTab?.title)
        assertNotNull(transport.lastConnectedConfig)
        assertEquals(host.id, (transport.lastConnectedConfig?.target as? TerminalTarget.SshHost)?.hostId)
    }

    @Test
    fun terminalViewModel_sendsSpecialKeysCorrectly() = runTest(testDispatcher) {
        val host = TermixHost(id = 2L, name = "desktop", ip = "192.168.1.101")
        terminalViewModel.openHostSshTab(host)
        advanceUntilIdle()

        val tabId = terminalViewModel.uiState.value.activeTabId!!
        terminalViewModel.sendSpecialKey(SpecialTerminalKey.CTRL_C)
        advanceUntilIdle()

        assertEquals(Pair(tabId, "\u0003"), transport.lastSentInput)
    }

    @Test
    fun workspaceViewModel_selectHost_loadsWorkspaceAndSftp() = runTest(testDispatcher) {
        workspaceViewModel.selectHost(1L)
        advanceUntilIdle()

        assertEquals(1L, workspaceViewModel.uiState.value.selectedHostId)
        assertEquals("/", workspaceViewModel.uiState.value.sftpCurrentPath)
        assertEquals(1, workspaceViewModel.uiState.value.sftpEntries.size)
        assertEquals("test.txt", workspaceViewModel.uiState.value.sftpEntries[0].name)
    }

    @Test
    fun workspaceViewModel_performContainerAction_triggersRepo() = runTest(testDispatcher) {
        workspaceViewModel.selectHost(1L)
        advanceUntilIdle()

        workspaceViewModel.performContainerAction("c_123", DockerContainerAction.RESTART)
        advanceUntilIdle()

        assertEquals(Triple(1L, "c_123", DockerContainerAction.RESTART), workspaceRepo.lastContainerAction)
    }

    @Test
    fun workspaceViewModel_openFile_updatesDialogState() = runTest(testDispatcher) {
        workspaceViewModel.selectHost(1L)
        advanceUntilIdle()

        val entry = SftpEntry(name = "test.txt", path = "/test.txt")
        workspaceViewModel.openFile(entry)
        advanceUntilIdle()

        val dialog = workspaceViewModel.uiState.value.activeDialog as? WorkspaceDialog.EditFile
        assertNotNull(dialog)
        assertEquals("Hello Helix SFTP", dialog?.content)
    }
}
