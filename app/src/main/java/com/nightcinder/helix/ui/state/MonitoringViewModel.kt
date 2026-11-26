package com.nightcinder.helix.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.MonitoringAlert
import com.nightcinder.helix.core.model.MonitoringSnapshot
import com.nightcinder.helix.core.model.PrometheusHostMetrics
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.WolDevice
import com.nightcinder.helix.core.monitoring.MonitoringRepository
import com.nightcinder.helix.monitoring.HostKeyMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class MonitoringUiState(
    val snapshot: MonitoringSnapshot = MonitoringSnapshot(),
    val selectedSourceFilter: AlertSource? = null,
    val isLoading: Boolean = false,
) {
    private val deduplicatedAlerts: List<MonitoringAlert>
        get() = snapshot.alerts
            .asSequence()
            .sortedWith(
                compareBy<MonitoringAlert> { severityRank(it.severity) }
                    .thenByDescending { it.triggeredAtEpochMs ?: 0L }
                    .thenBy { it.title.lowercase(Locale.US) },
            )
            .distinctBy { it.id }
            .toList()

    val filteredAlerts: List<MonitoringAlert>
        get() = if (selectedSourceFilter == null) {
            deduplicatedAlerts
        } else {
            deduplicatedAlerts.filter { it.source == selectedSourceFilter }
        }

    val activeAlertCount: Int
        get() = deduplicatedAlerts.size

    val hasCriticalAlerts: Boolean
        get() = deduplicatedAlerts.any { it.severity == AlertSeverity.CRITICAL }

    val sourceErrors: Map<AlertSource, String>
        get() = snapshot.sourceErrors

    fun countForSource(source: AlertSource): Int =
        deduplicatedAlerts.count { it.source == source }

    fun metricsForHost(host: TermixHost): PrometheusHostMetrics? =
        snapshot.hostMetricsByKey[HostKeyMatcher.canonicalKeyForHost(host)]

    private fun severityRank(severity: AlertSeverity): Int = when (severity) {
        AlertSeverity.CRITICAL -> 0
        AlertSeverity.WARNING -> 1
        AlertSeverity.INFO -> 2
    }
}

class MonitoringViewModel(
    private val monitoringRepository: MonitoringRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MonitoringUiState(
            snapshot = monitoringRepository.snapshot.value,
            isLoading = monitoringRepository.isLoading.value,
        ),
    )
    val uiState: StateFlow<MonitoringUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            monitoringRepository.snapshot.collect { snap ->
                _uiState.update { it.copy(snapshot = snap) }
            }
        }
        viewModelScope.launch {
            monitoringRepository.isLoading.collect { loading ->
                _uiState.update { it.copy(isLoading = loading) }
            }
        }
    }

    fun selectSourceFilter(source: AlertSource?) {
        _uiState.update { it.copy(selectedSourceFilter = source) }
    }

    fun refresh(
        hosts: List<TermixHost>,
        wolDevices: List<WolDevice> = emptyList(),
    ) {
        viewModelScope.launch {
            monitoringRepository.refresh(hosts = hosts, wolDevices = wolDevices)
        }
    }

    companion object {
        /**
         * Resolves a [MonitoringAlert] to a configured [TermixHost] for tap-to-open-SSH-terminal:
         * 1. Exact `alert.targetHostId` match
         * 2. Canonical alias match on `alert.targetHostName` (from the HELIX_HOST_ALIASES table)
         * 3. Canonical alias match on `alert.instance`
         */
        fun resolveAlertTargetHost(
            alert: MonitoringAlert,
            hosts: List<TermixHost>,
        ): TermixHost? {
            alert.targetHostId?.let { targetId ->
                hosts.firstOrNull { it.id == targetId }?.let { return it }
            }
            HostKeyMatcher.findMatchingHost(hosts, alert.targetHostName)?.let { return it }
            HostKeyMatcher.findMatchingHost(hosts, alert.instance)?.let { return it }
            return null
        }

        fun provideFactory(
            monitoringRepository: MonitoringRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MonitoringViewModel(monitoringRepository) as T
            }
        }
    }
}
