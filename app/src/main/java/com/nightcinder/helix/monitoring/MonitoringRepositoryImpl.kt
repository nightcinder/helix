package com.nightcinder.helix.monitoring

import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.HostAliasTable
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.MonitoringAlert
import com.nightcinder.helix.core.model.MonitoringSnapshot
import com.nightcinder.helix.core.model.PrometheusHostMetrics
import com.nightcinder.helix.core.model.SiteConfig
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.WolDevice
import com.nightcinder.helix.core.monitoring.MonitoringRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Canonical host key mapping across Termix hosts, Prometheus instance labels, Uptime Kuma monitors,
 * and Grafana alert labels. The aliases come from [SiteConfig.HOST_ALIASES] (HELIX_HOST_ALIASES), e.g.
 * `server=docker-host,nvidia-gpu,10.0.0.5;desktop=sunshine,moonlight`; tests can swap them with [configure].
 */
object HostKeyMatcher {
    private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val TOKEN_SPLIT_REGEX = Regex("""[^a-z0-9]+""")

    @Volatile
    private var table: HostAliasTable = HostAliasTable.parse(SiteConfig.HOST_ALIASES)

    @Volatile
    private var aliasToCanonical: Map<String, String> = buildAliasMap(table)

    /** Replaces the alias table (HELIX_HOST_ALIASES format). Intended for tests. */
    fun configure(spec: String) {
        val parsed = HostAliasTable.parse(spec)
        table = parsed
        aliasToCanonical = buildAliasMap(parsed)
    }

    val KNOWN_CANONICAL_HOSTS: Set<String> get() = table.canonicalHosts

    private fun buildAliasMap(t: HostAliasTable): Map<String, String> = buildMap {
        for ((host, aliases) in t.aliasesByHost) {
            put(host, host)
            for (alias in aliases) putIfAbsent(alias, host)
        }
    }

    fun canonicalKey(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        val withoutScheme = cleaned.substringAfter("://")
        val hostPart = withoutScheme.substringBefore('/').substringBefore(':')
        if (hostPart.isEmpty()) return null

        aliasToCanonical[hostPart]?.let { return it }

        if (!IPV4_REGEX.matches(hostPart)) {
            val shortName = hostPart.substringBefore('.')
            aliasToCanonical[shortName]?.let { return it }
            matchHyphenatedOrTokenAlias(shortName)?.let { return it }
            return shortName
        }

        return hostPart
    }

    /**
     * Returns ONLY one of the configured canonical host keys ([KNOWN_CANONICAL_HOSTS]),
     * or `null` if [raw] does not match a known host on token/delimiter boundaries.
     */
    fun knownCanonicalHostOrNull(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        val withoutScheme = cleaned.substringAfter("://")
        val hostPart = withoutScheme.substringBefore('/').substringBefore(':')
        if (hostPart.isEmpty()) return null

        aliasToCanonical[hostPart]?.let { return it }

        if (!IPV4_REGEX.matches(hostPart)) {
            val shortName = hostPart.substringBefore('.')
            aliasToCanonical[shortName]?.let { return it }
            matchHyphenatedOrTokenAlias(shortName)?.let { return it }
        }
        return null
    }

    private fun matchHyphenatedOrTokenAlias(text: String): String? {
        val aliases = aliasToCanonical
        // Hyphenated aliases (`docker-host`, `esxi-poller`) also match as a prefix or `-suffix`.
        for ((alias, host) in aliases) {
            if ('-' in alias && (text.startsWith(alias) || text.endsWith("-$alias"))) return host
        }

        val tokens = text.split(TOKEN_SPLIT_REGEX).filter { it.isNotEmpty() }
        for (token in tokens) {
            val host = aliases[token] ?: continue
            if (!IPV4_REGEX.matches(token)) return host
        }
        return null
    }

    fun canonicalKeyForHost(host: TermixHost): String {
        val candidates = listOfNotNull(
            host.name.takeIf { it.isNotBlank() },
            host.hostname?.takeIf { it.isNotBlank() },
            host.ip.takeIf { it.isNotBlank() },
        )
        for (c in candidates) {
            knownCanonicalHostOrNull(c)?.let { return it }
        }
        return canonicalKey(host.displayName) ?: host.displayName.lowercase(Locale.US)
    }

    fun findMatchingHost(hosts: List<TermixHost>, keyOrName: String?): TermixHost? {
        val target = canonicalKey(keyOrName) ?: return null
        return hosts.firstOrNull { host -> canonicalKeyForHost(host) == target }
    }

    fun inferTargetHostFromKuma(
        monitorHostname: String?,
        monitorName: String?,
        monitorUrl: String?,
    ): String {
        for (field in listOf(monitorHostname, monitorUrl)) {
            knownCanonicalHostOrNull(field)?.let { return it }
        }
        val nameLower = monitorName?.trim()?.lowercase(Locale.US).orEmpty()
        if (nameLower.isNotEmpty()) {
            matchHyphenatedOrTokenAlias(nameLower)?.let { return it }
        }
        // Unattributed monitors go to the first configured host (the core server in a typical alias table).
        return table.fallbackHost ?: canonicalKey(monitorHostname) ?: canonicalKey(monitorUrl) ?: UNATTRIBUTED_HOST
    }

    /** Bucket for monitors that match no host when no alias table is configured. */
    const val UNATTRIBUTED_HOST = "homelab"

    fun stableLabelsDigest(labels: Map<String, String>): String {
        if (labels.isEmpty()) return "0"
        val canonical = labels.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }
}

