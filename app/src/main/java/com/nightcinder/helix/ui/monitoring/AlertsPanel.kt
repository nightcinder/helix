package com.nightcinder.helix.ui.monitoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.MonitoringAlert
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.ui.state.MonitoringUiState
import com.nightcinder.helix.ui.state.MonitoringViewModel
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.AmberContainer
import com.nightcinder.helix.ui.theme.CockpitSurface
import com.nightcinder.helix.ui.theme.CockpitSurfaceVariant
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.CyanPrimaryContainer
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError
import com.nightcinder.helix.ui.theme.RoseErrorContainer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ALERT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", Locale.US)

/**
 * Unified homelab alerts & image-updates feed combining Grafana, Uptime Kuma (via Prometheus),
 * Diun container image updates, and Prometheus firing rules.
 *
 * Uses a single top-level [LazyColumn] so embedding inside tabbed or spatial panels never causes
 * nested infinite scroll crashes.
 */
@Composable
fun AlertsFeedPanel(
    uiState: MonitoringUiState,
    hosts: List<TermixHost>,
    onSelectFilter: (AlertSource?) -> Unit,
    onRefresh: () -> Unit,
    onOpenHostTerminal: (TermixHost) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header + Refresh action
        item(key = "alerts_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Alerts & Updates",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    AlertCountBadge(
                        count = uiState.activeAlertCount,
                        hasCritical = uiState.hasCriticalAlerts,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.snapshot.lastUpdatedEpochMs?.let { updatedMs ->
                        Text(
                            text = formatEpochTimestamp(updatedMs),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.size(32.dp),
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CyanPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh alerts",
                                tint = CyanPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        // Source filter chips row
        item(key = "alerts_filter_chips") {
            AlertSourceFilterBar(
                uiState = uiState,
                onSelectFilter = onSelectFilter,
            )
        }

        // Honest non-fatal sourceErrors warning banner (e.g., off-LAN HTTP 403 on Grafana/Prometheus)
        if (uiState.sourceErrors.isNotEmpty()) {
            item(key = "alerts_source_errors") {
                SourceErrorsBanner(sourceErrors = uiState.sourceErrors)
            }
        }

        // Alert cards or truthful empty state
        val alerts = uiState.filteredAlerts
        if (alerts.isEmpty()) {
            item(key = "alerts_empty_state") {
                AlertsEmptyStateCard(
                    isLoading = uiState.isLoading,
                    hasSourceErrors = uiState.sourceErrors.isNotEmpty(),
                    hasLoadedOnce = uiState.snapshot.lastUpdatedEpochMs != null,
                    selectedFilter = uiState.selectedSourceFilter,
                )
            }
        } else {
            items(
                items = alerts,
                key = { alert -> alert.id },
            ) { alert ->
                val matchedHost = MonitoringViewModel.resolveAlertTargetHost(alert, hosts)
                AlertFeedItemCard(
                    alert = alert,
                    matchedHost = matchedHost,
                    onOpenHostTerminal = onOpenHostTerminal,
                )
            }
        }
    }
}

@Composable
private fun AlertSourceFilterBar(
    uiState: MonitoringUiState,
    onSelectFilter: (AlertSource?) -> Unit,
) {
    val promCount = uiState.countForSource(AlertSource.PROMETHEUS)
    val showPromChip = promCount > 0 ||
        uiState.selectedSourceFilter == AlertSource.PROMETHEUS ||
        uiState.sourceErrors.containsKey(AlertSource.PROMETHEUS)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceFilterChip(
            label = "All (${uiState.activeAlertCount})",
            selected = uiState.selectedSourceFilter == null,
            onClick = { onSelectFilter(null) },
        )
        SourceFilterChip(
            label = "Grafana (${uiState.countForSource(AlertSource.GRAFANA)})",
            selected = uiState.selectedSourceFilter == AlertSource.GRAFANA,
            onClick = { onSelectFilter(AlertSource.GRAFANA) },
        )
        SourceFilterChip(
            label = "Uptime Kuma (${uiState.countForSource(AlertSource.UPTIME_KUMA)})",
            selected = uiState.selectedSourceFilter == AlertSource.UPTIME_KUMA,
            onClick = { onSelectFilter(AlertSource.UPTIME_KUMA) },
        )
        SourceFilterChip(
            label = "Diun (${uiState.countForSource(AlertSource.DIUN)})",
            selected = uiState.selectedSourceFilter == AlertSource.DIUN,
            onClick = { onSelectFilter(AlertSource.DIUN) },
        )
        if (showPromChip) {
            SourceFilterChip(
                label = "Prometheus ($promCount)",
                selected = uiState.selectedSourceFilter == AlertSource.PROMETHEUS,
                onClick = { onSelectFilter(AlertSource.PROMETHEUS) },
            )
        }
    }
}

@Composable
private fun SourceFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = CyanPrimaryContainer,
            selectedLabelColor = CyanPrimary,
            containerColor = CockpitSurfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            selectedBorderColor = CyanPrimary,
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        ),
    )
}

