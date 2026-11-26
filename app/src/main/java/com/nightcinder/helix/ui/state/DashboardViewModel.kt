package com.nightcinder.helix.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nightcinder.helix.core.api.HomelabRepository
import com.nightcinder.helix.core.auth.AuthManager
import com.nightcinder.helix.core.model.AuthSessionState
import com.nightcinder.helix.core.model.AuthentikConfig
import com.nightcinder.helix.core.model.GpuMetrics
import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.HomelabDefaults
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.LiveHostMetrics
import com.nightcinder.helix.core.model.MonitoringSnapshot
import com.nightcinder.helix.core.model.PrometheusHostMetrics
import com.nightcinder.helix.core.model.SnippetExecutionResult
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.TermixSnippet
import com.nightcinder.helix.core.model.WolDevice
import com.nightcinder.helix.core.model.WolWakeResult
import com.nightcinder.helix.core.monitoring.MonitoringRepository
import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import com.nightcinder.helix.monitoring.HostKeyMatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val authState: AuthSessionState = AuthSessionState(),
    val displayBackend: WorkspaceDisplayBackend = WorkspaceDisplayBackend.Standard2D,
    val hosts: List<TermixHost> = emptyList(),
    val selectedHostId: Long? = null,
    val liveMetrics: Map<Long, LiveHostMetrics> = emptyMap(),
    val healthSummary: HealthSummary? = null,
    val wolDevices: List<WolDevice> = emptyList(),
    val snippets: List<TermixSnippet> = emptyList(),
    val lastSnippetOutput: SnippetExecutionResult? = null,
    val wolStatus: WolWakeResult? = null,
    val monitoringSnapshot: MonitoringSnapshot = MonitoringSnapshot(),
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
) {
    val selectedHost: TermixHost?
        get() = hosts.firstOrNull { it.id == selectedHostId } ?: hosts.firstOrNull()

    val selectedHostMetrics: LiveHostMetrics?
        get() = selectedHost?.let { liveMetrics[it.id] }
}

