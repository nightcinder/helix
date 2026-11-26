package com.nightcinder.helix.ui

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.api.HomelabRepository
import com.nightcinder.helix.core.auth.AuthManager
import com.nightcinder.helix.core.model.AuthSessionState
import com.nightcinder.helix.core.model.AuthentikConfig
import com.nightcinder.helix.core.model.DataStatus
import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.HomelabDefaults
import com.nightcinder.helix.core.model.SiteConfig
import com.nightcinder.helix.core.model.HostHealthItem
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.LiveHostMetrics
import com.nightcinder.helix.core.model.MetricsCollectionStatus
import com.nightcinder.helix.core.model.MetricsSummary
import com.nightcinder.helix.core.model.MonitoringSnapshot
import com.nightcinder.helix.core.model.PrometheusHostMetrics
import com.nightcinder.helix.core.model.SnippetExecutionRequest
import com.nightcinder.helix.core.model.SnippetExecutionResult
import com.nightcinder.helix.core.model.TermixApiException
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.TermixSnippet
import com.nightcinder.helix.core.model.WolDevice
import com.nightcinder.helix.core.model.WolWakeResult
import com.nightcinder.helix.core.api.WsTicket
import com.nightcinder.helix.core.monitoring.MonitoringRepository
import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.DisplayEnvironmentProbe
import com.nightcinder.helix.core.spatial.ExternalDisplayDescriptor
import com.nightcinder.helix.core.spatial.UsbDeviceDescriptor
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import com.nightcinder.helix.ui.state.DashboardViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeAuthManager : AuthManager {
    val _sessionState = MutableStateFlow(AuthSessionState())
    override val sessionState: StateFlow<AuthSessionState> = _sessionState.asStateFlow()

    var passkeyCalled = false
    var silentRefreshCalled = false
    var oidcCallbackUriCalled: String? = null
    var signOutCalled = false

    override suspend fun initialize() {}

    override suspend fun signInWithPasskey(config: AuthentikConfig?): Result<AuthSessionState> {
        passkeyCalled = true
        val state = _sessionState.value.copy(
            isAuthenticated = true,
            isDekUnlocked = true,
            username = "user",
        )
        _sessionState.value = state
        return Result.success(state)
    }

    override suspend fun refreshOutpostCookies(force: Boolean): Result<AuthSessionState> {
        silentRefreshCalled = true
        return Result.success(_sessionState.value)
    }

    override suspend fun checkDataStatus(): Result<DataStatus> {
        return Result.success(DataStatus(unlocked = true))
    }

    override suspend fun handleDataExpired(): Result<AuthSessionState> {
        return Result.success(_sessionState.value)
    }

    override suspend fun onOidcCallbackUri(callbackUri: String): Result<AuthSessionState> {
        oidcCallbackUriCalled = callbackUri
        val state = _sessionState.value.copy(
            isAuthenticated = true,
            isDekUnlocked = true,
            username = "user",
        )
        _sessionState.value = state
        return Result.success(state)
    }

    override suspend fun updateConfig(config: AuthentikConfig) {
        _sessionState.value = _sessionState.value.copy(config = config)
    }

    override suspend fun signOut() {
        signOutCalled = true
        _sessionState.value = AuthSessionState()
    }
}

class FakeHomelabRepository : HomelabRepository {
    val _hosts = MutableStateFlow<List<TermixHost>>(emptyList())
    override val hosts: StateFlow<List<TermixHost>> = _hosts.asStateFlow()

    val _metricsSummary = MutableStateFlow<MetricsSummary?>(null)
    override val metricsSummary: StateFlow<MetricsSummary?> = _metricsSummary.asStateFlow()

    val _healthSummary = MutableStateFlow<HealthSummary?>(null)
    override val healthSummary: StateFlow<HealthSummary?> = _healthSummary.asStateFlow()

    val _liveMetricsByHost = MutableStateFlow<Map<Long, LiveHostMetrics>>(emptyMap())
    override val liveMetricsByHost: StateFlow<Map<Long, LiveHostMetrics>> = _liveMetricsByHost.asStateFlow()

    val _wolDevices = MutableStateFlow<List<WolDevice>>(emptyList())
    override val wolDevices: StateFlow<List<WolDevice>> = _wolDevices.asStateFlow()

