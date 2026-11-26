package com.nightcinder.helix.ui.monitoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightcinder.helix.core.model.LiveHostMetrics
import com.nightcinder.helix.core.model.PrometheusHostMetrics
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CockpitSurface
import com.nightcinder.helix.ui.theme.CockpitSurfaceVariant
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.CyanPrimaryContainer
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.EmeraldContainer
import com.nightcinder.helix.ui.theme.RoseError
import com.nightcinder.helix.ui.theme.RoseErrorContainer
import java.util.Locale

private const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0
private const val BYTES_PER_MIB = 1024.0 * 1024.0
private const val BYTES_PER_KIB = 1024.0

/**
 * Scannable Prometheus + Termix merged host telemetry section suitable for Concept A and Concept B
 * metrics tabs (Linux + NVIDIA GPU, Windows `C:`, ESXi).
 */
@Composable
fun PrometheusHostMetricsSection(
    host: TermixHost,
    metrics: PrometheusHostMetrics?,
    mergedLiveMetrics: LiveHostMetrics?,
    modifier: Modifier = Modifier,
) {
    val cpuPercent = mergedLiveMetrics?.cpuPercent ?: metrics?.cpuPercent
    val memPercent = mergedLiveMetrics?.memoryPercent ?: metrics?.memoryPercent
    val memUsedBytes = mergedLiveMetrics?.memoryUsedBytes ?: metrics?.memoryUsedBytes
    val memTotalBytes = mergedLiveMetrics?.memoryTotalBytes ?: metrics?.memoryTotalBytes

    val diskPercent = mergedLiveMetrics?.diskPercent ?: metrics?.diskPercent
    val diskUsedBytes = mergedLiveMetrics?.diskUsedBytes ?: metrics?.diskUsedBytes
    val diskTotalBytes = mergedLiveMetrics?.diskTotalBytes ?: metrics?.diskTotalBytes

    val netRxRate = mergedLiveMetrics?.networkRxBytesPerSec ?: metrics?.networkRxBytesPerSec
    val netTxRate = mergedLiveMetrics?.networkTxBytesPerSec ?: metrics?.networkTxBytesPerSec
    val uptimeSec = mergedLiveMetrics?.uptimeSeconds ?: metrics?.uptimeSeconds

    val firstLiveGpu = mergedLiveMetrics?.gpus?.firstOrNull()
    val gpuUtil = metrics?.gpuUtilizationPercent ?: firstLiveGpu?.utilizationPercent
    val gpuTemp = metrics?.gpuTemperatureCelsius ?: firstLiveGpu?.temperatureCelsius
    val gpuMemUsed = metrics?.gpuMemoryUsedBytes ?: firstLiveGpu?.memoryUsedBytes
    val gpuMemTotal = metrics?.gpuMemoryTotalBytes ?: firstLiveGpu?.memoryTotalBytes
    val gpuPowerWatts = metrics?.gpuPowerWatts ?: firstLiveGpu?.powerWatts
    val hasGpuTelemetry = gpuUtil != null || gpuTemp != null || gpuMemUsed != null || gpuPowerWatts != null

    val sourceBadgeLabel = buildSourceBadgeLabel(metrics)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header + Exporter/Instance badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${host.displayName} Telemetry",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                metrics?.isUp?.let { isUp ->
                    Surface(
                        color = if (isUp) EmeraldContainer else RoseErrorContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = if (isUp) "UP" else "DOWN",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                            ),
                            color = if (isUp) EmeraldAccent else RoseError,
                        )
                    }
                }

                if (sourceBadgeLabel != null) {
                    Surface(
                        color = CyanPrimaryContainer.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = sourceBadgeLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                            color = CyanPrimary,
                        )
                    }
                }
            }
        }

        // Compute, Memory, Disk, Network & Uptime Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CockpitSurface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TelemetryGaugeRow(
                    icon = Icons.Default.Speed,
                    label = "CPU",
                    percent = cpuPercent,
                    detailText = cpuPercent?.let { formatPercent(it) } ?: "—",
                )

                TelemetryGaugeRow(
                    icon = Icons.Default.Memory,
                    label = "Memory",
                    percent = memPercent,
                    detailText = formatCapacitySummary(memPercent, memUsedBytes, memTotalBytes),
                )

                TelemetryGaugeRow(
                    icon = Icons.Default.Storage,
                    label = "Disk",
                    percent = diskPercent,
                    detailText = formatCapacitySummary(diskPercent, diskUsedBytes, diskTotalBytes),
                )

                // Network Rx / Tx + Uptime summary strip
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = CockpitSurfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TelemetryStatColumn(
                            title = "NET RX",
                            value = "↓ ${formatBytesPerSec(netRxRate)}",
                            valueColor = EmeraldAccent,
                        )
                        TelemetryStatColumn(
                            title = "NET TX",
                            value = "↑ ${formatBytesPerSec(netTxRate)}",
                            valueColor = CyanPrimary,
                        )
                        TelemetryStatColumn(
                            title = "UPTIME",
                            value = formatUptimeDaysHours(uptimeSec),
                            valueColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // Dedicated NVIDIA GPU Telemetry Card (nvidia-gpu job)
        if (hasGpuTelemetry) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CockpitSurface),
                border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = firstLiveGpu?.name?.takeIf { it.isNotBlank() } ?: "NVIDIA GPU",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = CyanPrimary,
                        )
                        gpuTemp?.let { temp ->
                            Text(
                                text = String.format(Locale.US, "%.0f°C", temp),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = when {
                                    temp >= 85.0 -> RoseError
                                    temp >= 75.0 -> AmberAccent
                                    else -> EmeraldAccent
                                },
                            )
                        }
                    }

                    TelemetryGaugeRow(
                        icon = Icons.Default.Speed,
                        label = "GPU Load",
                        percent = gpuUtil,
                        detailText = gpuUtil?.let { formatPercent(it) } ?: "—",
                    )

                    val vramPercent = if (gpuMemUsed != null && gpuMemTotal != null && gpuMemTotal > 0L) {
                        (gpuMemUsed.toDouble() / gpuMemTotal.toDouble()) * 100.0
                    } else {
                        null
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TelemetryStatColumn(
                            title = "VRAM",
                            value = if (gpuMemUsed != null && gpuMemTotal != null) {
                                "${formatBytesGiB(gpuMemUsed)} / ${formatBytesGiB(gpuMemTotal)}" +
                                    (vramPercent?.let { " (${formatPercent(it)})" } ?: "")
                            } else {
                                "—"
                            },
                            valueColor = MaterialTheme.colorScheme.onSurface,
                        )
                        TelemetryStatColumn(
                            title = "POWER",
                            value = gpuPowerWatts?.let { String.format(Locale.US, "%.1f W", it) } ?: "—",
                            valueColor = AmberAccent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryGaugeRow(
    icon: ImageVector,
    label: String,
    percent: Double?,
    detailText: String,
) {
    val clampedFraction = ((percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val gaugeColor = when {
        percent == null -> MaterialTheme.colorScheme.outline
        percent >= 90.0 -> RoseError
        percent >= 75.0 -> AmberAccent
        else -> EmeraldAccent
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = detailText,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = if (percent != null) gaugeColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LinearProgressIndicator(
            progress = { clampedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = gaugeColor,
            trackColor = CockpitSurfaceVariant,
        )
    }
}

@Composable
private fun TelemetryStatColumn(
    title: String,
    value: String,
    valueColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            color = valueColor,
        )
    }
}

internal fun buildSourceBadgeLabel(metrics: PrometheusHostMetrics?): String? {
    if (metrics == null) return null
    val job = metrics.sourceJob.trim()
    val instance = metrics.instanceLabel.trim()
    return when {
        job.isNotEmpty() && instance.isNotEmpty() -> "$job / $instance"
        instance.isNotEmpty() -> instance
        job.isNotEmpty() -> job
        else -> null
    }
}

internal fun formatPercent(value: Double): String =
    String.format(Locale.US, "%.1f%%", value)

internal fun formatBytesGiB(bytes: Long): String =
    String.format(Locale.US, "%.1f GB", bytes.toDouble() / BYTES_PER_GIB)

internal fun formatCapacitySummary(
    percent: Double?,
    usedBytes: Long?,
    totalBytes: Long?,
): String {
    val pctPart = percent?.let { formatPercent(it) }
    val bytesPart = if (usedBytes != null && totalBytes != null && totalBytes > 0L) {
        "${formatBytesGiB(usedBytes)} / ${formatBytesGiB(totalBytes)}"
    } else {
        null
    }
    return when {
        pctPart != null && bytesPart != null -> "$pctPart ($bytesPart)"
        pctPart != null -> pctPart
        bytesPart != null -> bytesPart
        else -> "—"
    }
}

internal fun formatBytesPerSec(bytesPerSec: Long?): String {
    if (bytesPerSec == null || bytesPerSec < 0L) return "—"
    val v = bytesPerSec.toDouble()
    return when {
        v >= BYTES_PER_GIB -> String.format(Locale.US, "%.2f GB/s", v / BYTES_PER_GIB)
        v >= BYTES_PER_MIB -> String.format(Locale.US, "%.1f MB/s", v / BYTES_PER_MIB)
        v >= BYTES_PER_KIB -> String.format(Locale.US, "%.1f KB/s", v / BYTES_PER_KIB)
        else -> "$bytesPerSec B/s"
    }
}

internal fun formatUptimeDaysHours(uptimeSeconds: Long?): String {
    if (uptimeSeconds == null || uptimeSeconds < 0L) return "—"
    val days = uptimeSeconds / 86_400L
    val hours = (uptimeSeconds % 86_400L) / 3_600L
    val minutes = (uptimeSeconds % 3_600L) / 60L
    return when {
        days > 0L -> "${days}d ${hours}h"
        hours > 0L -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
