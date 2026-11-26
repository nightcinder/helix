package com.nightcinder.helix.monitoring

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.MonitoringSourceException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class PrometheusApiClientTest {
    init {
        TestSite.apply()
    }


    private lateinit var server: MockWebServer
    private lateinit var client: PrometheusApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PrometheusApiClient(
            client = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchAll_parsesLinuxWindowsEsxiGpuKumaAndAlerts(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/api/v1/alerts")) {
                    return MockResponse().setBody(
                        """
                        {
                          "status": "success",
                          "data": {
                            "alerts": [
                              {
                                "labels": {"alertname": "HighDiskUsage", "severity": "warning", "instance": "docker-host"},
                                "annotations": {"summary": "Root disk above 85%"},
                                "state": "firing",
                                "activeAt": "2026-11-23T18:10:00Z"
                              },
                              {
                                "labels": {"alertname": "PendingRule", "severity": "info", "instance": "desktop"},
                                "annotations": {"summary": "Not firing yet"},
                                "state": "pending",
                                "activeAt": "2026-11-23T18:12:00Z"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

                val queryRaw = path.substringAfter("query=", "")
                val query = URLDecoder.decode(queryRaw, "UTF-8")
                return when (query) {
                    "up" -> vectorResponse(
                        """{"metric":{"instance":"docker-host","job":"node"},"value":[1763938800,"1"]}""",
                        """{"metric":{"instance":"desktop","job":"windows"},"value":[1763938800,"1"]}""",
                        """{"metric":{"instance":"vcenter","job":"esxi"},"value":[1763938800,"1"]}""",
                        """{"metric":{"instance":"nvidia-gpu-exporter:9835","job":"nvidia-gpu"},"value":[1763938800,"1"]}""",
                    )
                    "monitor_status!=1" -> vectorResponse(
                        """{"metric":{"monitor_id":"10","monitor_name":"Plex Media","monitor_type":"http","monitor_hostname":"192.0.2.10","monitor_url":"https://plex.example.com"},"value":[1763938800,"0"]}""",
                        """{"metric":{"monitor_id":"11","monitor_name":"Media Group","monitor_type":"group","monitor_hostname":""},"value":[1763938800,"0"]}""",
                        """{"metric":{"monitor_id":"12","monitor_name":"Sunshine Host","monitor_type":"port","monitor_hostname":"192.0.2.20"},"value":[1763938800,"2"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_CPU -> vectorResponse(
                        """{"metric":{"instance":"docker-host"},"value":[1763938800,"24.5"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_MEM_PCT -> vectorResponse(
                        """{"metric":{"instance":"docker-host"},"value":[1763938800,"62.5"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_MEM_TOTAL -> vectorResponse(
                        """{"metric":{"instance":"docker-host"},"value":[1763938800,"68719476736"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_MEM_AVAIL -> vectorResponse(
                        """{"metric":{"instance":"docker-host"},"value":[1763938800,"25769803776"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_DISK_SIZE -> vectorResponse(
                        """{"metric":{"instance":"docker-host","mountpoint":"/"},"value":[1763938800,"1000000000000"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_DISK_AVAIL -> vectorResponse(
                        """{"metric":{"instance":"docker-host","mountpoint":"/"},"value":[1763938800,"400000000000"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_NET_RX -> vectorResponse(
                        """{"metric":{"instance":"docker-host"},"value":[1763938800,"1250000"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_NET_TX -> vectorResponse(
                        """{"metric":{"instance":"docker-host"},"value":[1763938800,"450000"]}""",
                    )
                    PrometheusApiClient.QUERY_WINDOWS_CPU -> vectorResponse(
                        """{"metric":{"instance":"desktop"},"value":[1763938800,"18.2"]}""",
                    )
                    PrometheusApiClient.QUERY_WINDOWS_MEM_TOTAL -> vectorResponse(
                        """{"metric":{"instance":"desktop"},"value":[1763938800,"34359738368"]}""",
                    )
                    PrometheusApiClient.QUERY_WINDOWS_MEM_FREE -> vectorResponse(
                        """{"metric":{"instance":"desktop"},"value":[1763938800,"17179869184"]}""",
                    )
                    PrometheusApiClient.QUERY_WINDOWS_DISK_SIZE -> vectorResponse(
                        """{"metric":{"instance":"desktop","volume":"C:"},"value":[1763938800,"2000000000000"]}""",
                    )
                    PrometheusApiClient.QUERY_WINDOWS_DISK_FREE -> vectorResponse(
                        """{"metric":{"instance":"desktop","volume":"C:"},"value":[1763938800,"500000000000"]}""",
                    )
                    PrometheusApiClient.QUERY_ESXI_CPU -> vectorResponse(
                        """{"metric":{"instance":"vcenter","cpu":"instance-total"},"value":[1763938800,"11.4"]}""",
                    )
                    PrometheusApiClient.QUERY_ESXI_MEM_PCT -> vectorResponse(
                        """{"metric":{"instance":"vcenter"},"value":[1763938800,"48.0"]}""",
                    )
                    PrometheusApiClient.QUERY_ESXI_NET_RX -> vectorResponse(
                        """{"metric":{"instance":"vcenter"},"value":[1763938800,"500"]}""",
                    )
                    PrometheusApiClient.QUERY_ESXI_NET_TX -> vectorResponse(
                        """{"metric":{"instance":"vcenter"},"value":[1763938800,"250"]}""",
                    )
                    PrometheusApiClient.QUERY_ESXI_UPTIME -> vectorResponse(
                        """{"metric":{"instance":"vcenter"},"value":[1763938800,"864000"]}""",
                    )
                    PrometheusApiClient.QUERY_GPU_UTIL -> vectorResponse(
                        """{"metric":{"instance":"nvidia-gpu-exporter:9835","job":"nvidia-gpu"},"value":[1763938800,"0.42"]}""",
                    )
                    PrometheusApiClient.QUERY_GPU_TEMP -> vectorResponse(
                        """{"metric":{"instance":"nvidia-gpu-exporter:9835","job":"nvidia-gpu"},"value":[1763938800,"58"]}""",
                    )
                    PrometheusApiClient.QUERY_GPU_MEM_USED -> vectorResponse(
                        """{"metric":{"instance":"nvidia-gpu-exporter:9835","job":"nvidia-gpu"},"value":[1763938800,"4294967296"]}""",
                    )
                    PrometheusApiClient.QUERY_GPU_MEM_TOTAL -> vectorResponse(
                        """{"metric":{"instance":"nvidia-gpu-exporter:9835","job":"nvidia-gpu"},"value":[1763938800,"25769803776"]}""",
                    )
                    PrometheusApiClient.QUERY_GPU_POWER -> vectorResponse(
                        """{"metric":{"instance":"nvidia-gpu-exporter:9835","job":"nvidia-gpu"},"value":[1763938800,"135.5"]}""",
                    )
                    else -> vectorResponse()
                }
            }
        }

        val result = client.fetchAll()

        // Host up map
        assertEquals(true, result.hostUpByKey["core"])
        assertEquals(true, result.hostUpByKey["desktop"])
        assertEquals(true, result.hostUpByKey["server1"])

        // core metrics + GPU + Disk
        val core = result.hostMetricsByKey["core"]
        assertNotNull(core)
        assertEquals(24.5, core!!.cpuPercent!!, 0.01)
        assertEquals(62.5, core.memoryPercent!!, 0.01)
        assertEquals(60.0, core.diskPercent!!, 0.01)
        assertEquals(600_000_000_000L, core.diskUsedBytes)
        assertEquals(1_000_000_000_000L, core.diskTotalBytes)
        assertEquals(1_250_000L, core.networkRxBytesPerSec)
        assertEquals(42.0, core.gpuUtilizationPercent!!, 0.01)
        assertEquals(58.0, core.gpuTemperatureCelsius!!, 0.01)
        assertEquals(135.5, core.gpuPowerWatts!!, 0.01)

        // desktop metrics + Disk C:
        val desktop = result.hostMetricsByKey["desktop"]
        assertNotNull(desktop)
        assertEquals(18.2, desktop!!.cpuPercent!!, 0.01)
        assertEquals(50.0, desktop.memoryPercent!!, 0.01)
        assertEquals(75.0, desktop.diskPercent!!, 0.01)
        assertEquals(1_500_000_000_000L, desktop.diskUsedBytes)

        // server1 (vcenter) ESXi metrics (KB/s -> B/s)
        val server1 = result.hostMetricsByKey["server1"]
        assertNotNull(server1)
        assertEquals(11.4, server1!!.cpuPercent!!, 0.01)
        assertEquals(48.0, server1.memoryPercent!!, 0.01)
        assertEquals(512_000L, server1.networkRxBytesPerSec)
        assertEquals(256_000L, server1.networkTxBytesPerSec)
        assertEquals(864_000L, server1.uptimeSeconds)

        // Alerts: 2 Kuma (group skipped) + 1 firing Prometheus (pending skipped)
        assertEquals(3, result.alerts.size)
        val plexAlert = result.alerts.first { it.title.contains("Plex Media") }
        assertEquals(AlertSource.UPTIME_KUMA, plexAlert.source)
        assertEquals(AlertSeverity.CRITICAL, plexAlert.severity)
        assertEquals("core", plexAlert.targetHostName)

        val sunshineAlert = result.alerts.first { it.title.contains("Sunshine Host") }
        assertEquals(AlertSeverity.WARNING, sunshineAlert.severity)
        assertEquals("desktop", sunshineAlert.targetHostName)

        val promAlert = result.alerts.first { it.source == AlertSource.PROMETHEUS }
        assertEquals("HighDiskUsage", promAlert.title)
        assertEquals("core", promAlert.targetHostName)
    }

    @Test
    fun fetchAll_primaryOsJobWinsUpPrecedenceAndIgnoresRootfsZeroDisk(): Unit = runBlocking {
        assertEquals(
            "sum by (instance) (vsphere_host_net_transmitted_average)",
            PrometheusApiClient.QUERY_ESXI_NET_TX,
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/api/v1/alerts")) {
                    return MockResponse().setBody("""{"status":"success","data":{"alerts":[]}}""")
                }
                val query = URLDecoder.decode(path.substringAfter("query=", ""), "UTF-8")
                return when (query) {
                    // Secondary exporter down (`0`) before `node` (`1`), and secondary exporter (`0`) after `node` (`1`)
                    "up" -> vectorResponse(
                        """{"metric":{"instance":"docker-host","job":"cadvisor"},"value":[1763938800,"0"]}""",
                        """{"metric":{"instance":"docker-host","job":"node"},"value":[1763938800,"1"]}""",
                        """{"metric":{"instance":"nvidia-gpu-exporter:9835","job":"nvidia-gpu"},"value":[1763938800,"0"]}""",
                        """{"metric":{"instance":"desktop","job":"custom-exporter"},"value":[1763938800,"1"]}""",
                        """{"metric":{"instance":"desktop","job":"windows"},"value":[1763938800,"0"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_DISK_SIZE -> vectorResponse(
                        """{"metric":{"instance":"docker-host","mountpoint":"/","fstype":"ext4","device":"/dev/nvme0n1p2"},"value":[1763938800,"1000000000000"]}""",
                        """{"metric":{"instance":"docker-host","mountpoint":"/","fstype":"rootfs","device":"rootfs"},"value":[1763938800,"0"]}""",
                    )
                    PrometheusApiClient.QUERY_LINUX_DISK_AVAIL -> vectorResponse(
                        """{"metric":{"instance":"docker-host","mountpoint":"/","fstype":"ext4","device":"/dev/nvme0n1p2"},"value":[1763938800,"300000000000"]}""",
                        """{"metric":{"instance":"docker-host","mountpoint":"/","fstype":"rootfs","device":"rootfs"},"value":[1763938800,"0"]}""",
                    )
                    "monitor_status!=1" -> vectorResponse(
                        """{"metric":{"monitor_id":"20","monitor_name":"HTTP Check","monitor_type":"http","monitor_url":"https://a.example.com"},"value":[1763938800,"0"]}""",
                        """{"metric":{"monitor_id":"20","monitor_name":"HTTP Check","monitor_type":"http","monitor_url":"https://b.example.com"},"value":[1763938800,"0"]}""",
                    )
                    else -> vectorResponse()
                }
            }
        }

        val result = client.fetchAll()

        // `node` (1) beats `cadvisor` (0) and `nvidia-gpu` (0); `windows` (0) beats `custom-exporter` (1)
        assertEquals(true, result.hostUpByKey["core"])
        assertEquals("node", result.hostMetricsByKey["core"]?.sourceJob)
        assertEquals("docker-host", result.hostMetricsByKey["core"]?.instanceLabel)
        assertEquals(false, result.hostUpByKey["desktop"])

        // `rootfs` 0-byte entry did NOT clobber ext4 `/` disk metrics
        val core = result.hostMetricsByKey["core"]!!
        assertEquals(1_000_000_000_000L, core.diskTotalBytes)
        assertEquals(700_000_000_000L, core.diskUsedBytes)
        assertEquals(70.0, core.diskPercent!!, 0.01)

        // Kuma alerts with distinct URLs have distinct non-colliding IDs
        assertEquals(2, result.alerts.size)
        assertEquals(2, result.alerts.map { it.id }.distinct().size)
    }

    @Test
    fun fetchAll_http403_throwsMonitoringSourceException(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        try {
            client.fetchAll()
            fail("Expected MonitoringSourceException on HTTP 403")
        } catch (e: MonitoringSourceException) {
            assertEquals(AlertSource.PROMETHEUS, e.source)
            assertEquals(403, e.httpStatus)
        }
    }

    @Test
    fun fetchAll_htmlSsoRedirect_throwsMonitoringSourceException(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<!DOCTYPE html><html><body>Authentik Login</body></html>"),
        )
        try {
            client.fetchAll()
            fail("Expected MonitoringSourceException on HTML SSO redirect")
        } catch (e: MonitoringSourceException) {
            assertEquals(AlertSource.PROMETHEUS, e.source)
            assertTrue(e.message.contains("HTML", ignoreCase = true) || e.message.contains("SSO", ignoreCase = true))
        }
    }

    private fun vectorResponse(vararg samples: String): MockResponse {
        val joined = samples.joinToString(",")
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"status":"success","data":{"resultType":"vector","result":[$joined]}}""")
    }
}
