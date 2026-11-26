package com.nightcinder.helix.ui

import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.monitoring.HostKeyMatcher

/**
 * Resolves a host's effective status across Termix health checks, merged host status, and Prometheus/TCP
 * reachability (`hostUpByKey`), ensuring `HostHealthItem` entries with `UNKNOWN` status never mask
 * live Prometheus / WOL / TCP reachability results. Shared by the Panels and Tree cockpits so both show
 * the same status.
 */
fun resolveHostStatus(
    host: TermixHost,
    health: HealthSummary?,
    hostUpByKey: Map<String, Boolean> = emptyMap(),
): HostStatus {
    health?.hosts
        ?.firstOrNull { it.hostId == host.id }
        ?.resolvedStatus
        ?.takeIf { it != HostStatus.UNKNOWN }
        ?.let { return it }

    val isUp = hostUpByKey[HostKeyMatcher.canonicalKeyForHost(host)]
    if (isUp != null) {
        if (host.status == HostStatus.UNKNOWN) {
            return if (isUp) HostStatus.ONLINE else HostStatus.OFFLINE
        }
        if (host.status == HostStatus.OFFLINE && isUp) {
            return HostStatus.ONLINE
        }
    }
    return host.status
}
