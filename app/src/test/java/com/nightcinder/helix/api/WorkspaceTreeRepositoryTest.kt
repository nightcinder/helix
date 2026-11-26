package com.nightcinder.helix.api

import com.nightcinder.helix.core.api.DockerApi
import com.nightcinder.helix.core.api.SftpApi
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
import com.nightcinder.helix.core.model.TerminalTabState
import com.nightcinder.helix.core.model.TerminalTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceTreeRepositoryTest {

    private lateinit var fakeDockerApi: FakeDockerApi
    private lateinit var fakeSftpApi: FakeSftpApi
    private lateinit var repository: WorkspaceTreeRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        fakeDockerApi = FakeDockerApi()
        fakeSftpApi = FakeSftpApi()
        repository = WorkspaceTreeRepositoryImpl(
            dockerApi = fakeDockerApi,
            sftpApi = fakeSftpApi,
            ioDispatcher = testDispatcher,
        )
    }

    @Test
    fun refreshDockerTree_autoConnectsSession_and_mergesStacksWithContainers() = runTest(testDispatcher) {
        fakeDockerApi.containers = listOf(
            DockerContainer(
                id = "c-auth-1",
                name = "/authentik-server",
                image = "ghcr.io/goauthentik/server:latest",
                state = "running",
                status = "Up 5 hours",
                stackName = "identity",
            ),
            DockerContainer(
                id = "c-auth-2",
                name = "/authentik-worker",
                image = "ghcr.io/goauthentik/server:latest",
                state = "running",
                status = "Up 5 hours",
                stackName = "identity",
            ),
            DockerContainer(
                id = "c-traefik-1",
                name = "/traefik",
                image = "traefik:v3.2",
                state = "exited",
                status = "Exited (0)",
                stackName = "edge",
            ),
        )
        fakeDockerApi.stacks = listOf(
            DockerStack(name = "identity", path = "/srv/stacks/identity")
        )

        val result = repository.refreshDockerTree(hostId = 1L)
        assertTrue(result.isSuccess)

        val session = repository.dockerSessionsByHost.value[1L]
        assertNotNull(session)
        assertEquals("docker-session-1", session?.sessionId)

        val containers = repository.containersByHost.value[1L].orEmpty()
        assertEquals(3, containers.size)

        val stacks = repository.stacksByHost.value[1L].orEmpty()
        assertEquals(2, stacks.size)

        val identityStack = stacks.first { it.name == "identity" }
        assertEquals(2, identityStack.containers.size)
        assertEquals(2, identityStack.runningCount)
        assertEquals("running", identityStack.status)

        // "edge" stack synthesized from container stackName label
        val edgeStack = stacks.first { it.name == "edge" }
        assertEquals(1, edgeStack.containers.size)
        assertEquals(0, edgeStack.runningCount)
        assertEquals("stopped", edgeStack.status)
    }

    @Test
    fun containerAction_and_fetchLogs_and_stackCompose() = runTest(testDispatcher) {
        val actionResult = repository.performContainerAction(
            hostId = 1L,
            containerId = "c-auth-1",
            action = DockerContainerAction.RESTART,
        )
        assertTrue(actionResult.isSuccess)
        assertEquals("restart", actionResult.getOrThrow().action)
        assertEquals(1, fakeDockerApi.connectCount) // Session reused across refresh

        val logsResult = repository.fetchContainerLogs(
            hostId = 1L,
            containerId = "c-auth-1",
            tail = 50,
        )
        assertTrue(logsResult.isSuccess)
        assertEquals("log line 1\nlog line 2", repository.containerLogs.value["c-auth-1"]?.combinedText)

        val composeRes = repository.updateStackCompose(
            hostId = 1L,
            stackName = "identity",
            content = "services:\n  authentik:\n    image: goauthentik/server:2026.10",
        )
        assertTrue(composeRes.isSuccess)
        assertTrue(composeRes.getOrThrow().content.contains("2026.10"))

        val stackActRes = repository.performStackAction(
            hostId = 1L,
            stackName = "identity",
            action = DockerStackAction.UP,
        )
        assertTrue(stackActRes.isSuccess)
        assertEquals("deploy", stackActRes.getOrThrow().action)
    }

    @Test
    fun sftpBrowseReadAndWrite_managesPerHostSessionAndContent() = runTest(testDispatcher) {
        fakeSftpApi.entriesByPath["/srv"] = listOf(
            SftpEntry(name = "compose.yaml", path = "/srv/compose.yaml", isDirectory = false, sizeBytes = 128),
            SftpEntry(name = "data", path = "/srv/data", isDirectory = true),
        )
        fakeSftpApi.filesByPath["/srv/compose.yaml"] = "services:\n  app:\n    image: alpine"

        val browseRes = repository.browseSftpDirectory(hostId = 2L, path = "/srv")
        assertTrue(browseRes.isSuccess)
        assertEquals("/srv", repository.sftpCurrentPathByHost.value[2L])
        assertEquals(2, repository.sftpEntriesByHost.value[2L]?.size)

        val readRes = repository.readSftpFile(hostId = 2L, path = "/srv/compose.yaml")
        assertTrue(readRes.isSuccess)
        assertEquals("services:\n  app:\n    image: alpine", repository.openFileContent.value?.content)

        val writeRes = repository.writeSftpFile(
            hostId = 2L,
            path = "/srv/compose.yaml",
            content = "services:\n  app:\n    image: alpine:3.20",
        )
        assertTrue(writeRes.isSuccess)
        assertEquals("services:\n  app:\n    image: alpine:3.20", repository.openFileContent.value?.content)
        assertEquals(1, fakeSftpApi.connectCount)

        val transferRes = repository.transferSftpFileToHost(
            sourceHostId = 2L,
            sourcePath = "/srv/compose.yaml",
            targetHostId = 3L,
            targetPath = "/backup/compose.yaml",
        )
        assertTrue(transferRes.isSuccess)
        assertEquals(3L, transferRes.getOrThrow().targetHostId)
    }

    @Test
    fun terminalTabLifecycle_upsertSelectCloseAndClear() {
        val tab1 = TerminalTabState(
            id = "tab-1",
            title = "core",
            target = TerminalTarget.SshHost(hostId = 1, hostName = "core"),
            state = TerminalConnectionState.Connected,
        )
        val tab2 = TerminalTabState(
            id = "tab-2",
            title = "traefik@desktop",
            target = TerminalTarget.DockerConsole(hostId = 2, hostName = "desktop", containerId = "c123", containerName = "traefik"),
            state = TerminalConnectionState.Connecting,
        )

        repository.upsertTerminalTab(tab1)
        assertEquals(1, repository.terminalTabs.value.size)
        assertEquals("tab-1", repository.activeTerminalTabId.value)

        repository.upsertTerminalTab(tab2)
        assertEquals(2, repository.terminalTabs.value.size)
        assertEquals("tab-2", repository.activeTerminalTabId.value)

        repository.selectTerminalTab("tab-1")
        assertEquals("tab-1", repository.activeTerminalTabId.value)

        // Update existing tab-1 without changing count
        repository.upsertTerminalTab(tab1.copy(title = "core [tmux]"))
        assertEquals(2, repository.terminalTabs.value.size)
        assertEquals("core [tmux]", repository.terminalTabs.value.first { it.id == "tab-1" }.title)

        // Closing active tab selects remaining tab
        repository.closeTerminalTab("tab-1")
        assertEquals(1, repository.terminalTabs.value.size)
        assertEquals("tab-2", repository.activeTerminalTabId.value)

        repository.clearWorkspace()
        assertTrue(repository.terminalTabs.value.isEmpty())
        assertNull(repository.activeTerminalTabId.value)
    }

    private class FakeDockerApi : DockerApi {
        var connectCount = 0
        var containers: List<DockerContainer> = emptyList()
        var stacks: List<DockerStack> = emptyList()
        private val composeByStack = mutableMapOf<String, String>()

        override suspend fun connectSsh(hostId: Long): DockerSshSession {
            connectCount++
            return DockerSshSession(
                sessionId = "docker-session-$hostId",
                hostId = hostId,
                connected = true,
            )
        }

        override suspend fun keepaliveSsh(sessionId: String): Boolean = true

        override suspend fun listContainers(sessionId: String, all: Boolean): List<DockerContainer> =
            containers

        override suspend fun containerAction(
            sessionId: String,
            containerId: String,
            action: DockerContainerAction,
        ): DockerActionResult = DockerActionResult(
            success = true,
            action = action.wireName,
            targetId = containerId,
        )

        override suspend fun getContainerLogs(
            sessionId: String,
            containerId: String,
            tail: Int,
        ): DockerLogsResult = DockerLogsResult(
            containerId = containerId,
            logs = "log line 1\nlog line 2",
            tail = tail,
        )

        override suspend fun listStacks(sessionId: String): List<DockerStack> = stacks

        override suspend fun getStackCompose(sessionId: String, stackName: String): DockerComposeFile =
            DockerComposeFile(
                stackName = stackName,
                content = composeByStack[stackName] ?: "services: {}",
            )

        override suspend fun updateStackCompose(
            sessionId: String,
            stackName: String,
            content: String,
        ): DockerComposeFile {
            composeByStack[stackName] = content
            return DockerComposeFile(stackName = stackName, content = content)
        }

        override suspend fun stackAction(
            sessionId: String,
            stackName: String,
            action: DockerStackAction,
        ): DockerActionResult = DockerActionResult(
            success = true,
            action = action.wireName,
            targetId = stackName,
        )
    }

    @Test
    fun sftpBrowse_marksSymlinkToDirectoryAsNavigable_andKeepsFileSymlinkAsFile() = runTest(testDispatcher) {
        fakeSftpApi.entriesByPath["/srv"] = listOf(
            SftpEntry(name = "link-dir", path = "/srv/link-dir", isSymlink = true, linkTarget = "data"),
            SftpEntry(name = "link-file", path = "/srv/link-file", isSymlink = true, linkTarget = "compose.yaml"),
            SftpEntry(name = "data", path = "/srv/data", isDirectory = true),
        )
        fakeSftpApi.entriesByPath["/srv/data"] = emptyList()

        val browseRes = repository.browseSftpDirectory(hostId = 4L, path = "/srv")
        assertTrue(browseRes.isSuccess)
        val entries = browseRes.getOrThrow()
        assertTrue(entries[0].isDirectory)
        assertTrue(entries[0].isSymlink)
        assertFalse(entries[1].isDirectory)
        assertTrue(entries[1].isSymlink)
        assertTrue(entries[2].isDirectory)
    }

    private class FakeSftpApi : SftpApi {
        var connectCount = 0
        val entriesByPath = mutableMapOf<String, List<SftpEntry>>()
        val filesByPath = mutableMapOf<String, String>()

        override suspend fun connect(hostId: Long): SftpSession {
            connectCount++
            return SftpSession(
                sessionId = "sftp-session-$hostId",
                hostId = hostId,
                homePath = "/root",
                connected = true,
            )
        }

        override suspend fun listFiles(sessionId: String, path: String): List<SftpEntry> =
            entriesByPath[path] ?: throw IllegalStateException("not a directory: $path")

        override suspend fun readFile(sessionId: String, path: String): SftpFileContent =
            SftpFileContent(
                path = path,
                content = filesByPath[path].orEmpty(),
            )

        override suspend fun writeFile(
            sessionId: String,
            path: String,
            content: String,
        ): SftpTransferResult {
            filesByPath[path] = content
            return SftpTransferResult(
                success = true,
                targetPath = path,
                bytesTransferred = content.length.toLong(),
            )
        }

        override suspend fun downloadFile(sessionId: String, path: String): ByteArray =
            filesByPath[path].orEmpty().encodeToByteArray()

        override suspend fun uploadFile(
            sessionId: String,
            path: String,
            bytes: ByteArray,
        ): SftpTransferResult {
            filesByPath[path] = bytes.decodeToString()
            return SftpTransferResult(
                success = true,
                targetPath = path,
                bytesTransferred = bytes.size.toLong(),
            )
        }

        override suspend fun transferToHost(
            sourceSessionId: String,
            sourcePath: String,
            targetHostId: Long,
            targetPath: String,
        ): SftpTransferResult = SftpTransferResult(
            success = true,
            sourcePath = sourcePath,
            targetPath = targetPath,
            targetHostId = targetHostId,
            bytesTransferred = 64L,
        )
    }
}
