package com.nightcinder.helix.api

import com.nightcinder.helix.core.api.WsTicket
import com.nightcinder.helix.core.model.SnippetExecutionRequest
import com.nightcinder.helix.core.model.SnippetSecretResolveRequest
import com.nightcinder.helix.core.model.TermixApiException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class TermixApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TermixApiClient
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        client = TermixApiClient(
            client = okHttpClient,
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getHosts_decodesArrayAndAttachesNoMutatingHeaders() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"id":1,"name":"core","ip":"192.168.1.10"},{"id":2,"name":"desktop","ip":"192.168.1.11"}]""")
        )

        val hosts = client.getHosts()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/host/db/host", recorded.path)
        assertEquals(null, recorded.getHeader("X-Electron-App"))
        assertEquals(2, hosts.size)
        assertEquals("core", hosts[0].name)
        assertEquals("desktop", hosts[1].name)
    }

    @Test
    fun getHosts_decodesWrappedObject() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"hosts":[{"id":3,"name":"workstation","ip":"192.168.1.12"}]}""")
        )

        val hosts = client.getHosts()
        val recorded = server.takeRequest()
        assertEquals("/host/db/host", recorded.path)
        assertEquals(1, hosts.size)
        assertEquals("workstation", hosts[0].name)
    }

    @Test
    fun getHost_returnsSingleHost() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":1,"name":"core","ip":"192.168.1.10","tags":["homelab","core"]}""")
        )

        val host = client.getHost(1)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/host/db/host/1", recorded.path)
        assertEquals(1L, host.id)
        assertEquals("core", host.name)
        assertEquals(listOf("homelab", "core"), host.tags)
    }

    @Test
    fun getDataStatus_returnsStatus() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"unlocked":true,"dataUnlocked":true,"status":"unlocked"}""")
        )

        val status = client.getDataStatus()
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/users/data-status", recorded.path)
        assertTrue(status.isDekUnlocked)
    }

    @Test
    fun createWsTicket_callsExactPathAndAttachesXElectronApp() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticket":"ws_ticket_abc123","expiresInSeconds":3600}""")
        )

        val ticket = client.createWsTicket()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/users/ws-ticket", recorded.path)
        assertEquals("true", recorded.getHeader("X-Electron-App"))
        assertEquals("ws_ticket_abc123", ticket.ticket)
        assertEquals(3600L, ticket.expiresInSeconds)
    }

    @Test
    fun getMetricsSummary_returnsParsedSummary() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"hosts":[{"hostId":1,"hostName":"core","cpu":14.2,"memory":45.0}],"totalHosts":1,"onlineHosts":1}""")
        )

        val summary = client.getMetricsSummary()
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/plugin-api/host-metrics/metrics/summary", recorded.path)
        assertEquals(1, summary.hosts.size)
        assertEquals(14.2, summary.hosts[0].cpuPercent ?: 0.0, 0.01)
    }

    @Test
    fun getHealthSummary_returnsHealth() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"hosts":[{"hostId":1,"healthy":true,"status":"online","latencyMs":2}],"healthyCount":1}""")
        )

        val health = client.getHealthSummary()
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/plugin-api/host-metrics/health/summary", recorded.path)
        assertEquals(1, health.healthyCount)
        assertEquals(true, health.hosts[0].healthy)
    }

    @Test
    fun summaries_acceptTermixBareArrays() = runTest {
        // Live host-metrics v26.11 payloads (bare arrays, health level ok|warn|crit).
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""[{"hostId":3,"cpu":12.5,"memory":40.1,"disk":null,"lastChecked":"2026-11-23T05:00:00.000Z"}]"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""[{"hostId":3,"level":"warn","checks":[{"id":"c1","name":"disk","level":"warn","detail":"85%","ts":1}]}]"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("[]"),
        )

        val metrics = client.getMetricsSummary()
        assertEquals(1, metrics.hosts.size)
        assertEquals(3L, metrics.hosts[0].hostId)
        assertEquals(12.5, metrics.hosts[0].cpuPercent ?: 0.0, 0.01)

        val health = client.getHealthSummary()
        assertEquals(com.nightcinder.helix.core.model.HostStatus.DEGRADED, health.hosts[0].resolvedStatus)

        val empty = client.getHealthSummary()
        assertEquals(0, empty.hosts.size)
    }

    @Test
    fun getLiveHostMetrics_usesVerifiedPath() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"hostId":42,"cpu":25.5,"memoryPercent":60.0,"networkRxBytesPerSec":1048576}""")
        )

        val live = client.getLiveHostMetrics(42)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/plugin-api/host-metrics/host-metrics/managers/live/42", recorded.path)
        assertEquals(42L, live.hostId)
        assertEquals(25.5, live.cpuPercent ?: 0.0, 0.01)
        assertEquals(1048576L, live.networkRxBytesPerSec)
    }

    @Test
    fun startAndHeartbeatHostMetrics_sendPostWithHeader() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"hostId":7,"started":true,"active":true}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"hostId":7,"active":true}""")
        )

        val start = client.startHostMetrics(7)
        val hb = client.heartbeatHostMetrics(7)

        val rec1 = server.takeRequest()
        assertEquals("POST", rec1.method)
        assertEquals("/plugin-api/host-metrics/metrics/start/7", rec1.path)
        assertEquals("true", rec1.getHeader("X-Electron-App"))

        val rec2 = server.takeRequest()
        assertEquals("POST", rec2.method)
        assertEquals("/plugin-api/host-metrics/metrics/heartbeat/7", rec2.path)
        assertEquals("true", rec2.getHeader("X-Electron-App"))

        assertTrue(start.started)
        assertTrue(hb.active)
    }

    @Test
    fun wakeWolDevice_and_wakeHost_pathsAndMutatingHeader() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"message":"Magic packet dispatched"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"message":"Host wake dispatched"}""")
        )

        val r1 = client.wakeWolDevice(99)
        val rec1 = server.takeRequest()
        assertEquals("POST", rec1.method)
        assertEquals("/plugin-api/wake-on-lan/devices/99/wake", rec1.path)
        assertEquals("true", rec1.getHeader("X-Electron-App"))
        assertTrue(r1.success)

        val r2 = client.wakeHost(99)
        val rec2 = server.takeRequest()
        assertEquals("POST", rec2.method)
        assertEquals("/plugin-api/wake-on-lan/host/99/wake", rec2.path)
        assertEquals("true", rec2.getHeader("X-Electron-App"))
        assertTrue(r2.success)
    }

    @Test
    fun snippets_getResolveExecute_flow() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"id":5,"name":"Clean Docker","command":"docker system prune -f"}]""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"secrets":{"DOCKER_AUTH":"token123"}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"snippetId":5,"hostId":1,"success":true,"stdout":"Total reclaimed: 2GB","exitCode":0}""")
        )

        val snippets = client.getSnippets()
        assertEquals(1, snippets.size)
        assertEquals("Clean Docker", snippets[0].name)

        val secrets = client.resolveSnippetSecrets(SnippetSecretResolveRequest(snippetId = 5, hostId = 1))
        assertEquals("token123", secrets.secrets["DOCKER_AUTH"])

        val execResult = client.executeSnippet(
            SnippetExecutionRequest(
                snippetId = 5,
                hostId = 1,
                resolvedSecrets = secrets.secrets,
            )
        )
        assertTrue(execResult.success)
        assertEquals("Total reclaimed: 2GB", execResult.stdout)

        val rec1 = server.takeRequest()
        assertEquals("GET", rec1.method)
        assertEquals("/plugin-api/snippets", rec1.path)

        val rec2 = server.takeRequest()
        assertEquals("POST", rec2.method)
        assertEquals("/plugin-api/snippets/secrets/resolve", rec2.path)
        assertEquals("true", rec2.getHeader("X-Electron-App"))

        val rec3 = server.takeRequest()
        assertEquals("POST", rec3.method)
        assertEquals("/plugin-api/snippets/execute", rec3.path)
        assertEquals("true", rec3.getHeader("X-Electron-App"))
        assertTrue(rec3.body.readUtf8().contains("token123"))
    }

    @Test
    fun dataExpired_mapsToStructuredException() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"DATA_EXPIRED","message":"Termix DEK has expired, user password required"}""")
        )

        try {
            client.getHosts()
            fail("Expected TermixApiException.DataExpired")
        } catch (e: TermixApiException.DataExpired) {
            assertEquals(401, e.httpStatus)
            assertEquals("DATA_EXPIRED", e.code)
        }
    }

    @Test
    fun unauthorized_mapsToStructuredUnauthorizedException() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"unauthorized"}""")
        )

        try {
            client.getHosts()
            fail("Expected TermixApiException.Unauthorized")
        } catch (e: TermixApiException.Unauthorized) {
            assertEquals(401, e.httpStatus)
        }
    }
}