    val _snippets = MutableStateFlow<List<TermixSnippet>>(emptyList())
    override val snippets: StateFlow<List<TermixSnippet>> = _snippets.asStateFlow()

    val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val _lastError = MutableStateFlow<TermixApiException?>(null)
    override val lastError: StateFlow<TermixApiException?> = _lastError.asStateFlow()

    var wakeDeviceCalledWith: Triple<String?, String?, Long?>? = null
    var executeSnippetCalledWith: Pair<Long, Long>? = null

    override suspend fun refreshDashboard(): Result<Unit> = Result.success(Unit)

    override suspend fun refreshHosts(): Result<List<TermixHost>> = Result.success(_hosts.value)

    override suspend fun refreshHostLiveMetrics(hostId: Long, ensureStarted: Boolean): Result<LiveHostMetrics> {
        val metrics = LiveHostMetrics(hostId = hostId, cpuPercent = 15.0, memoryPercent = 42.0)
        _liveMetricsByHost.value = _liveMetricsByHost.value + (hostId to metrics)
        return Result.success(metrics)
    }

    override suspend fun heartbeatHostMetrics(hostId: Long): Result<MetricsCollectionStatus> {
        return Result.success(MetricsCollectionStatus(hostId = hostId, started = true, active = true))
    }

    override suspend fun refreshWolDevices(): Result<List<WolDevice>> = Result.success(_wolDevices.value)

    override suspend fun wakeDevice(macAddress: String?, hostName: String?, hostId: Long?): Result<WolWakeResult> {
        wakeDeviceCalledWith = Triple(macAddress, hostName, hostId)
        return Result.success(
            WolWakeResult(
                success = true,
                deviceName = hostName ?: "Target Host",
                macAddress = macAddress,
                endpointUsed = "/plugin-api/wake-on-lan/devices/wake"
            )
        )
    }

    override suspend fun refreshSnippets(): Result<List<TermixSnippet>> = Result.success(_snippets.value)

    override suspend fun executeSnippet(snippetId: Long, hostId: Long, resolveSecretsFirst: Boolean): Result<SnippetExecutionResult> {
        executeSnippetCalledWith = Pair(snippetId, hostId)
        return Result.success(
            SnippetExecutionResult(
                snippetId = snippetId,
                hostId = hostId,
                success = true,
                exitCode = 0,
                stdout = "uptime: 14 days"
            )
        )
    }

    override suspend fun createWsTicket(): Result<WsTicket> {
        return Result.success(WsTicket(ticket = "test_ticket"))
    }

    override fun clearData() {
        _hosts.value = emptyList()
        _metricsSummary.value = null
        _healthSummary.value = null
        _liveMetricsByHost.value = emptyMap()
        _wolDevices.value = emptyList()
        _snippets.value = emptyList()
    }
}

