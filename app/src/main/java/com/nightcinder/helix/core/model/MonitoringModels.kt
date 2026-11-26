package com.nightcinder.helix.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AlertSource {
    GRAFANA,
    UPTIME_KUMA,
    DIUN,
    PROMETHEUS,
}

@Serializable
enum class AlertSeverity {
    CRITICAL,
    WARNING,
    INFO,
}

@Serializable
data class MonitoringAlert(
    val id: String,
    val source: AlertSource,
    val severity: AlertSeverity,
    val title: String,
    val summary: String,
    val targetHostName: String? = null,
    val targetHostId: Long? = null,
    val instance: String? = null,
    val triggeredAtEpochMs: Long? = null,
    val state: String = "firing",
)

@Serializable
data class PrometheusHostMetrics(
    val hostKey: String,
    val instanceLabel: String,
    val isUp: Boolean? = null,
    val cpuPercent: Double? = null,
    val memoryPercent: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    val diskPercent: Double? = null,
    val diskUsedBytes: Long? = null,
    val diskTotalBytes: Long? = null,
    val networkRxBytesPerSec: Long? = null,
    val networkTxBytesPerSec: Long? = null,
    val uptimeSeconds: Long? = null,
    val gpuUtilizationPercent: Double? = null,
    val gpuTemperatureCelsius: Double? = null,
    val gpuMemoryUsedBytes: Long? = null,
    val gpuMemoryTotalBytes: Long? = null,
    val gpuPowerWatts: Double? = null,
    val sourceJob: String = "",
)

@Serializable
data class MonitoringSnapshot(
    val alerts: List<MonitoringAlert> = emptyList(),
    val hostMetricsByKey: Map<String, PrometheusHostMetrics> = emptyMap(),
    val hostUpByKey: Map<String, Boolean> = emptyMap(),
    val sourceErrors: Map<AlertSource, String> = emptyMap(),
    val lastUpdatedEpochMs: Long? = null,
)

class MonitoringSourceException(
    val source: AlertSource,
    override val message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
