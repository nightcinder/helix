package com.nightcinder.helix.monitoring

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.api.DockerApi
import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.DockerActionResult
import com.nightcinder.helix.core.model.DockerComposeFile
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerContainerAction
import com.nightcinder.helix.core.model.DockerLogsResult
import com.nightcinder.helix.core.model.DockerSshSession
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.DockerStackAction
import com.nightcinder.helix.core.model.TermixHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class DiunLogAlertSourceTest {
    init {
        TestSite.apply()
    }


    private class FakeDockerApi(
        private val containers: List<DockerContainer>,
        private val logs: String,
    ) : DockerApi {
        var connectCalls = 0
        var lastTail = 0

        override suspend fun connectSsh(hostId: Long): DockerSshSession {
            connectCalls++
            return DockerSshSession(sessionId = "sess-$hostId", hostId = hostId)
        }

        override suspend fun keepaliveSsh(sessionId: String): Boolean = true
        override suspend fun listContainers(sessionId: String, all: Boolean): List<DockerContainer> = containers
        override suspend fun containerAction(sessionId: String, containerId: String, action: DockerContainerAction) =
            DockerActionResult(success = true)

        override suspend fun getContainerLogs(sessionId: String, containerId: String, tail: Int): DockerLogsResult {
            lastTail = tail
            return DockerLogsResult(containerId = containerId, logs = logs, tail = tail)
        }

        override suspend fun listStacks(sessionId: String): List<DockerStack> = emptyList()
        override suspend fun getStackCompose(sessionId: String, stackName: String) = DockerComposeFile()
        override suspend fun updateStackCompose(sessionId: String, stackName: String, content: String) = DockerComposeFile()
        override suspend fun stackAction(sessionId: String, stackName: String, action: DockerStackAction) =
            DockerActionResult(success = true)
    }

    @Test
    fun fetchDiunAlerts_parsesAndDeduplicatesRealDiunLogLines(): Unit = runBlocking {
        val sampleLogs = """
            Mon, 23 Nov 2026 17:00:00 EST INF Starting Diun version=4.28.0
            Mon, 23 Nov 2026 18:00:34 EST INF New image found image=ghcr.io/paperless-ngx/paperless-ngx:3.5 provider=docker
            Mon, 23 Nov 2026 18:00:35 EST INF Image update found image=lscr.io/linuxserver/plex:1.41.2
            Mon, 23 Nov 2026 18:30:00 EST INF New image found image=ghcr.io/paperless-ngx/paperless-ngx:3.5 provider=docker
            Mon, 23 Nov 2026 18:30:01 EST INF Jobs completed
        """.trimIndent()

        val dockerApi = FakeDockerApi(
            containers = listOf(
                DockerContainer(id = "c-1", name = "/traefik", state = "running"),
                DockerContainer(id = "c-diun", name = "/diun", state = "running"),
            ),
            logs = sampleLogs,
        )
        val source = DiunLogAlertSource(dockerApi)
        val hosts = listOf(
            TermixHost(id = 7L, name = "core", ip = "192.0.2.10"),
            TermixHost(id = 8L, name = "desktop", ip = "192.0.2.20"),
        )

        val alerts = source.fetchDiunAlerts(hosts)
        assertEquals(1, dockerApi.connectCalls)
        assertEquals(200, dockerApi.lastTail)
        assertEquals(2, alerts.size)

        val paperless = alerts[0]
        assertEquals(AlertSource.DIUN, paperless.source)
        assertEquals(AlertSeverity.INFO, paperless.severity)
        assertEquals("Update: paperless-ngx:3.5", paperless.title)
        assertEquals("ghcr.io/paperless-ngx/paperless-ngx:3.5", paperless.summary)
        assertEquals("core", paperless.targetHostName)
        assertEquals(7L, paperless.targetHostId)
        assertNotNull(paperless.triggeredAtEpochMs)

        val plex = alerts[1]
        assertEquals("Update: plex:1.41.2", plex.title)
        assertEquals("lscr.io/linuxserver/plex:1.41.2", plex.summary)
    }

    @Test
    fun fetchDiunAlerts_reusesExistingSessionWhenProvided(): Unit = runBlocking {
        val dockerApi = FakeDockerApi(
            containers = listOf(DockerContainer(id = "c-diun", name = "diun", state = "running")),
            logs = "Mon, 23 Nov 2026 18:00:34 EST INF New image found image=ghcr.io/immich-app/immich-server:v1.120.0",
        )
        val source = DiunLogAlertSource(
            dockerApi = dockerApi,
            existingSessionIdProvider = { hostId -> if (hostId == 7L) "existing-sess" else null },
        )
        val alerts = source.fetchDiunAlerts(listOf(TermixHost(id = 7L, name = "core")))
        assertEquals(0, dockerApi.connectCalls)
        assertEquals(1, alerts.size)
        assertEquals("Update: immich-server:v1.120.0", alerts.first().title)
    }

    @Test
    fun fetchDiunAlerts_cachesFallbackSessionAcrossRefreshesAndParsesMuxHeaders(): Unit = runBlocking {
        val muxLine = "\u0001\u0000\u0000\u0000\u0000\u0000\u0000\u005F2026-11-23T23:00:34Z Mon, 23 Nov 2026 18:00:34 EST INF New image found image=ghcr.io/authelia/authelia:4.38"
        val dockerApi = FakeDockerApi(
            containers = listOf(DockerContainer(id = "c-diun", name = "/diun", state = "running")),
            logs = muxLine,
        )
        val source = DiunLogAlertSource(dockerApi)
        val hosts = listOf(TermixHost(id = 7L, name = "core"))

        // First refresh connects once; second refresh reuses cached fallback session without leaking
        val first = source.fetchDiunAlerts(hosts)
        val second = source.fetchDiunAlerts(hosts)
        assertEquals(1, dockerApi.connectCalls)
        assertEquals(1, first.size)
        assertEquals("Update: authelia:4.38", first.first().title)
        assertNotNull(first.first().triggeredAtEpochMs)
        assertEquals(1, second.size)

        // clearSessions forces fresh SSH session on next refresh
        source.clearSessions()
        source.fetchDiunAlerts(hosts)
        assertEquals(2, dockerApi.connectCalls)
    }

    @Test
    fun fetchDiunAlerts_cancellationDuringListContainersDoesNotReconnect(): Unit = runBlocking {
        var connectCount = 0
        val cancellingApi = object : DockerApi {
            override suspend fun connectSsh(hostId: Long): DockerSshSession {
                connectCount++
                return DockerSshSession(sessionId = "s-$hostId", hostId = hostId)
            }
            override suspend fun keepaliveSsh(sessionId: String) = true
            override suspend fun listContainers(sessionId: String, all: Boolean): List<DockerContainer> {
                throw CancellationException("Parent scope cancelled")
            }
            override suspend fun containerAction(sessionId: String, containerId: String, action: DockerContainerAction) =
                DockerActionResult(success = true)
            override suspend fun getContainerLogs(sessionId: String, containerId: String, tail: Int) =
                DockerLogsResult()
            override suspend fun listStacks(sessionId: String) = emptyList<DockerStack>()
            override suspend fun getStackCompose(sessionId: String, stackName: String) = DockerComposeFile()
            override suspend fun updateStackCompose(sessionId: String, stackName: String, content: String) = DockerComposeFile()
            override suspend fun stackAction(sessionId: String, stackName: String, action: DockerStackAction) =
                DockerActionResult(success = true)
        }
        val source = DiunLogAlertSource(
            dockerApi = cancellingApi,
            existingSessionIdProvider = { "existing-session" },
        )
        try {
            source.fetchDiunAlerts(listOf(TermixHost(id = 7L, name = "core")))
            fail("Expected CancellationException to be rethrown")
        } catch (_: CancellationException) {
            assertEquals(0, connectCount)
        }
    }
}