class FakeDisplayProbe(
    var xrFeaturePresent: Boolean = false,
) : DisplayEnvironmentProbe {
    override fun hasSystemFeature(featureName: String): Boolean {
        return if (featureName == DisplayBackendDetector.FEATURE_XR_API_SPATIAL) xrFeaturePresent else false
    }

    override fun connectedUsbDevices(): List<UsbDeviceDescriptor> = emptyList()

    override fun externalPresentationDisplays(): List<ExternalDisplayDescriptor> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    init {
        TestSite.apply()
    }


    private val testDispatcher = StandardTestDispatcher()
    private lateinit var authManager: FakeAuthManager
    private lateinit var repository: FakeHomelabRepository
    private lateinit var displayProbe: FakeDisplayProbe
    private lateinit var displayDetector: DisplayBackendDetector
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authManager = FakeAuthManager()
        repository = FakeHomelabRepository()
        displayProbe = FakeDisplayProbe(xrFeaturePresent = false)
        displayDetector = DisplayBackendDetector(displayProbe)

        viewModel = DashboardViewModel(
            authManager = authManager,
            repository = repository,
            displayBackendDetector = displayDetector,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun wakeDefaultHost_wakesConfiguredQuickWakeHost() = runTest {
        viewModel.wakeDefaultHost()
        advanceUntilIdle()
        assertEquals(Triple(TestSite.WOL_MAC, "workstation", null), repository.wakeDeviceCalledWith)
    }

    @Test
    fun wakeDefaultHost_withoutConfigurationReportsErrorAndDoesNotWake() = runTest {
        SiteConfig.wolHost = ""
        SiteConfig.wolMac = ""
        try {
            viewModel.wakeDefaultHost()
            advanceUntilIdle()
            assertEquals(null, repository.wakeDeviceCalledWith)
            assertEquals("No Wake-on-LAN host configured", viewModel.uiState.value.errorMessage)
        } finally {
            TestSite.apply()
        }
    }
    @Test
    fun initialState_hasDefaultBackendAndSignedOut() = runTest {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(WorkspaceDisplayBackend.Standard2D, state.displayBackend)
        assertEquals(false, state.authState.isAuthenticated)
    }

    @Test
    fun displayBackendDetector_updatesWhenXrPresent() = runTest {
        displayProbe.xrFeaturePresent = true
        viewModel.detectDisplayBackend()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.displayBackend is WorkspaceDisplayBackend.AndroidXrSpatial)
    }

    @Test
    fun signInWithPasskey_updatesAuthenticatedState() = runTest {
        viewModel.signInWithPasskey()
        advanceUntilIdle()

        assertTrue(authManager.passkeyCalled)
        assertTrue(viewModel.uiState.value.authState.isAuthenticated)
        assertEquals("user", viewModel.uiState.value.authState.username)
    }

    @Test
    fun onOidcCallbackUri_handlesIntentAndAuthenticates() = runTest {
        val oidcUri = "termix-mobile://oidc-callback?code=xyz"
        viewModel.onOidcCallbackUri(oidcUri)
        advanceUntilIdle()

        assertEquals(oidcUri, authManager.oidcCallbackUriCalled)
        assertTrue(viewModel.uiState.value.authState.isAuthenticated)
    }

    @Test
    fun hostSelection_andDynamicWolWake_resolvesCorrectDevice() = runTest {
        val workstationHost = TermixHost(
            id = 42L,
            name = "workstation",
            ip = "192.168.1.50",
            status = HostStatus.OFFLINE,
            macAddress = HomelabDefaults.WOL_MAC,
        )
        repository._hosts.value = listOf(workstationHost)
        advanceUntilIdle()

        assertEquals(42L, viewModel.uiState.value.selectedHostId)

        viewModel.wakeHost(workstationHost)
        advanceUntilIdle()

        val wakeTarget = repository.wakeDeviceCalledWith
        assertNotNull(wakeTarget)
        assertEquals(HomelabDefaults.WOL_MAC, wakeTarget?.first)
        assertEquals(true, viewModel.uiState.value.wolStatus?.success)
    }

    @Test
    fun executeSnippet_runsSnippetAgainstSelectedHost() = runTest {
        val host = TermixHost(id = 10L, name = "core", ip = "192.168.1.10")
        val snippet = TermixSnippet(id = 101L, name = "Check Uptime", command = "uptime")
        repository._hosts.value = listOf(host)
        repository._snippets.value = listOf(snippet)
        advanceUntilIdle()

        viewModel.selectHost(10L)
        viewModel.executeSnippet(101L, 10L)
        advanceUntilIdle()

        assertEquals(Pair(101L, 10L), repository.executeSnippetCalledWith)
        assertNotNull(viewModel.uiState.value.lastSnippetOutput)
        assertEquals(true, viewModel.uiState.value.lastSnippetOutput?.success)
    }

    @Test
    fun signOut_clearsDataAndSession() = runTest {
        viewModel.signInWithPasskey()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.authState.isAuthenticated)

        viewModel.signOut()
        advanceUntilIdle()

        assertTrue(authManager.signOutCalled)
        assertEquals(false, viewModel.uiState.value.authState.isAuthenticated)
    }

    @Test
    fun monitoringMerge_populatesDiskGpuEsxiMetricsAndResolvesUnknownHostStatuses() = runTest {
        val fakeMonitoring = FakeMonitoringRepository()
        val vmWithMonitoring = DashboardViewModel(
            authManager = authManager,
            repository = repository,
            displayBackendDetector = displayDetector,
            monitoringRepository = fakeMonitoring,
        )

        repository._hosts.value = listOf(
            TermixHost(id = 1L, name = "core", ip = "192.0.2.10", status = HostStatus.ONLINE),
            TermixHost(id = 2L, name = "desktop", ip = "192.0.2.20", status = HostStatus.UNKNOWN),
            TermixHost(id = 3L, name = "server1", ip = "192.0.2.30", status = HostStatus.UNKNOWN),
            TermixHost(id = 4L, name = "workstation", ip = "192.0.2.40", status = HostStatus.UNKNOWN),
        )
        // Termix live metrics for core lacks disk and GPU
        repository._liveMetricsByHost.value = mapOf(
            1L to LiveHostMetrics(hostId = 1L, hostName = "core", cpuPercent = 20.0, memoryPercent = 55.0),
        )
        fakeMonitoring._snapshot.value = MonitoringSnapshot(
            hostMetricsByKey = mapOf(
                "core" to PrometheusHostMetrics(
                    hostKey = "core",
                    instanceLabel = "docker-host",
                    isUp = true,
                    diskPercent = 64.0,
                    diskUsedBytes = 640_000_000_000L,
                    diskTotalBytes = 1_000_000_000_000L,
                    gpuUtilizationPercent = 38.0,
                    gpuTemperatureCelsius = 52.0,
                ),
                "desktop" to PrometheusHostMetrics(
                    hostKey = "desktop",
                    instanceLabel = "desktop",
                    isUp = true,
                    cpuPercent = 12.0,
                    memoryPercent = 44.0,
                    diskPercent = 71.5,
                    diskUsedBytes = 1_430_000_000_000L,
                    diskTotalBytes = 2_000_000_000_000L,
                ),
                "server1" to PrometheusHostMetrics(
                    hostKey = "server1",
                    instanceLabel = "vcenter",
                    isUp = true,
                    cpuPercent = 9.5,
                    memoryPercent = 41.0,
                    uptimeSeconds = 500_000L,
                ),
            ),
            hostUpByKey = mapOf(
                "core" to true,
                "desktop" to true,
                "server1" to true,
                "workstation" to false,
            ),
            lastUpdatedEpochMs = 1_763_940_000_000L,
        )

        advanceUntilIdle()

        val state = vmWithMonitoring.uiState.value
        val byId = state.hosts.associateBy { it.id }

        // No more grey UNKNOWN dots!
        assertEquals(HostStatus.ONLINE, byId[1L]?.status)
        assertEquals(HostStatus.ONLINE, byId[2L]?.status)
        assertEquals(HostStatus.ONLINE, byId[3L]?.status)
        assertEquals(HostStatus.OFFLINE, byId[4L]?.status)

        // core retains Termix CPU/RAM and gains Prometheus Disk + GPU
        val coreMetrics = state.liveMetrics[1L]
        assertNotNull(coreMetrics)
        assertEquals(20.0, coreMetrics!!.cpuPercent!!, 0.01)
        assertEquals(64.0, coreMetrics.diskPercent!!, 0.01)
        assertEquals(640_000_000_000L, coreMetrics.diskUsedBytes)
        assertEquals(1, coreMetrics.gpus.size)
        assertEquals(38.0, coreMetrics.gpus.first().utilizationPercent!!, 0.01)

        // desktop and server1 receive full Prometheus metrics including Disk C: and ESXi uptime
        val desktopMetrics = state.liveMetrics[2L]
        assertNotNull(desktopMetrics)
        assertEquals(71.5, desktopMetrics!!.diskPercent!!, 0.01)

        val r440Metrics = state.liveMetrics[3L]
        assertNotNull(r440Metrics)
        assertEquals(9.5, r440Metrics!!.cpuPercent!!, 0.01)
        assertEquals(500_000L, r440Metrics.uptimeSeconds)

        // refreshHomelabData invokes monitoringRepository.refresh
        vmWithMonitoring.refreshHomelabData()
        advanceUntilIdle()
        assertEquals(1, fakeMonitoring.refreshCalls)
    }

    @Test
    fun monitoringMerge_nonLinuxZeroTermixSampleDoesNotClobberPrometheusTelemetry() = runTest {
        val fakeMonitoring = FakeMonitoringRepository()
        repository._hosts.value = listOf(
            TermixHost(id = 2L, name = "desktop", ip = "192.0.2.20", status = HostStatus.ONLINE),
            TermixHost(id = 3L, name = "server1", ip = "192.0.2.30", status = HostStatus.ONLINE),
        )
        // Simulate Termix /proc probe on Windows (desktop) and ESXi (server1) returning 0s
        repository._liveMetricsByHost.value = mapOf(
            2L to LiveHostMetrics(
                hostId = 2L,
                hostName = "desktop",
                cpuPercent = 0.0,
                memoryPercent = null,
                memoryUsedBytes = 0L,
                memoryTotalBytes = 0L,
                diskUsedBytes = 0L,
                diskTotalBytes = 0L,
                uptimeSeconds = 0L,
            ),
            3L to LiveHostMetrics(
                hostId = 3L,
                hostName = "server1",
                cpuPercent = 0.0,
                memoryUsedBytes = 0L,
                memoryTotalBytes = 0L,
                uptimeSeconds = 0L,
            ),
        )
        fakeMonitoring._snapshot.value = MonitoringSnapshot(
            hostMetricsByKey = mapOf(
                "desktop" to PrometheusHostMetrics(
                    hostKey = "desktop",
                    instanceLabel = "desktop",
                    isUp = true,
                    cpuPercent = 27.5,
                    memoryPercent = 50.0,
                    memoryUsedBytes = 17_179_869_184L,
                    memoryTotalBytes = 34_359_738_368L,
                    diskPercent = 72.0,
                    diskUsedBytes = 1_440_000_000_000L,
                    diskTotalBytes = 2_000_000_000_000L,
                ),
                "server1" to PrometheusHostMetrics(
                    hostKey = "server1",
                    instanceLabel = "vcenter",
                    isUp = true,
                    cpuPercent = 14.2,
                    memoryPercent = 48.0,
                    uptimeSeconds = 864_000L,
                ),
            ),
        )

        val vm = DashboardViewModel(
            authManager = authManager,
            repository = repository,
            displayBackendDetector = displayDetector,
            monitoringRepository = fakeMonitoring,
        )
        // Cold-start refresh before selectedHostId is populated refreshes first host
        vm.refreshHomelabData()
        advanceUntilIdle()

        // Even if refreshHostLiveMetrics wrote another sample, simulate non-Linux 0 sample on selectHost(3L)
        repository._liveMetricsByHost.value = repository._liveMetricsByHost.value + (
            2L to LiveHostMetrics(
                hostId = 2L,
                hostName = "desktop",
                cpuPercent = 0.0,
                memoryUsedBytes = 0L,
                memoryTotalBytes = 0L,
                diskUsedBytes = 0L,
                diskTotalBytes = 0L,
                uptimeSeconds = 0L,
            )
        )
        advanceUntilIdle()

        val desktop = vm.uiState.value.liveMetrics[2L]!!
        assertEquals(27.5, desktop.cpuPercent!!, 0.01)
        assertEquals(50.0, desktop.memoryPercent!!, 0.01)
        assertEquals(17_179_869_184L, desktop.memoryUsedBytes)
        assertEquals(34_359_738_368L, desktop.memoryTotalBytes)
        assertEquals(72.0, desktop.diskPercent!!, 0.01)
        assertEquals(2_000_000_000_000L, desktop.diskTotalBytes)

        val server1 = vm.uiState.value.liveMetrics[3L]!!
        assertEquals(14.2, server1.cpuPercent!!, 0.01)
        assertEquals(48.0, server1.memoryPercent!!, 0.01)
        assertEquals(864_000L, server1.uptimeSeconds)
    }
}

private class FakeMonitoringRepository : MonitoringRepository {
    val _snapshot = MutableStateFlow(MonitoringSnapshot())
    override val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()

    val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    var refreshCalls = 0

    override suspend fun refresh(hosts: List<TermixHost>, wolDevices: List<WolDevice>): Result<MonitoringSnapshot> {
        refreshCalls++
        return Result.success(_snapshot.value)
    }

    override fun clearData() {
        _snapshot.value = MonitoringSnapshot()
    }
}