@Composable
private fun SourceErrorsBanner(
    sourceErrors: Map<AlertSource, String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AmberContainer.copy(alpha = 0.45f),
        ),
        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Monitoring source warning",
                    tint = AmberAccent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Partial telemetry — one or more alert sources reported an error",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = AmberAccent,
                )
            }
            sourceErrors.forEach { (source, message) ->
                Text(
                    text = "• ${sourceDisplayLabel(source)}: $message",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun AlertsEmptyStateCard(
    isLoading: Boolean,
    hasSourceErrors: Boolean,
    hasLoadedOnce: Boolean,
    selectedFilter: AlertSource?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CockpitSurface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading && !hasLoadedOnce -> {
                    Text(
                        text = "Querying Prometheus, Grafana, and Diun sources...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                hasSourceErrors -> {
                    Text(
                        text = "No alerts returned for ${selectedFilter?.let(::sourceDisplayLabel) ?: "selected sources"}, " +
                            "but source errors occurred above — health cannot be confirmed until sources respond.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberAccent,
                    )
                }
                !hasLoadedOnce -> {
                    Text(
                        text = "Tap refresh to query live alerts and container updates.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldAccent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (selectedFilter == null) {
                                "No firing alerts or pending image updates."
                            } else {
                                "No active entries for ${sourceDisplayLabel(selectedFilter)}."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertFeedItemCard(
    alert: MonitoringAlert,
    matchedHost: TermixHost?,
    onOpenHostTerminal: (TermixHost) -> Unit,
) {
    val terminalHost = matchedHost?.takeIf { it.enableTerminal }
    val (severityBg, severityFg, borderAccent) = when (alert.severity) {
        AlertSeverity.CRITICAL -> Triple(RoseErrorContainer, RoseError, RoseError.copy(alpha = 0.7f))
        AlertSeverity.WARNING -> Triple(AmberContainer, AmberAccent, AmberAccent.copy(alpha = 0.65f))
        AlertSeverity.INFO -> Triple(CyanPrimaryContainer, CyanPrimary, CyanPrimary.copy(alpha = 0.45f))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (terminalHost != null) {
                    Modifier.clickable { onOpenHostTerminal(terminalHost) }
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.cardColors(containerColor = CockpitSurface),
        border = BorderStroke(1.dp, borderAccent),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Top badges row: Severity + Source + Target Host + Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        color = severityBg,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = alert.severity.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                            ),
                            color = severityFg,
                            maxLines = 1,
                        )
                    }

                    Surface(
                        color = CockpitSurfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = sourceDisplayLabel(alert.source),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }

                    val hostBadgeText = matchedHost?.displayName
                        ?: alert.targetHostName?.takeIf { it.isNotBlank() }
                        ?: alert.instance?.takeIf { it.isNotBlank() }
                    if (hostBadgeText != null) {
                        Surface(
                            color = CockpitSurfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = hostBadgeText,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp,
                                ),
                                color = CyanPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                alert.triggeredAtEpochMs?.let { ts ->
                    Text(
                        text = formatEpochTimestamp(ts),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // Title & Summary
            Text(
                text = alert.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (alert.summary.isNotBlank() && alert.summary != alert.title) {
                Text(
                    text = alert.summary,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Action / Target Host terminal affordance
            if (terminalHost != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Tap to open SSH terminal on ${terminalHost.displayName}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = CyanPrimary,
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = if (matchedHost != null && !matchedHost.enableTerminal) {
                            "SSH terminal disabled on ${matchedHost.displayName}"
                        } else {
                            "No matching Termix host"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

internal fun sourceDisplayLabel(source: AlertSource): String = when (source) {
    AlertSource.GRAFANA -> "Grafana"
    AlertSource.UPTIME_KUMA -> "Uptime Kuma"
    AlertSource.DIUN -> "Diun"
    AlertSource.PROMETHEUS -> "Prometheus"
}

internal fun formatEpochTimestamp(epochMs: Long): String =
    runCatching {
        ALERT_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")