class DashboardViewModel(
    private val authManager: AuthManager,
    private val repository: HomelabRepository,
    private val displayBackendDetector: DisplayBackendDetector,
    private val monitoringRepository: MonitoringRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var metricsHeartbeatJob: Job? = null

    init {
        detectDisplayBackend()
        observeAuthSession()
        observeHomelabData()
    }

    fun detectDisplayBackend() {
        val backend = displayBackendDetector.detect()
        _uiState.update { it.copy(displayBackend = backend) }
    }

    private fun observeAuthSession() {
        viewModelScope.launch {
            authManager.sessionState.collect { session ->
                _uiState.update { current ->
                    current.copy(
                        authState = session,
                        errorMessage = session.lastError?.message ?: current.errorMessage
                    )
                }
                if (session.isAuthenticated) {
                    refreshHomelabData()
                }
            }
        }
    }

    private fun observeHomelabData() {
        val monitoringFlow = monitoringRepository?.snapshot ?: flowOf(MonitoringSnapshot())
        viewModelScope.launch {
            combine(
                repository.hosts,
                repository.liveMetricsByHost,
                monitoringFlow,
            ) { rawHosts, rawMetrics, monitoring ->
                val mergedHosts = mergeHostStatuses(rawHosts, monitoring.hostUpByKey)
                val mergedMetrics = mergeHostMetrics(mergedHosts, rawMetrics, monitoring)
                Triple(mergedHosts, mergedMetrics, monitoring)
            }.collect { (mergedHosts, mergedMetrics, monitoring) ->
                _uiState.update { current ->
                    val nextSelected = when {
                        current.selectedHostId != null && mergedHosts.any { it.id == current.selectedHostId } ->
                            current.selectedHostId
                        mergedHosts.isNotEmpty() ->
                            mergedHosts.first().id
                        else -> null
                    }
                    current.copy(
                        hosts = mergedHosts,
                        selectedHostId = nextSelected,
                        liveMetrics = mergedMetrics,
                        monitoringSnapshot = monitoring,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.healthSummary.collect { health ->
                _uiState.update { it.copy(healthSummary = health) }
            }
        }
        viewModelScope.launch {
            repository.wolDevices.collect { devices ->
                _uiState.update { it.copy(wolDevices = devices) }
            }
        }
        viewModelScope.launch {
            repository.snippets.collect { snippetList ->
                _uiState.update { it.copy(snippets = snippetList) }
            }
        }
        viewModelScope.launch {
            repository.isLoading.collect { loading ->
                _uiState.update { it.copy(isLoading = loading) }
            }
        }
        viewModelScope.launch {
            repository.lastError.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(errorMessage = error.message) }
                }
            }
        }
    }

    fun signInWithPasskey(config: AuthentikConfig? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authManager.signInWithPasskey(config)
            _uiState.update { it.copy(isLoading = false) }
            result.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun silentRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authManager.refreshOutpostCookies(force = true)
            _uiState.update { it.copy(isLoading = false) }
            result.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun onOidcCallbackUri(callbackUri: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authManager.onOidcCallbackUri(callbackUri)
            _uiState.update { it.copy(isLoading = false) }
            result.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun updateAuthentikConfig(config: AuthentikConfig) {
        viewModelScope.launch {
            authManager.updateConfig(config)
        }
    }

    fun selectHost(hostId: Long) {
        _uiState.update { it.copy(selectedHostId = hostId) }
        refreshMetricsForHost(hostId)
    }

    fun refreshHomelabData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.refreshDashboard()
            (_uiState.value.selectedHostId ?: repository.hosts.value.firstOrNull()?.id)?.let { hostId ->
                repository.refreshHostLiveMetrics(hostId)
            }
            monitoringRepository?.refresh(
                hosts = repository.hosts.value,
                wolDevices = repository.wolDevices.value,
            )
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun refreshMetricsForHost(hostId: Long) {
        viewModelScope.launch {
            repository.refreshHostLiveMetrics(hostId)
        }
    }

    /**
     * Triggers dynamic Wake-on-LAN for a host.
     * Runtime resolution looks up the host's MAC (Termix WoL device or HELIX_WOL_MAC profile) or hostname dynamically.
     */
    fun wakeHost(host: TermixHost) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, wolStatus = null) }
            val targetMac = host.macAddress ?: host.effectiveHomelabProfile?.wolMacAddress
            val result = repository.wakeDevice(
                macAddress = targetMac,
                hostName = host.displayName,
                hostId = host.id
            )
            _uiState.update { it.copy(isLoading = false) }
            result.fold(
                onSuccess = { wakeResult ->
                    _uiState.update { it.copy(wolStatus = wakeResult) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = "WOL failed: ${error.message}") }
                }
            )
        }
    }

    /** Wakes the configured quick-wake workstation (HELIX_WOL_HOST / HELIX_WOL_MAC). */
    fun wakeDefaultHost() {
        if (HomelabDefaults.WOL_HOST.isBlank() && HomelabDefaults.WOL_MAC.isBlank()) {
            _uiState.update { it.copy(errorMessage = "No Wake-on-LAN host configured") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, wolStatus = null) }
            val result = repository.wakeDevice(
                macAddress = HomelabDefaults.WOL_MAC.takeIf { it.isNotBlank() },
                hostName = HomelabDefaults.WOL_HOST.takeIf { it.isNotBlank() }
            )
            _uiState.update { it.copy(isLoading = false) }
            result.fold(
                onSuccess = { wakeResult ->
                    _uiState.update { it.copy(wolStatus = wakeResult) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = "Failed to wake ${HomelabDefaults.WOL_HOST.ifBlank { "host" }}: ${error.message}") }
                }
            )
        }
    }

    fun executeSnippet(snippetId: Long, hostId: Long? = _uiState.value.selectedHostId) {
        if (hostId == null) {
            _uiState.update { it.copy(errorMessage = "No host selected for snippet execution") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.executeSnippet(snippetId = snippetId, hostId = hostId)
            _uiState.update { it.copy(isLoading = false) }
            result.fold(
                onSuccess = { execResult ->
                    _uiState.update { it.copy(lastSnippetOutput = execResult) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = "Snippet execution failed: ${error.message}") }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearWolStatus() {
        _uiState.update { it.copy(wolStatus = null) }
    }

    fun clearSnippetOutput() {
        _uiState.update { it.copy(lastSnippetOutput = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            repository.clearData()
            monitoringRepository?.clearData()
            _uiState.update {
                DashboardUiState(
                    displayBackend = it.displayBackend,
                    authState = it.authState.copy(isAuthenticated = false)
                )
            }
        }
    }

    private fun mergeHostStatuses(
        hosts: List<TermixHost>,
        hostUpByKey: Map<String, Boolean>,
    ): List<TermixHost> {
        if (hostUpByKey.isEmpty()) return hosts
        return hosts.map { host ->
            val key = HostKeyMatcher.canonicalKeyForHost(host)
            val isUp = hostUpByKey[key] ?: return@map host
            val resolved = when {
                host.status == HostStatus.UNKNOWN -> if (isUp) HostStatus.ONLINE else HostStatus.OFFLINE
                host.status == HostStatus.OFFLINE && isUp -> HostStatus.ONLINE
                else -> host.status
            }
            if (resolved != host.status) host.copy(status = resolved) else host
        }
    }

    private fun mergeHostMetrics(
        hosts: List<TermixHost>,
        rawMetrics: Map<Long, LiveHostMetrics>,
        monitoring: MonitoringSnapshot,
    ): Map<Long, LiveHostMetrics> {
        if (monitoring.hostMetricsByKey.isEmpty()) return rawMetrics
        val merged = rawMetrics.toMutableMap()
        for (host in hosts) {
            val key = HostKeyMatcher.canonicalKeyForHost(host)
            val prom: PrometheusHostMetrics = monitoring.hostMetricsByKey[key] ?: continue
            val existing = merged[host.id]
            val promGpus = if (
                prom.gpuUtilizationPercent != null ||
                prom.gpuTemperatureCelsius != null ||
                prom.gpuMemoryUsedBytes != null ||
                prom.gpuMemoryTotalBytes != null ||
                prom.gpuPowerWatts != null
            ) {
                listOf(
                    GpuMetrics(
                        index = 0,
                        name = "NVIDIA GPU",
                        utilizationPercent = prom.gpuUtilizationPercent,
                        memoryUsedBytes = prom.gpuMemoryUsedBytes,
                        memoryTotalBytes = prom.gpuMemoryTotalBytes,
                        temperatureCelsius = prom.gpuTemperatureCelsius,
                        powerWatts = prom.gpuPowerWatts,
                    ),
                )
            } else {
                emptyList()
            }

            val base = existing ?: LiveHostMetrics(
                hostId = host.id,
                hostName = host.displayName,
            )
            // Termix's /proc probe yields memoryTotalBytes=0L, cpu=0.0, uptime=0L on non-Linux hosts (Windows/ESXi).
            val hasValidTermixSample = base.memoryTotalBytes == null || base.memoryTotalBytes > 0L
            val hasValidTermixMemory = base.memoryTotalBytes != null && base.memoryTotalBytes > 0L
            val hasValidTermixDisk = base.diskTotalBytes != null && base.diskTotalBytes > 0L

            merged[host.id] = base.copy(
                cpuPercent = if (hasValidTermixSample) (base.cpuPercent ?: prom.cpuPercent) else (prom.cpuPercent ?: base.cpuPercent),
                memoryPercent = if (hasValidTermixSample) (base.memoryPercent ?: prom.memoryPercent) else (prom.memoryPercent ?: base.memoryPercent),
                memoryUsedBytes = if (hasValidTermixMemory) (base.memoryUsedBytes ?: prom.memoryUsedBytes) else prom.memoryUsedBytes,
                memoryTotalBytes = if (hasValidTermixMemory) base.memoryTotalBytes else prom.memoryTotalBytes,
                diskPercent = if (hasValidTermixDisk || base.diskTotalBytes == null) (base.diskPercent ?: prom.diskPercent) else (prom.diskPercent ?: base.diskPercent),
                diskUsedBytes = if (hasValidTermixDisk) (base.diskUsedBytes ?: prom.diskUsedBytes) else prom.diskUsedBytes,
                diskTotalBytes = if (hasValidTermixDisk) base.diskTotalBytes else prom.diskTotalBytes,
                networkRxBytesPerSec = if (hasValidTermixSample) (base.networkRxBytesPerSec ?: prom.networkRxBytesPerSec) else (prom.networkRxBytesPerSec ?: base.networkRxBytesPerSec),
                networkTxBytesPerSec = if (hasValidTermixSample) (base.networkTxBytesPerSec ?: prom.networkTxBytesPerSec) else (prom.networkTxBytesPerSec ?: base.networkTxBytesPerSec),
                uptimeSeconds = if (hasValidTermixSample) (base.uptimeSeconds?.takeIf { it > 0L } ?: prom.uptimeSeconds ?: base.uptimeSeconds) else (prom.uptimeSeconds ?: base.uptimeSeconds),
                gpus = base.gpus.takeIf { it.isNotEmpty() } ?: promGpus,
                collectedAtEpochMs = base.collectedAtEpochMs ?: monitoring.lastUpdatedEpochMs,
            )
        }
        return merged
    }

    companion object {
        fun provideFactory(
            authManager: AuthManager,
            repository: HomelabRepository,
            displayBackendDetector: DisplayBackendDetector,
            monitoringRepository: MonitoringRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(
                    authManager = authManager,
                    repository = repository,
                    displayBackendDetector = displayBackendDetector,
                    monitoringRepository = monitoringRepository,
                ) as T
            }
        }
    }
}
