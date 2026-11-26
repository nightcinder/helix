package com.nightcinder.helix.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MetricsSummary(
    @JsonNames("hosts", "items", "data")
    val hosts: List<HostMetricsOverview> = emptyList(),
    val totalHosts: Int = 0,
    val onlineHosts: Int = 0,
    val timestampEpochMs: Long? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HostMetricsOverview(
    val hostId: Long = 0L,
    val hostName: String? = null,
    @JsonNames("cpu", "cpuPercent", "cpu_percent", "cpuUsage")
    val cpuPercent: Double? = null,
    @JsonNames("memory", "memoryPercent", "memory_percent", "memPercent", "ramPercent")
    val memoryPercent: Double? = null,
    @JsonNames("disk", "diskPercent", "disk_percent")
    val diskPercent: Double? = null,
    val uptimeSeconds: Long? = null,
    val status: String? = null,
    val lastUpdatedEpochMs: Long? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HealthSummary(
    @JsonNames("hosts", "items", "data")
    val hosts: List<HostHealthItem> = emptyList(),
    val healthyCount: Int = 0,
    val unhealthyCount: Int = 0,
    val offlineCount: Int = 0,
    val timestampEpochMs: Long? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HostHealthItem(
    val hostId: Long = 0L,
    val hostName: String? = null,
    val healthy: Boolean = false,
    // Termix host-metrics reports the worst dashboard check as "level": ok | warn | crit.
    @JsonNames("level")
    val status: String? = null,
    val latencyMs: Long? = null,
    val lastCheckedEpochMs: Long? = null,
    val message: String? = null,
) {
    val resolvedStatus: HostStatus
        get() = when {
            status.equals("online", ignoreCase = true) ||
                status.equals("healthy", ignoreCase = true) ||
                status.equals("ok", ignoreCase = true) ||
                healthy -> HostStatus.ONLINE
            status.equals("degraded", ignoreCase = true) ||
                status.equals("warn", ignoreCase = true) ||
                status.equals("crit", ignoreCase = true) -> HostStatus.DEGRADED
            status.equals("sleeping", ignoreCase = true) -> HostStatus.SLEEPING
            status.equals("offline", ignoreCase = true) ||
                status.equals("unhealthy", ignoreCase = true) -> HostStatus.OFFLINE
            else -> HostStatus.UNKNOWN
        }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LiveHostMetrics(
    val hostId: Long = 0L,
    val hostName: String? = null,
    @JsonNames("cpu", "cpuPercent", "cpu_percent", "cpuUsage")
    val cpuPercent: Double? = null,
    val cpuCores: Int? = null,
    val loadAverage: List<Double> = emptyList(),
    @JsonNames("memory", "memoryPercent", "memory_percent", "memPercent", "ramPercent")
    val memoryPercent: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    @JsonNames("disk", "diskPercent", "disk_percent")
    val diskPercent: Double? = null,
    val diskUsedBytes: Long? = null,
    val diskTotalBytes: Long? = null,
    @JsonNames("networkRxBytesPerSec", "rxBytesPerSec", "netRxBytesPerSec")
    val networkRxBytesPerSec: Long? = null,
    @JsonNames("networkTxBytesPerSec", "txBytesPerSec", "netTxBytesPerSec")
    val networkTxBytesPerSec: Long? = null,
    val networkRxTotalBytes: Long? = null,
    val networkTxTotalBytes: Long? = null,
    val uptimeSeconds: Long? = null,
    val osName: String? = null,
    val kernelVersion: String? = null,
    @JsonNames("topProcesses", "processes")
    val topProcesses: List<ProcessSample> = emptyList(),
    val gpus: List<GpuMetrics> = emptyList(),
    val collectedAtEpochMs: Long? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProcessSample(
    val pid: Int? = null,
    val name: String = "",
    val command: String? = null,
    @JsonNames("cpu", "cpuPercent", "cpu_percent")
    val cpuPercent: Double? = null,
    @JsonNames("memory", "memoryPercent", "memory_percent", "memPercent")
    val memoryPercent: Double? = null,
    val memoryBytes: Long? = null,
    val user: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GpuMetrics(
    val index: Int = 0,
    val name: String? = null,
    @JsonNames("utilization", "utilizationPercent", "gpuPercent")
    val utilizationPercent: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    @JsonNames("temperature", "temperatureCelsius", "tempC")
    val temperatureCelsius: Double? = null,
    val powerWatts: Double? = null,
)

@Serializable
data class MetricsCollectionStatus(
    val hostId: Long = 0L,
    val started: Boolean = false,
    val active: Boolean = false,
    val sessionId: String? = null,
    val message: String? = null,
)