class MonitoringRepositoryImpl(
    private val prometheusClient: PrometheusApiClient,
    private val grafanaClient: GrafanaAlertsClient,
    private val diunSource: DiunLogAlertSource,
    private val tcpReachabilityProbe: suspend (host: String, port: Int, timeoutMs: Int) -> Boolean = ::defaultTcpProbe,
    private val clock: () -> Long = System::currentTimeMillis,
) : MonitoringRepository {

    private val _snapshot = MutableStateFlow(MonitoringSnapshot())
    override val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    override suspend fun refresh(
        hosts: List<TermixHost>,
        wolDevices: List<WolDevice>,
    ): Result<MonitoringSnapshot> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val resultSnapshot = supervisorScope {
                val promDeferred = async {
                    try {
                        Result.success(prometheusClient.fetchAll())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                val grafanaDeferred = async {
                    try {
                        Result.success(grafanaClient.fetchActiveAlerts())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                val diunDeferred = async {
                    try {
                        Result.success(diunSource.fetchDiunAlerts(hosts))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }

                val promResult = promDeferred.await()
                val grafanaResult = grafanaDeferred.await()
                val diunResult = diunDeferred.await()

                val sourceErrors = linkedMapOf<AlertSource, String>()
                val combinedAlerts = mutableListOf<MonitoringAlert>()
                val hostMetricsByKey = linkedMapOf<String, PrometheusHostMetrics>()
                val hostUpByKey = linkedMapOf<String, Boolean>()

                promResult.fold(
                    onSuccess = { prom ->
                        combinedAlerts.addAll(prom.alerts)
                        hostMetricsByKey.putAll(prom.hostMetricsByKey)
                        hostUpByKey.putAll(prom.hostUpByKey)
                    },
                    onFailure = { err ->
                        sourceErrors[AlertSource.PROMETHEUS] = err.message ?: "Prometheus unavailable"
                    },
                )

                grafanaResult.fold(
                    onSuccess = { alerts ->
                        combinedAlerts.addAll(alerts)
                    },
                    onFailure = { err ->
                        sourceErrors[AlertSource.GRAFANA] = err.message ?: "Grafana unavailable"
                    },
                )

                diunResult.fold(
                    onSuccess = { alerts ->
                        combinedAlerts.addAll(alerts)
                    },
                    onFailure = { err ->
                        sourceErrors[AlertSource.DIUN] = err.message ?: "Diun logs unavailable"
                    },
                )

                // Resolve reachability for hosts still lacking an authoritative status (e.g. Windows desktops, ESXi)
                val unresolvedHosts = hosts.filter { host ->
                    val key = HostKeyMatcher.canonicalKeyForHost(host)
                    !hostUpByKey.containsKey(key) && host.status == HostStatus.UNKNOWN
                }

                val probeResults = unresolvedHosts.map { host ->
                    async {
                        val key = HostKeyMatcher.canonicalKeyForHost(host)
                        val targetMac = host.macAddress ?: host.effectiveHomelabProfile?.wolMacAddress
                        val matchedWol = wolDevices.firstOrNull { dev ->
                            dev.matches(
                                mac = targetMac,
                                targetHostName = host.displayName,
                                targetHostId = host.id,
                            )
                        }
                        val wolOnline = matchedWol?.isOnline
                        val isReachable = if (wolOnline != null) {
                            wolOnline
                        } else {
                            val address = host.ip.ifBlank { host.hostname.orEmpty() }.trim()
                            val port = host.port.takeIf { it in 1..65535 } ?: 22
                            if (address.isNotEmpty()) {
                                try {
                                    tcpReachabilityProbe(address, port, TCP_PROBE_TIMEOUT_MS)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    false
                                }
                            } else {
                                false
                            }
                        }
                        key to isReachable
                    }
                }.awaitAll()

                for ((key, isUp) in probeResults) {
                    hostUpByKey[key] = isUp
                }

                // Bind targetHostId where a TermixHost matches, deduplicate, and sort by severity
                val normalizedAlerts = combinedAlerts
                    .distinctBy { it.id }
                    .map { alert ->
                        if (alert.targetHostId != null) {
                            alert
                        } else {
                            val matchedHost = HostKeyMatcher.findMatchingHost(hosts, alert.targetHostName)
                            if (matchedHost != null) alert.copy(targetHostId = matchedHost.id) else alert
                        }
                    }
                    .sortedWith(
                        compareBy<MonitoringAlert> { severityRank(it.severity) }
                            .thenByDescending { it.triggeredAtEpochMs ?: 0L }
                            .thenBy { it.title.lowercase(Locale.US) },
                    )

                MonitoringSnapshot(
                    alerts = normalizedAlerts,
                    hostMetricsByKey = hostMetricsByKey,
                    hostUpByKey = hostUpByKey,
                    sourceErrors = sourceErrors,
                    lastUpdatedEpochMs = clock(),
                )
            }

            _snapshot.value = resultSnapshot
            Result.success(resultSnapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    override fun clearData() {
        diunSource.clearSessions()
        _snapshot.value = MonitoringSnapshot()
        _isLoading.value = false
    }

    private fun severityRank(severity: AlertSeverity): Int = when (severity) {
        AlertSeverity.CRITICAL -> 0
        AlertSeverity.WARNING -> 1
        AlertSeverity.INFO -> 2
    }

    companion object {
        const val TCP_PROBE_TIMEOUT_MS = 800

        suspend fun defaultTcpProbe(host: String, port: Int, timeoutMs: Int): Boolean =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) {
                        runCatching { socket.close() }
                    }
                }
                try {
                    socket.use { s ->
                        s.connect(InetSocketAddress(host, port), timeoutMs)
                        true
                    }
                } catch (e: CancellationException) {
                    runCatching { socket.close() }
                    throw e
                } catch (_: Exception) {
                    runCatching { socket.close() }
                    false
                } finally {
                    cancelHandle?.dispose()
                }
            }
    }
}
