package com.nightcinder.helix.api

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.api.TermixApi
import com.nightcinder.helix.core.api.WsTicket
import com.nightcinder.helix.core.model.DataStatus
import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.HomelabDefaults
import com.nightcinder.helix.core.model.HostHealthItem
import com.nightcinder.helix.core.model.HostMetricsOverview
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.LiveHostMetrics
import com.nightcinder.helix.core.model.MetricsCollectionStatus
import com.nightcinder.helix.core.model.MetricsSummary
import com.nightcinder.helix.core.model.SnippetExecutionRequest
import com.nightcinder.helix.core.model.SnippetExecutionResult
import com.nightcinder.helix.core.model.SnippetSecretResolveRequest
import com.nightcinder.helix.core.model.SnippetSecretResolveResult
import com.nightcinder.helix.core.model.TermixApiException
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.TermixSnippet
import com.nightcinder.helix.core.model.WolDevice
import com.nightcinder.helix.core.model.WolWakeResult
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
class HomelabRepositoryTest {
    init {
        TestSite.apply()
    }


    private lateinit var fakeApi: FakeTermixApi
    private lateinit var repository: HomelabRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        fakeApi = FakeTermixApi()
        repository = HomelabRepositoryImpl(
            api = fakeApi,
            ioDispatcher = testDispatcher,
        )
    }

    @Test
    fun refreshDashboard_aggregatesHostsMetricsHealthAndWol() = runTest(testDispatcher) {
        fakeApi.hostsList = listOf(
            TermixHost(id = 1, name = "core", ip = "192.168.1.10"),
            TermixHost(id = 2, name = "desktop", ip = "192.168.1.11"),
            TermixHost(id = 3, name = "workstation", ip = "192.168.1.12"),
        )
        fakeApi.metricsSummaryResponse = MetricsSummary(
            hosts = listOf(HostMetricsOverview(hostId = 1, cpuPercent = 15.0)),
            totalHosts = 3,
            onlineHosts = 2,
        )
        fakeApi.healthSummaryResponse = HealthSummary(
            hosts = listOf(
                HostHealthItem(hostId = 1, healthy = true, status = "online"),
                HostHealthItem(hostId = 2, healthy = true, status = "online"),
                HostHealthItem(hostId = 3, healthy = false, status = "offline"),
            ),
            healthyCount = 2,
            unhealthyCount = 1,
        )
        fakeApi.wolDevicesList = listOf(
            WolDevice(id = 99, hostId = 3, name = "workstation-desktop", macAddress = "02:00:00:00:00:01")
        )
        fakeApi.snippetsList = listOf(
            TermixSnippet(id = 10, name = "reboot-node", command = "sudo reboot")
        )

        val result = repository.refreshDashboard()
        assertTrue(result.isSuccess)

        val hosts = repository.hosts.value
        assertEquals(3, hosts.size)

        // Verify host 1 (core) is online and, not being the quick-wake host, has no built-in profile
        val core = hosts.first { it.id == 1L }
        assertEquals("core", core.name)
        assertEquals(HostStatus.ONLINE, core.status)
        assertNull(core.effectiveHomelabProfile)

        // Verify host 3 (workstation) has offline status and WOL capable profile
        val workstation = hosts.first { it.id == 3L }
        assertEquals(HostStatus.OFFLINE, workstation.status)
        assertNotNull(workstation.effectiveHomelabProfile)
        assertEquals("Workstation (WOL)", workstation.effectiveHomelabProfile?.displayRole)
        assertEquals(HomelabDefaults.WOL_MAC, workstation.effectiveHomelabProfile?.wolMacAddress)

        // Verify state flows populated
        assertEquals(1, repository.metricsSummary.value?.hosts?.size)
        assertEquals(2, repository.healthSummary.value?.healthyCount)
        assertEquals(1, repository.wolDevices.value.size)
        assertEquals(1, repository.snippets.value.size)
        assertNull(repository.lastError.value)
    }

    @Test
    fun wakeDevice_dynamicLookupByNormalizedMac_withoutHardcodedId() = runTest(testDispatcher) {
        // Device is registered in Termix with arbitrary dynamic ID (e.g. 748, hostId 33)
        fakeApi.wolDevicesList = listOf(
            WolDevice(id = 748, hostId = 33, name = "workstation-workstation", macAddress = "02:00:00:00:00:01")
        )

        // Wake using lowercase colon-separated MAC
        val result1 = repository.wakeDevice(macAddress = "02:00:00:00:00:01")
        assertTrue(result1.isSuccess)
        val wake1 = result1.getOrThrow()
        assertEquals(33L, wake1.hostId)
        assertEquals(748L, wake1.deviceId)
        assertEquals("/plugin-api/wake-on-lan/devices/33/wake", wake1.endpointUsed)

        // Wake using hyphenated uppercase MAC
        val result2 = repository.wakeDevice(macAddress = "02-00-00-00-00-01")
        assertTrue(result2.isSuccess)
        assertEquals(33L, result2.getOrThrow().hostId)
    }

    @Test
    fun wakeDevice_dynamicLookupByHostname_resolvesWorkstationDefaults() = runTest(testDispatcher) {
        // Device registered with dynamic ID 105, matching homelab default MAC for workstation
        fakeApi.wolDevicesList = listOf(
            WolDevice(id = 105, hostId = 105, name = "Workstation PC", macAddress = "02:00:00:00:00:01")
        )

        val result = repository.wakeDevice(hostName = "workstation")
        assertTrue(result.isSuccess)
        val wake = result.getOrThrow()
        assertEquals(105L, wake.hostId)
        assertEquals(105L, wake.deviceId)
    }

    @Test
    fun wakeDevice_fallsBackToHostWakeEndpointOn404() = runTest(testDispatcher) {
        fakeApi.wolDevicesList = listOf(
            WolDevice(id = 55, hostId = 55, name = "desktop-box", macAddress = "00:11:22:33:44:55")
        )
        fakeApi.failWakeWolWith404 = true

        val result = repository.wakeDevice(hostName = "desktop-box")
        assertTrue(result.isSuccess)
        val wake = result.getOrThrow()
        assertEquals(55L, wake.hostId)
        assertEquals("/plugin-api/wake-on-lan/host/55/wake", wake.endpointUsed)
    }

    @Test
    fun wakeDevice_failsWhenNoMatchFound() = runTest(testDispatcher) {
        fakeApi.wolDevicesList = emptyList()

        val result = repository.wakeDevice(macAddress = "aa:bb:cc:dd:ee:ff")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TermixApiException.HttpError)
        val error = result.exceptionOrNull() as TermixApiException.HttpError
        assertEquals(404, error.httpStatus)
    }

    @Test
    fun refreshHostLiveMetrics_startsMetricsAndCaches() = runTest(testDispatcher) {
        fakeApi.liveHostMetricsResponse = LiveHostMetrics(
            hostId = 1,
            cpuPercent = 33.3,
            memoryPercent = 70.0,
        )

        val result = repository.refreshHostLiveMetrics(hostId = 1, ensureStarted = true)
        assertTrue(result.isSuccess)
        val live = result.getOrThrow()
        assertEquals(1L, live.hostId)
        assertEquals(33.3, live.cpuPercent ?: 0.0, 0.01)
        assertTrue(fakeApi.startHostMetricsCalledWith.contains(1L))

        val map = repository.liveMetricsByHost.value
        assertEquals(1, map.size)
        assertEquals(33.3, map[1L]?.cpuPercent ?: 0.0, 0.01)
    }

    @Test
    fun executeSnippet_resolvesSecretsFirst() = runTest(testDispatcher) {
        fakeApi.secretResolveResponse = SnippetSecretResolveResult(
            secrets = mapOf("PASSWORD" to "secret123")
        )
        fakeApi.snippetExecutionResponse = SnippetExecutionResult(
            snippetId = 4,
            hostId = 1,
            success = true,
            stdout = "hello world",
        )

        val result = repository.executeSnippet(snippetId = 4, hostId = 1, resolveSecretsFirst = true)
        assertTrue(result.isSuccess)
        val exec = result.getOrThrow()
        assertTrue(exec.success)
        assertEquals("hello world", exec.stdout)

        assertNotNull(fakeApi.lastSnippetExecutionRequest)
        assertEquals("secret123", fakeApi.lastSnippetExecutionRequest?.resolvedSecrets?.get("PASSWORD"))
    }

    @Test
    fun clearData_resetsState() = runTest(testDispatcher) {
        fakeApi.hostsList = listOf(TermixHost(id = 1, name = "core"))
        repository.refreshHosts()
        assertFalse(repository.hosts.value.isEmpty())

        repository.clearData()
        assertTrue(repository.hosts.value.isEmpty())
        assertNull(repository.metricsSummary.value)
        assertNull(repository.healthSummary.value)
        assertTrue(repository.liveMetricsByHost.value.isEmpty())
        assertTrue(repository.wolDevices.value.isEmpty())
        assertTrue(repository.snippets.value.isEmpty())
        assertFalse(repository.isLoading.value)
        assertNull(repository.lastError.value)
    }

    private class FakeTermixApi : TermixApi {
        var hostsList: List<TermixHost> = emptyList()
        var metricsSummaryResponse: MetricsSummary = MetricsSummary()
        var healthSummaryResponse: HealthSummary = HealthSummary()
        var liveHostMetricsResponse: LiveHostMetrics = LiveHostMetrics()
        var wolDevicesList: List<WolDevice> = emptyList()
        var snippetsList: List<TermixSnippet> = emptyList()
        var secretResolveResponse: SnippetSecretResolveResult = SnippetSecretResolveResult()
        var snippetExecutionResponse: SnippetExecutionResult = SnippetExecutionResult()

        val startHostMetricsCalledWith = mutableListOf<Long>()
        var failWakeWolWith404 = false
        var lastSnippetExecutionRequest: SnippetExecutionRequest? = null

        override suspend fun getHosts(): List<TermixHost> = hostsList

        override suspend fun getHost(hostId: Long): TermixHost =
            hostsList.firstOrNull { it.id == hostId } ?: TermixHost(id = hostId)

        override suspend fun getDataStatus(): DataStatus = DataStatus(unlocked = true)

        override suspend fun createWsTicket(): WsTicket = WsTicket(ticket = "test_ws_ticket")

        override suspend fun getMetricsSummary(): MetricsSummary = metricsSummaryResponse

        override suspend fun getHealthSummary(): HealthSummary = healthSummaryResponse

        override suspend fun getLiveHostMetrics(hostId: Long): LiveHostMetrics =
            liveHostMetricsResponse.copy(hostId = hostId)

        override suspend fun startHostMetrics(hostId: Long): MetricsCollectionStatus {
            startHostMetricsCalledWith.add(hostId)
            return MetricsCollectionStatus(hostId = hostId, started = true, active = true)
        }

        override suspend fun heartbeatHostMetrics(hostId: Long): MetricsCollectionStatus =
            MetricsCollectionStatus(hostId = hostId, active = true)

        override suspend fun getWolDevices(): List<WolDevice> = wolDevicesList

        override suspend fun wakeWolDevice(hostId: Long): WolWakeResult {
            if (failWakeWolWith404) {
                throw TermixApiException.HttpError(httpStatus = 404, message = "Not found on /devices/{id}/wake")
            }
            return WolWakeResult(
                success = true,
                hostId = hostId,
                endpointUsed = "/plugin-api/wake-on-lan/devices/$hostId/wake",
                message = "WOL sent",
            )
        }

        override suspend fun wakeHost(hostId: Long): WolWakeResult {
            return WolWakeResult(
                success = true,
                hostId = hostId,
                endpointUsed = "/plugin-api/wake-on-lan/host/$hostId/wake",
                message = "Host wake sent",
            )
        }

        override suspend fun getSnippets(): List<TermixSnippet> = snippetsList

        override suspend fun resolveSnippetSecrets(
            request: SnippetSecretResolveRequest,
        ): SnippetSecretResolveResult = secretResolveResponse

        override suspend fun executeSnippet(
            request: SnippetExecutionRequest,
        ): SnippetExecutionResult {
            lastSnippetExecutionRequest = request
            return snippetExecutionResponse.copy(
                snippetId = request.snippetId,
                hostId = request.hostId,
            )
        }
    }
}
