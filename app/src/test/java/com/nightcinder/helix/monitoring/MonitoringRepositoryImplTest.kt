package com.nightcinder.helix.monitoring

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.api.DockerApi
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.DockerActionResult
import com.nightcinder.helix.core.model.DockerComposeFile
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerContainerAction
import com.nightcinder.helix.core.model.DockerLogsResult
import com.nightcinder.helix.core.model.DockerSshSession
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.DockerStackAction
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.WolDevice
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MonitoringRepositoryImplTest {
    init {
        TestSite.apply()
    }


    private lateinit var promServer: MockWebServer
    private lateinit var grafanaServer: MockWebServer

    @Before
    fun setUp() {
        promServer = MockWebServer().apply { start() }
        grafanaServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        promServer.shutdown()
        grafanaServer.shutdown()
    }

    private class StubDiunDockerApi(private val logs: String) : DockerApi {
        override suspend fun connectSsh(hostId: Long) = DockerSshSession(sessionId = "s-$hostId", hostId = hostId)
        override suspend fun keepaliveSsh(sessionId: String) = true
        override suspend fun listContainers(sessionId: String, all: Boolean) =
            listOf(DockerContainer(id = "diun-1", name = "/diun", state = "running"))
        override suspend fun containerAction(sessionId: String, containerId: String, action: DockerContainerAction) =
            DockerActionResult(success = true)
        override suspend fun getContainerLogs(sessionId: String, containerId: String, tail: Int) =
            DockerLogsResult(containerId = containerId, logs = logs)
        override suspend fun listStacks(sessionId: String) = emptyList<DockerStack>()
        override suspend fun getStackCompose(sessionId: String, stackName: String) = DockerComposeFile()
        override suspend fun updateStackCompose(sessionId: String, stackName: String, content: String) = DockerComposeFile()
        override suspend fun stackAction(sessionId: String, stackName: String, action: DockerStackAction) =
            DockerActionResult(success = true)
    }

    @Test
    fun refresh_offLan403OnPromAndGrafana_stillReturnsDiunAlertsAndProbesUnknownHosts(): Unit = runBlocking {
        promServer.enqueue(MockResponse().setResponseCode(403).setBody("LAN only"))
        grafanaServer.enqueue(MockResponse().setResponseCode(403).setBody("LAN only"))

        val repo = MonitoringRepositoryImpl(
            prometheusClient = PrometheusApiClient(
                client = OkHttpClient(),
                baseUrlProvider = { promServer.url("/").toString().trimEnd('/') },
            ),
            grafanaClient = GrafanaAlertsClient(
                client = OkHttpClient(),
                baseUrlProvider = { grafanaServer.url("/").toString().trimEnd('/') },
            ),
            diunSource = DiunLogAlertSource(
                StubDiunDockerApi("Mon, 23 Nov 2026 18:00:34 EST INF New image found image=ghcr.io/paperless-ngx/paperless-ngx:3.5"),
            ),
            tcpReachabilityProbe = { host, _, _ ->
                host == "192.0.2.20" // desktop reachable, server1 unreachable
            },
            clock = { 1_763_940_000_000L },
        )

        val hosts = listOf(
            TermixHost(id = 1L, name = "core", ip = "192.0.2.10", status = HostStatus.ONLINE),
            TermixHost(id = 2L, name = "desktop", ip = "192.0.2.20", status = HostStatus.UNKNOWN),
            TermixHost(id = 3L, name = "server1", ip = "192.0.2.30", status = HostStatus.UNKNOWN),
            TermixHost(id = 4L, name = "workstation", ip = "192.0.2.40", macAddress = "02:00:00:00:00:01", status = HostStatus.UNKNOWN),
        )
        val wolDevices = listOf(
            WolDevice(id = 10L, hostId = 4L, name = "workstation", macAddress = "02:00:00:00:00:01", isOnline = false),
        )

        val snapshot = repo.refresh(hosts, wolDevices).getOrThrow()

        // Honest source errors for off-LAN 403
        assertTrue(snapshot.sourceErrors.containsKey(AlertSource.PROMETHEUS))
        assertTrue(snapshot.sourceErrors.containsKey(AlertSource.GRAFANA))
        assertTrue(!snapshot.sourceErrors.containsKey(AlertSource.DIUN))

        // Diun alert still surfaced!
        assertEquals(1, snapshot.alerts.size)
        assertEquals(AlertSource.DIUN, snapshot.alerts.first().source)
        assertEquals(1L, snapshot.alerts.first().targetHostId)

        // Reachability resolved for all UNKNOWN hosts
        assertEquals(true, snapshot.hostUpByKey["desktop"])
        assertEquals(false, snapshot.hostUpByKey["server1"])
        assertEquals(false, snapshot.hostUpByKey["workstation"])
        assertEquals(1_763_940_000_000L, snapshot.lastUpdatedEpochMs)
    }

    @Test
    fun hostKeyMatcher_respectsTokenBoundariesAndClearDataClearsDiunSessions(): Unit = runBlocking {
        // "operational" contains "vcenter" substring but must NOT match server1!
        assertEquals("operational", HostKeyMatcher.canonicalKey("operational"))
        assertNull(HostKeyMatcher.knownCanonicalHostOrNull("operational"))
        assertNull(HostKeyMatcher.knownCanonicalHostOrNull("CertExpiringSoon"))

        // Delimiter-separated tokens DO match known canonical hosts
        assertEquals("server1", HostKeyMatcher.knownCanonicalHostOrNull("esxi-poller-down"))
        assertEquals("server1", HostKeyMatcher.knownCanonicalHostOrNull("vcenter.example.com:9272"))
        assertEquals("core", HostKeyMatcher.knownCanonicalHostOrNull("docker-host"))
        assertEquals("desktop", HostKeyMatcher.knownCanonicalHostOrNull("192.0.2.20"))
        assertEquals("workstation", HostKeyMatcher.knownCanonicalHostOrNull("workstation"))

        var connectCalls = 0
        val countingDockerApi = object : DockerApi {
            override suspend fun connectSsh(hostId: Long): DockerSshSession {
                connectCalls++
                return DockerSshSession(sessionId = "s-$connectCalls", hostId = hostId)
            }
            override suspend fun keepaliveSsh(sessionId: String) = true
            override suspend fun listContainers(sessionId: String, all: Boolean) = emptyList<DockerContainer>()
            override suspend fun containerAction(sessionId: String, containerId: String, action: DockerContainerAction) =
                DockerActionResult(success = true)
            override suspend fun getContainerLogs(sessionId: String, containerId: String, tail: Int) = DockerLogsResult()
            override suspend fun listStacks(sessionId: String) = emptyList<DockerStack>()
            override suspend fun getStackCompose(sessionId: String, stackName: String) = DockerComposeFile()
            override suspend fun updateStackCompose(sessionId: String, stackName: String, content: String) = DockerComposeFile()
            override suspend fun stackAction(sessionId: String, stackName: String, action: DockerStackAction) =
                DockerActionResult(success = true)
        }
        promServer.enqueue(MockResponse().setResponseCode(403))
        grafanaServer.enqueue(MockResponse().setResponseCode(403))
        promServer.enqueue(MockResponse().setResponseCode(403))
        grafanaServer.enqueue(MockResponse().setResponseCode(403))

        val repo = MonitoringRepositoryImpl(
            prometheusClient = PrometheusApiClient(OkHttpClient(), { promServer.url("/").toString().trimEnd('/') }),
            grafanaClient = GrafanaAlertsClient(OkHttpClient(), { grafanaServer.url("/").toString().trimEnd('/') }),
            diunSource = DiunLogAlertSource(countingDockerApi),
        )
        val hosts = listOf(TermixHost(id = 1L, name = "core", status = HostStatus.ONLINE))
        repo.refresh(hosts)
        assertEquals(1, connectCalls)

        repo.clearData()
        repo.refresh(hosts)
        assertEquals(2, connectCalls)
    }
}
