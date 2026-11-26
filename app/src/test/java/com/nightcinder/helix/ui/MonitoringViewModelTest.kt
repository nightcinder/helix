package com.nightcinder.helix.ui

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.MonitoringAlert
import com.nightcinder.helix.core.model.MonitoringSnapshot
import com.nightcinder.helix.core.model.PrometheusHostMetrics
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.WolDevice
import com.nightcinder.helix.core.monitoring.MonitoringRepository
import com.nightcinder.helix.ui.monitoring.buildSourceBadgeLabel
import com.nightcinder.helix.ui.monitoring.formatBytesPerSec
import com.nightcinder.helix.ui.monitoring.formatCapacitySummary
import com.nightcinder.helix.ui.monitoring.formatUptimeDaysHours
import com.nightcinder.helix.ui.state.MonitoringViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class TestMonitoringRepository : MonitoringRepository {
    val _snapshot = MutableStateFlow(MonitoringSnapshot())
    override val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()

    val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    var lastRefreshHosts: List<TermixHost>? = null
    var lastRefreshWolDevices: List<WolDevice>? = null
    var refreshCallCount: Int = 0
    var nextRefreshSnapshot: MonitoringSnapshot? = null

    override suspend fun refresh(
        hosts: List<TermixHost>,
        wolDevices: List<WolDevice>,
    ): Result<MonitoringSnapshot> {
        refreshCallCount++
        lastRefreshHosts = hosts
        lastRefreshWolDevices = wolDevices
        val updated = nextRefreshSnapshot ?: _snapshot.value
        _snapshot.value = updated
        return Result.success(updated)
    }

    override fun clearData() {
        _snapshot.value = MonitoringSnapshot()
        _isLoading.value = false
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringViewModelTest {
    init {
        TestSite.apply()
    }


    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: TestMonitoringRepository
    private lateinit var viewModel: MonitoringViewModel

    private val sampleHosts = listOf(
        TermixHost(id = 10L, name = "core", ip = "192.0.2.10"),
        TermixHost(id = 20L, name = "desktop", ip = "192.0.2.20"),
        TermixHost(id = 30L, name = "server1", ip = "192.0.2.30", hostname = "vcenter.example.com"),
        TermixHost(id = 40L, name = "workstation", ip = "192.0.2.40"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = TestMonitoringRepository()
        viewModel = MonitoringViewModel(fakeRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sourceFiltering_andSeverityTimestampSorting_ordersCorrectly() = runTest {
        val alerts = listOf(
            MonitoringAlert(
                id = "diun-1",
                source = AlertSource.DIUN,
                severity = AlertSeverity.INFO,
                title = "Update: paperless-ngx:3.5",
                summary = "ghcr.io/paperless-ngx/paperless-ngx:3.5",
                targetHostName = "core",
                triggeredAtEpochMs = 5_000L,
            ),
            MonitoringAlert(
                id = "graf-warn-older",
                source = AlertSource.GRAFANA,
                severity = AlertSeverity.WARNING,
                title = "High Memory Usage",
                summary = "Memory > 85%",
                targetHostName = "docker-host",
                triggeredAtEpochMs = 2_000L,
            ),
            MonitoringAlert(
                id = "kuma-crit",
                source = AlertSource.UPTIME_KUMA,
                severity = AlertSeverity.CRITICAL,
                title = "Sunshine Stream Down",
                summary = "monitor_status=0",
                targetHostName = "desktop",
                triggeredAtEpochMs = 3_000L,
            ),
            MonitoringAlert(
                id = "graf-warn-newer",
                source = AlertSource.GRAFANA,
                severity = AlertSeverity.WARNING,
                title = "ESXi Poller Delayed",
                summary = "Scrape lag",
                instance = "vcenter",
                triggeredAtEpochMs = 4_000L,
            ),
        )

        fakeRepo._snapshot.value = MonitoringSnapshot(
            alerts = alerts,
            lastUpdatedEpochMs = 10_000L,
        )
        advanceUntilIdle()

        val stateAll = viewModel.uiState.value
        assertEquals(4, stateAll.activeAlertCount)
        assertTrue(stateAll.hasCriticalAlerts)
        // Expected order: CRITICAL (kuma-crit) -> WARNING newer (graf-warn-newer) -> WARNING older (graf-warn-older) -> INFO (diun-1)
        assertEquals(
            listOf("kuma-crit", "graf-warn-newer", "graf-warn-older", "diun-1"),
            stateAll.filteredAlerts.map { it.id },
        )
        assertEquals(2, stateAll.countForSource(AlertSource.GRAFANA))
        assertEquals(1, stateAll.countForSource(AlertSource.UPTIME_KUMA))
        assertEquals(1, stateAll.countForSource(AlertSource.DIUN))

        // Filter: GRAFANA only
        viewModel.selectSourceFilter(AlertSource.GRAFANA)
        assertEquals(
            listOf("graf-warn-newer", "graf-warn-older"),
            viewModel.uiState.value.filteredAlerts.map { it.id },
        )

        // Filter: UPTIME_KUMA only
        viewModel.selectSourceFilter(AlertSource.UPTIME_KUMA)
        assertEquals(
            listOf("kuma-crit"),
            viewModel.uiState.value.filteredAlerts.map { it.id },
        )

        // Filter: DIUN only
        viewModel.selectSourceFilter(AlertSource.DIUN)
        assertEquals(
            listOf("diun-1"),
            viewModel.uiState.value.filteredAlerts.map { it.id },
        )

        // Reset to All
        viewModel.selectSourceFilter(null)
        assertEquals(4, viewModel.uiState.value.filteredAlerts.size)
    }

    @Test
    fun hasCriticalAlerts_isFalseWhenOnlyWarningsAndInfoExist() = runTest {
        fakeRepo._snapshot.value = MonitoringSnapshot(
            alerts = listOf(
                MonitoringAlert(
                    id = "warn-1",
                    source = AlertSource.GRAFANA,
                    severity = AlertSeverity.WARNING,
                    title = "Disk 80%",
                    summary = "Root volume warning",
                ),
                MonitoringAlert(
                    id = "info-1",
                    source = AlertSource.DIUN,
                    severity = AlertSeverity.INFO,
                    title = "Update: traefik:v3.2",
                    summary = "docker.io/library/traefik:v3.2",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.activeAlertCount)
        assertFalse(viewModel.uiState.value.hasCriticalAlerts)
    }

    @Test
    fun sourceErrors_propagateVerbatimForOffLan403Diagnostics() = runTest {
        val errors = mapOf(
            AlertSource.GRAFANA to "HTTP 403 Forbidden (lan-only@file)",
            AlertSource.PROMETHEUS to "Authentik SSO redirect required",
        )
        fakeRepo._snapshot.value = MonitoringSnapshot(
            alerts = emptyList(),
            sourceErrors = errors,
            lastUpdatedEpochMs = 99_000L,
        )
        advanceUntilIdle()

        assertEquals(errors, viewModel.uiState.value.sourceErrors)
        assertEquals(0, viewModel.uiState.value.activeAlertCount)
    }

    @Test
    fun refresh_forwardsHostsAndWolDevicesToRepository() = runTest {
        val wol = listOf(WolDevice(name = "workstation", macAddress = "02:00:00:00:00:01", isOnline = false))
        val refreshedSnap = MonitoringSnapshot(
            alerts = listOf(
                MonitoringAlert(
                    id = "prom-1",
                    source = AlertSource.PROMETHEUS,
                    severity = AlertSeverity.CRITICAL,
                    title = "TargetDown",
                    summary = "Exporter unreachable",
                    instance = "desktop:9182",
                ),
            ),
            hostMetricsByKey = mapOf(
                "server1" to PrometheusHostMetrics(
                    hostKey = "server1",
                    instanceLabel = "vcenter",
                    isUp = true,
                    cpuPercent = 18.4,
                    memoryPercent = 62.1,
                    sourceJob = "esxi",
                ),
            ),
            lastUpdatedEpochMs = 123_456L,
        )
        fakeRepo.nextRefreshSnapshot = refreshedSnap

        viewModel.refresh(hosts = sampleHosts, wolDevices = wol)
        advanceUntilIdle()

        assertEquals(1, fakeRepo.refreshCallCount)
        assertEquals(sampleHosts, fakeRepo.lastRefreshHosts)
        assertEquals(wol, fakeRepo.lastRefreshWolDevices)
        assertEquals(1, viewModel.uiState.value.activeAlertCount)

        val r440Host = sampleHosts.first { it.name == "server1" }
        val r440Metrics = viewModel.uiState.value.metricsForHost(r440Host)
        assertNotNull(r440Metrics)
        assertEquals("esxi / vcenter", buildSourceBadgeLabel(r440Metrics))
    }

    @Test
    fun resolveAlertTargetHost_mapsTargetHostIdAndHomelabAliases() {
        // 1. Direct targetHostId match
        val byId = MonitoringAlert(
            id = "a1",
            source = AlertSource.DIUN,
            severity = AlertSeverity.INFO,
            title = "Update",
            summary = "img",
            targetHostId = 10L,
        )
        assertEquals(10L, MonitoringViewModel.resolveAlertTargetHost(byId, sampleHosts)?.id)

        // 2. Alias docker-host -> core
        val dockerHostAlert = MonitoringAlert(
            id = "a2",
            source = AlertSource.PROMETHEUS,
            severity = AlertSeverity.WARNING,
            title = "Node Disk",
            summary = "Root 82%",
            targetHostName = "docker-host",
        )
        assertEquals("core", MonitoringViewModel.resolveAlertTargetHost(dockerHostAlert, sampleHosts)?.name)

        // 3. Alias vcenter -> server1 via instance field
        val esxiAlert = MonitoringAlert(
            id = "a3",
            source = AlertSource.GRAFANA,
            severity = AlertSeverity.CRITICAL,
            title = "esxi-poller-down",
            summary = "ESXi poller scrape failed",
            instance = "vcenter:8000",
        )
        assertEquals("server1", MonitoringViewModel.resolveAlertTargetHost(esxiAlert, sampleHosts)?.name)

        // 4. Direct desktop match
        val desktopAlert = MonitoringAlert(
            id = "a4",
            source = AlertSource.UPTIME_KUMA,
            severity = AlertSeverity.CRITICAL,
            title = "Sunshine Down",
            summary = "https://192.0.2.20:47990",
            targetHostName = "desktop",
        )
        assertEquals("desktop", MonitoringViewModel.resolveAlertTargetHost(desktopAlert, sampleHosts)?.name)

        // 5. Unknown external target returns null safely
        val unknownAlert = MonitoringAlert(
            id = "a5",
            source = AlertSource.UPTIME_KUMA,
            severity = AlertSeverity.WARNING,
            title = "External DNS",
            summary = "1.1.1.1",
            targetHostName = "cloudflare-dns",
        )
        assertNull(MonitoringViewModel.resolveAlertTargetHost(unknownAlert, sampleHosts))
    }

    @Test
    fun filteredAlerts_deduplicatesCollidingAlertIdsKeepingHighestPriority() = runTest {
        fakeRepo._snapshot.value = MonitoringSnapshot(
            alerts = listOf(
                MonitoringAlert(
                    id = "dup-kuma-ping",
                    source = AlertSource.UPTIME_KUMA,
                    severity = AlertSeverity.WARNING,
                    title = "Ping degraded (older/lower)",
                    summary = "monitor_status=2",
                    targetHostName = "core",
                    triggeredAtEpochMs = 1_000L,
                ),
                MonitoringAlert(
                    id = "dup-kuma-ping",
                    source = AlertSource.UPTIME_KUMA,
                    severity = AlertSeverity.CRITICAL,
                    title = "Ping down (newer/critical)",
                    summary = "monitor_status=0",
                    targetHostName = "desktop",
                    triggeredAtEpochMs = 2_000L,
                ),
                MonitoringAlert(
                    id = "unique-diun",
                    source = AlertSource.DIUN,
                    severity = AlertSeverity.INFO,
                    title = "Update: caddy:2.9",
                    summary = "docker.io/library/caddy:2.9",
                    targetHostName = "core",
                    triggeredAtEpochMs = 3_000L,
                ),
            ),
        )
        advanceUntilIdle()

        val filtered = viewModel.uiState.value.filteredAlerts
        assertEquals(2, viewModel.uiState.value.activeAlertCount)
        assertEquals(2, filtered.size)
        assertEquals(listOf("dup-kuma-ping", "unique-diun"), filtered.map { it.id })
        // Because deduplication runs after sorting, the CRITICAL / newer alert wins
        assertEquals(AlertSeverity.CRITICAL, filtered.first().severity)
        assertEquals("Ping down (newer/critical)", filtered.first().title)
    }

    @Test
    fun metricsFormattingHelpers_formatDiskNetworkAndUptimeCleanly() {
        val oneGiB = 1024L * 1024L * 1024L
        assertEquals(
            "45.0% (180.0 GB / 400.0 GB)",
            formatCapacitySummary(percent = 45.0, usedBytes = 180L * oneGiB, totalBytes = 400L * oneGiB),
        )
        assertEquals("512 B/s", formatBytesPerSec(512L))
        assertEquals("12.5 MB/s", formatBytesPerSec((12.5 * 1024 * 1024).toLong()))
        assertEquals("14d 3h", formatUptimeDaysHours(14L * 86_400L + 3L * 3_600L + 120L))
    }
}
