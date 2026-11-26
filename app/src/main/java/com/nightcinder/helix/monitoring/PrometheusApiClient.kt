package com.nightcinder.helix.monitoring

import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.MonitoringAlert
import com.nightcinder.helix.core.model.MonitoringSourceException
import com.nightcinder.helix.core.model.PrometheusHostMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

val DefaultMonitoringJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

data class PrometheusVectorSample(
    val metric: Map<String, String>,
    val value: Double,
    val timestampSec: Double? = null,
)

data class PrometheusFetchResult(
    val alerts: List<MonitoringAlert>,
    val hostMetricsByKey: Map<String, PrometheusHostMetrics>,
    val hostUpByKey: Map<String, Boolean>,
)

/**
 * Live Prometheus HTTP v1 API client (`/api/v1/query` & `/api/v1/alerts`).
 * Detects 401/403 and HTML/Authentik SSO redirects honestly via [MonitoringSourceException].
 */
class PrometheusApiClient(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val json: Json = DefaultMonitoringJson,
) {

    suspend fun fetchAll(): PrometheusFetchResult = coroutineScope {
        // Gate on `up` first so off-LAN 403 or SSO redirect fails fast in one request.
        val upSamples = queryVector("up")

        val kumaDeferred = async {
            try {
                queryVector("monitor_status!=1")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                rethrowIfAuth(e)
                emptyList()
            }
        }
        val promAlertsDeferred = async {
            try {
                fetchPrometheusAlerts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                rethrowIfAuth(e)
                emptyList()
            }
        }

        // Linux (node_exporter)
        val linuxCpuDeferred = async { safeQuery(QUERY_LINUX_CPU) }
        val linuxMemPctDeferred = async { safeQuery(QUERY_LINUX_MEM_PCT) }
        val linuxMemTotalDeferred = async { safeQuery(QUERY_LINUX_MEM_TOTAL) }
        val linuxMemAvailDeferred = async { safeQuery(QUERY_LINUX_MEM_AVAIL) }
        val linuxDiskSizeDeferred = async { safeQuery(QUERY_LINUX_DISK_SIZE) }
        val linuxDiskAvailDeferred = async { safeQuery(QUERY_LINUX_DISK_AVAIL) }
        val linuxNetRxDeferred = async { safeQuery(QUERY_LINUX_NET_RX) }
        val linuxNetTxDeferred = async { safeQuery(QUERY_LINUX_NET_TX) }

        // Windows (windows_exporter)
        val winCpuDeferred = async { safeQuery(QUERY_WINDOWS_CPU) }
        val winMemFreeDeferred = async { safeQuery(QUERY_WINDOWS_MEM_FREE) }
        val winMemTotalDeferred = async { safeQuery(QUERY_WINDOWS_MEM_TOTAL) }
        val winDiskSizeDeferred = async { safeQuery(QUERY_WINDOWS_DISK_SIZE) }
        val winDiskFreeDeferred = async { safeQuery(QUERY_WINDOWS_DISK_FREE) }

        // ESXi (vSphere exporter)
        val esxiCpuDeferred = async { safeQuery(QUERY_ESXI_CPU) }
        val esxiMemPctDeferred = async { safeQuery(QUERY_ESXI_MEM_PCT) }
        val esxiNetRxDeferred = async { safeQuery(QUERY_ESXI_NET_RX) }
        val esxiNetTxDeferred = async { safeQuery(QUERY_ESXI_NET_TX) }
        val esxiUptimeDeferred = async { safeQuery(QUERY_ESXI_UPTIME) }

        // NVIDIA GPU (nvidia-gpu job)
        val gpuUtilDeferred = async { safeQuery(QUERY_GPU_UTIL) }
        val gpuTempDeferred = async { safeQuery(QUERY_GPU_TEMP) }
        val gpuMemUsedDeferred = async { safeQuery(QUERY_GPU_MEM_USED) }
        val gpuMemTotalDeferred = async { safeQuery(QUERY_GPU_MEM_TOTAL) }
        val gpuPowerDeferred = async { safeQuery(QUERY_GPU_POWER) }

        val hostUpByKey = linkedMapOf<String, Boolean>()
        val hasPrimaryUp = mutableSetOf<String>()
        val builders = linkedMapOf<String, MutableHostMetrics>()

        fun getOrCreate(hostKey: String, instanceLabel: String, job: String = ""): MutableHostMetrics {
            val isPrimary = job.lowercase(Locale.US) in PRIMARY_OS_JOBS
            return builders.getOrPut(hostKey) {
                MutableHostMetrics(
                    hostKey = hostKey,
                    instanceLabel = instanceLabel.ifBlank { hostKey },
                    sourceJob = if (isPrimary) job else "",
                )
            }.also { existing ->
                if (isPrimary) {
                    existing.sourceJob = job
                    if (instanceLabel.isNotBlank()) {
                        existing.instanceLabel = instanceLabel
                    }
                } else if (existing.sourceJob.isBlank() && job.isNotBlank()) {
                    existing.sourceJob = job
                }
            }
        }

        // Process `up` — ONLY primary OS jobs (`node`, `windows`, `esxi`) are authoritative for host `isUp`.
        // Secondary exporters (e.g. `cadvisor`, `nvidia-gpu`, `uptime-kuma`) never override a primary OS job
        // and never mark a host offline (`false`) on their own.
        for (sample in upSamples) {
            val instance = sample.metric["instance"].orEmpty()
            val job = sample.metric["job"].orEmpty()
            val hostKey = HostKeyMatcher.knownCanonicalHostOrNull(instance)
                ?: HostKeyMatcher.knownCanonicalHostOrNull(job)
                ?: continue
            val isUp = sample.value >= 1.0
            val jobLower = job.lowercase(Locale.US)
            val isPrimaryOsJob = jobLower in PRIMARY_OS_JOBS

            if (isPrimaryOsJob) {
                val m = getOrCreate(hostKey, instance, job)
                m.isUp = isUp
                hostUpByKey[hostKey] = isUp
                hasPrimaryUp.add(hostKey)
            } else if (hostKey !in hasPrimaryUp && isUp) {
                val m = getOrCreate(hostKey, instance.takeIf { !it.contains("nvidia-gpu") }.orEmpty(), job)
                if (m.isUp == null) {
                    m.isUp = true
                    hostUpByKey[hostKey] = true
                }
            }
        }

        // Linux (node_exporter)
        for (s in linuxCpuDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "linux" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "linux"
            getOrCreate(key, inst, "node").cpuPercent = s.value.coerceIn(0.0, 100.0)
        }
        val linuxMemTotals = linuxMemTotalDeferred.await().associateBy {
            HostKeyMatcher.canonicalKey(it.metric["instance"]) ?: "linux"
        }
        val linuxMemAvails = linuxMemAvailDeferred.await().associateBy {
            HostKeyMatcher.canonicalKey(it.metric["instance"]) ?: "linux"
        }
        for (s in linuxMemPctDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "linux" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "linux"
            getOrCreate(key, inst, "node").memoryPercent = s.value.coerceIn(0.0, 100.0)
        }
        for ((key, totalSample) in linuxMemTotals) {
            val inst = totalSample.metric["instance"].orEmpty().ifBlank { "linux" }
            val m = getOrCreate(key, inst, "node")
            val total = totalSample.value.toLong().coerceAtLeast(0L)
            m.memoryTotalBytes = total
            linuxMemAvails[key]?.let { availSample ->
                val avail = availSample.value.toLong().coerceAtLeast(0L)
                val used = (total - avail).coerceAtLeast(0L)
                m.memoryUsedBytes = used
                if (m.memoryPercent == null && total > 0L) {
                    m.memoryPercent = ((used.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                }
            }
        }

        // Filter out synthetic `rootfs` / `tmpfs` (0-byte) pseudo-filesystems on `/` and pick real backing device
        val linuxDiskSizesByKey = linuxDiskSizeDeferred.await()
            .filter { it.metric["fstype"]?.lowercase(Locale.US) !in IGNORED_FS_TYPES && it.value > 0.0 }
            .groupBy { HostKeyMatcher.canonicalKey(it.metric["instance"]) ?: "linux" }
        val linuxDiskAvailsList = linuxDiskAvailDeferred.await()
            .filter { it.metric["fstype"]?.lowercase(Locale.US) !in IGNORED_FS_TYPES }

        for ((key, sizeSamples) in linuxDiskSizesByKey) {
            val sizeSample = sizeSamples.maxByOrNull { it.value } ?: continue
            val inst = sizeSample.metric["instance"].orEmpty().ifBlank { "linux" }
            val device = sizeSample.metric["device"]
            val m = getOrCreate(key, inst, "node")
            val total = sizeSample.value.toLong().coerceAtLeast(0L)
            if (total <= 0L) continue
            m.diskTotalBytes = total

            val matchingAvail = linuxDiskAvailsList
                .filter { (HostKeyMatcher.canonicalKey(it.metric["instance"]) ?: "linux") == key }
                .let { candidates ->
                    if (!device.isNullOrBlank()) {
                        candidates.firstOrNull { it.metric["device"] == device }
                            ?: candidates.maxByOrNull { it.value }
                    } else {
                        candidates.maxByOrNull { it.value }
                    }
                }
            matchingAvail?.let { availSample ->
                val avail = availSample.value.toLong().coerceAtLeast(0L)
                val used = (total - avail).coerceAtLeast(0L)
                m.diskUsedBytes = used
                m.diskPercent = ((used.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0)
            }
        }

        for (s in linuxNetRxDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "linux" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "linux"
            getOrCreate(key, inst, "node").networkRxBytesPerSec = s.value.toLong().coerceAtLeast(0L)
        }
        for (s in linuxNetTxDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "linux" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "linux"
            getOrCreate(key, inst, "node").networkTxBytesPerSec = s.value.toLong().coerceAtLeast(0L)
        }

        // Windows (windows_exporter)
        for (s in winCpuDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "windows" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "windows"
            getOrCreate(key, inst, "windows").cpuPercent = s.value.coerceIn(0.0, 100.0)
        }
        val winMemTotals = winMemTotalDeferred.await().associateBy {
            HostKeyMatcher.canonicalKey(it.metric["instance"]) ?: "windows"
        }
        val winMemFrees = winMemFreeDeferred.await().associateBy {
            HostKeyMatcher.canonicalKey(it.metric["instance"]) ?: "windows"
        }
        for ((key, totalSample) in winMemTotals) {
            val inst = totalSample.metric["instance"].orEmpty().ifBlank { "windows" }
            val m = getOrCreate(key, inst, "windows")
            val total = totalSample.value.toLong().coerceAtLeast(0L)
            m.memoryTotalBytes = total
            winMemFrees[key]?.let { freeSample ->
                val free = freeSample.value.toLong().coerceAtLeast(0L)
                val used = (total - free).coerceAtLeast(0L)
                m.memoryUsedBytes = used
                if (total > 0L) {
                    m.memoryPercent = ((used.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                }
            }
        }
        val winDiskSizes = winDiskSizeDeferred.await()
            .filter { it.value > 0.0 }
            .associateBy { HostKeyMatcher.canonicalKey(it.metric["instance"]) ?: "windows" }
        val winDiskFrees = winDiskFreeDeferred.await()
            .associateBy { HostKeyMatcher.canonicalKey(it.metric["instance"]) ?: "windows" }
        for ((key, sizeSample) in winDiskSizes) {
            val inst = sizeSample.metric["instance"].orEmpty().ifBlank { "windows" }
            val m = getOrCreate(key, inst, "windows")
            val total = sizeSample.value.toLong().coerceAtLeast(0L)
            m.diskTotalBytes = total
            winDiskFrees[key]?.let { freeSample ->
                val free = freeSample.value.toLong().coerceAtLeast(0L)
                val used = (total - free).coerceAtLeast(0L)
                m.diskUsedBytes = used
                if (total > 0L) {
                    m.diskPercent = ((used.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                }
            }
        }

        // ESXi (vSphere exporter)
        for (s in esxiCpuDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "esxi" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "esxi"
            getOrCreate(key, inst, "esxi").cpuPercent = s.value.coerceIn(0.0, 100.0)
        }
        for (s in esxiMemPctDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "esxi" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "esxi"
            getOrCreate(key, inst, "esxi").memoryPercent = s.value.coerceIn(0.0, 100.0)
        }
        for (s in esxiNetRxDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "esxi" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "esxi"
            // vSphere reports KB/s; convert to bytes/sec
            getOrCreate(key, inst, "esxi").networkRxBytesPerSec = (s.value * 1024.0).toLong().coerceAtLeast(0L)
        }
        for (s in esxiNetTxDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "esxi" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "esxi"
            getOrCreate(key, inst, "esxi").networkTxBytesPerSec = (s.value * 1024.0).toLong().coerceAtLeast(0L)
        }
        for (s in esxiUptimeDeferred.await()) {
            val inst = s.metric["instance"].orEmpty().ifBlank { "esxi" }
            val key = HostKeyMatcher.canonicalKey(inst) ?: "esxi"
            getOrCreate(key, inst, "esxi").uptimeSeconds = s.value.toLong().coerceAtLeast(0L)
        }

        // NVIDIA GPU (nvidia-gpu job); attributed to its instance via the alias table
        gpuUtilDeferred.await().firstOrNull()?.let { s ->
            val key = HostKeyMatcher.knownCanonicalHostOrNull(s.metric["instance"]) ?: HostKeyMatcher.canonicalKey(s.metric["instance"]) ?: "gpu"
            val pct = if (s.value <= 1.0) s.value * 100.0 else s.value
            getOrCreate(key, "nvidia-gpu", "nvidia-gpu").gpuUtilizationPercent = pct.coerceIn(0.0, 100.0)
        }
        gpuTempDeferred.await().firstOrNull()?.let { s ->
            val key = HostKeyMatcher.knownCanonicalHostOrNull(s.metric["instance"]) ?: HostKeyMatcher.canonicalKey(s.metric["instance"]) ?: "gpu"
            getOrCreate(key, "nvidia-gpu", "nvidia-gpu").gpuTemperatureCelsius = s.value
        }
        gpuMemUsedDeferred.await().firstOrNull()?.let { s ->
            val key = HostKeyMatcher.knownCanonicalHostOrNull(s.metric["instance"]) ?: HostKeyMatcher.canonicalKey(s.metric["instance"]) ?: "gpu"
            getOrCreate(key, "nvidia-gpu", "nvidia-gpu").gpuMemoryUsedBytes = s.value.toLong().coerceAtLeast(0L)
        }
        gpuMemTotalDeferred.await().firstOrNull()?.let { s ->
            val key = HostKeyMatcher.knownCanonicalHostOrNull(s.metric["instance"]) ?: HostKeyMatcher.canonicalKey(s.metric["instance"]) ?: "gpu"
            getOrCreate(key, "nvidia-gpu", "nvidia-gpu").gpuMemoryTotalBytes = s.value.toLong().coerceAtLeast(0L)
        }
        gpuPowerDeferred.await().firstOrNull()?.let { s ->
            val key = HostKeyMatcher.knownCanonicalHostOrNull(s.metric["instance"]) ?: HostKeyMatcher.canonicalKey(s.metric["instance"]) ?: "gpu"
            getOrCreate(key, "nvidia-gpu", "nvidia-gpu").gpuPowerWatts = s.value
        }

        // Parse Uptime Kuma alerts with collision-free IDs
        val kumaAlerts = kumaDeferred.await().mapNotNull { sample ->
            val monitorType = sample.metric["monitor_type"].orEmpty()
            if (monitorType.equals("group", ignoreCase = true)) return@mapNotNull null

            val statusInt = sample.value.toInt()
            if (statusInt == 1) return@mapNotNull null

            val severity = when (statusInt) {
                0 -> AlertSeverity.CRITICAL
                2, 3 -> AlertSeverity.WARNING
                else -> AlertSeverity.WARNING
            }
            val stateLabel = when (statusInt) {
                0 -> "down"
                2 -> "pending"
                3 -> "maintenance"
                else -> "degraded"
            }
            val monitorName = sample.metric["monitor_name"]?.takeIf { it.isNotBlank() }
                ?: sample.metric["monitor_url"]?.takeIf { it.isNotBlank() }
                ?: "Uptime Kuma Monitor"
            val monitorHostname = sample.metric["monitor_hostname"]?.takeIf { it.isNotBlank() }
            val monitorUrl = sample.metric["monitor_url"]?.takeIf { it.isNotBlank() }
            val targetHost = HostKeyMatcher.inferTargetHostFromKuma(
                monitorHostname = monitorHostname,
                monitorName = monitorName,
                monitorUrl = monitorUrl,
            )
            val summary = listOfNotNull(
                monitorUrl,
                monitorHostname,
                monitorType.takeIf { it.isNotBlank() },
            ).distinct().joinToString(" • ").ifBlank { "Monitor status=$statusInt ($stateLabel)" }

            val monitorId = sample.metric["monitor_id"]?.takeIf { it.isNotBlank() } ?: monitorName
            val endpointKey = monitorHostname ?: monitorUrl ?: ""
            val digest = HostKeyMatcher.stableLabelsDigest(sample.metric)
            MonitoringAlert(
                id = "kuma:$monitorId:$endpointKey:$digest:$stateLabel",
                source = AlertSource.UPTIME_KUMA,
                severity = severity,
                title = "$monitorName (${stateLabel.uppercase()})",
                summary = summary,
                targetHostName = targetHost,
                instance = monitorHostname ?: monitorUrl,
                triggeredAtEpochMs = sample.timestampSec?.let { (it * 1000.0).toLong() },
                state = stateLabel,
            )
        }

        val allAlerts = kumaAlerts + promAlertsDeferred.await()

        PrometheusFetchResult(
            alerts = allAlerts,
            hostMetricsByKey = builders.mapValues { it.value.toImmutable() },
            hostUpByKey = hostUpByKey,
        )
    }

    suspend fun queryVector(promQl: String): List<PrometheusVectorSample> {
        val base = normalizedBaseUrl()
        val url = "$base/api/v1/query".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", promQl)
            ?.build()
            ?: throw MonitoringSourceException(AlertSource.PROMETHEUS, "Invalid Prometheus URL: $base")

        val body = executeGet(url.toString())
        return parseVectorResponse(body)
    }

    suspend fun fetchPrometheusAlerts(): List<MonitoringAlert> {
        val base = normalizedBaseUrl()
        val body = executeGet("$base/api/v1/alerts")
        return parseAlertsResponse(body)
    }

    private suspend fun safeQuery(promQl: String): List<PrometheusVectorSample> {
        return try {
            queryVector(promQl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MonitoringSourceException) {
            rethrowIfAuth(e)
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun rethrowIfAuth(t: Throwable) {
        if (t is CancellationException) throw t
        if (t is MonitoringSourceException && (t.httpStatus == 401 || t.httpStatus == 403 || t.message.contains("SSO", ignoreCase = true))) {
            throw t
        }
    }

    private suspend fun executeGet(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw MonitoringSourceException(
                source = AlertSource.PROMETHEUS,
                message = "Prometheus network error: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }

        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                throw MonitoringSourceException(
                    source = AlertSource.PROMETHEUS,
                    message = "Prometheus access denied (HTTP ${resp.code} — LAN or Authentik session required)",
                    httpStatus = resp.code,
                )
            }
            if (!resp.isSuccessful) {
                throw MonitoringSourceException(
                    source = AlertSource.PROMETHEUS,
                    message = "Prometheus HTTP ${resp.code}",
                    httpStatus = resp.code,
                )
            }
            if (isHtmlOrSsoRedirect(resp.request.url.encodedPath, resp.header("Content-Type"), body)) {
                throw MonitoringSourceException(
                    source = AlertSource.PROMETHEUS,
                    message = "Prometheus redirected to Authentik SSO login (HTML response)",
                    httpStatus = resp.code,
                )
            }
            body
        }
    }

    private fun parseVectorResponse(body: String): List<PrometheusVectorSample> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw MonitoringSourceException(
                source = AlertSource.PROMETHEUS,
                message = "Invalid Prometheus JSON response: ${e.message}",
                cause = e,
            )
        }
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        if (status != "success") {
            val errMsg = root["error"]?.jsonPrimitive?.contentOrNull ?: "status=$status"
            throw MonitoringSourceException(AlertSource.PROMETHEUS, "Prometheus query error: $errMsg")
        }
        val data = root["data"] as? JsonObject ?: return emptyList()
        val resultArray = data["result"] as? JsonArray ?: return emptyList()

        return resultArray.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val metricMap = (obj["metric"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { k to it }
            }?.toMap().orEmpty()
            val valueArr = obj["value"] as? JsonArray ?: return@mapNotNull null
            if (valueArr.size < 2) return@mapNotNull null
            val ts = valueArr[0].jsonPrimitive.contentOrNull?.toDoubleOrNull()
            val numVal = valueArr[1].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            if (numVal.isNaN() || numVal.isInfinite()) return@mapNotNull null
            PrometheusVectorSample(metric = metricMap, value = numVal, timestampSec = ts)
        }
    }

    private fun parseAlertsResponse(body: String): List<MonitoringAlert> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw MonitoringSourceException(
                source = AlertSource.PROMETHEUS,
                message = "Invalid Prometheus alerts JSON: ${e.message}",
                cause = e,
            )
        }
        if (root["status"]?.jsonPrimitive?.contentOrNull != "success") return emptyList()
        val alertsArray = (root["data"] as? JsonObject)?.get("alerts") as? JsonArray ?: return emptyList()

        return alertsArray.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val state = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!state.equals("firing", ignoreCase = true)) return@mapNotNull null

            val labels = (obj["labels"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { k to it }
            }?.toMap().orEmpty()
            val annotations = (obj["annotations"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { k to it }
            }?.toMap().orEmpty()

            val alertName = labels["alertname"]?.takeIf { it.isNotBlank() } ?: "Prometheus Alert"
            val instance = labels["instance"]?.takeIf { it.isNotBlank() }
            val severityStr = labels["severity"].orEmpty().lowercase(Locale.US)
            val severity = when {
                severityStr in setOf("critical", "crit", "page", "error", "high") -> AlertSeverity.CRITICAL
                severityStr in setOf("warning", "warn", "medium") -> AlertSeverity.WARNING
                else -> AlertSeverity.INFO
            }
            val targetHost = HostKeyMatcher.knownCanonicalHostOrNull(labels["host"])
                ?: HostKeyMatcher.knownCanonicalHostOrNull(instance)
                ?: HostKeyMatcher.knownCanonicalHostOrNull(labels["job"])
            val summary = annotations["summary"]?.takeIf { it.isNotBlank() }
                ?: annotations["description"]?.takeIf { it.isNotBlank() }
                ?: annotations["message"]?.takeIf { it.isNotBlank() }
                ?: instance?.let { "$alertName on $it" }
                ?: alertName
            val activeAtMs = obj["activeAt"]?.jsonPrimitive?.contentOrNull?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            val digest = HostKeyMatcher.stableLabelsDigest(labels)

            MonitoringAlert(
                id = "prom:$alertName:${instance.orEmpty()}:$digest",
                source = AlertSource.PROMETHEUS,
                severity = severity,
                title = alertName,
                summary = summary,
                targetHostName = targetHost,
                instance = instance,
                triggeredAtEpochMs = activeAtMs,
                state = state.lowercase(Locale.US),
            )
        }
    }

    private fun normalizedBaseUrl(): String {
        val raw = baseUrlProvider().trim().trimEnd('/')
        if (raw.isEmpty()) {
            throw MonitoringSourceException(AlertSource.PROMETHEUS, "Prometheus base URL is blank")
        }
        return raw
    }

    private class MutableHostMetrics(
        val hostKey: String,
        var instanceLabel: String,
        var isUp: Boolean? = null,
        var cpuPercent: Double? = null,
        var memoryPercent: Double? = null,
        var memoryUsedBytes: Long? = null,
        var memoryTotalBytes: Long? = null,
        var diskPercent: Double? = null,
        var diskUsedBytes: Long? = null,
        var diskTotalBytes: Long? = null,
        var networkRxBytesPerSec: Long? = null,
        var networkTxBytesPerSec: Long? = null,
        var uptimeSeconds: Long? = null,
        var gpuUtilizationPercent: Double? = null,
        var gpuTemperatureCelsius: Double? = null,
        var gpuMemoryUsedBytes: Long? = null,
        var gpuMemoryTotalBytes: Long? = null,
        var gpuPowerWatts: Double? = null,
        var sourceJob: String = "",
    ) {
        fun toImmutable() = PrometheusHostMetrics(
            hostKey = hostKey,
            instanceLabel = instanceLabel,
            isUp = isUp,
            cpuPercent = cpuPercent,
            memoryPercent = memoryPercent,
            memoryUsedBytes = memoryUsedBytes,
            memoryTotalBytes = memoryTotalBytes,
            diskPercent = diskPercent,
            diskUsedBytes = diskUsedBytes,
            diskTotalBytes = diskTotalBytes,
            networkRxBytesPerSec = networkRxBytesPerSec,
            networkTxBytesPerSec = networkTxBytesPerSec,
            uptimeSeconds = uptimeSeconds,
            gpuUtilizationPercent = gpuUtilizationPercent,
            gpuTemperatureCelsius = gpuTemperatureCelsius,
            gpuMemoryUsedBytes = gpuMemoryUsedBytes,
            gpuMemoryTotalBytes = gpuMemoryTotalBytes,
            gpuPowerWatts = gpuPowerWatts,
            sourceJob = sourceJob,
        )
    }

    companion object {
        private val PRIMARY_OS_JOBS = setOf("node", "windows", "esxi")
        private val IGNORED_FS_TYPES = setOf("rootfs", "tmpfs", "devtmpfs", "squashfs", "overlay")

        const val QUERY_LINUX_CPU =
            """100 - (avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[2m])) * 100)"""
        const val QUERY_LINUX_MEM_PCT =
            """100 * (1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes))"""
        const val QUERY_LINUX_MEM_TOTAL = "node_memory_MemTotal_bytes"
        const val QUERY_LINUX_MEM_AVAIL = "node_memory_MemAvailable_bytes"
        const val QUERY_LINUX_DISK_SIZE = """node_filesystem_size_bytes{mountpoint="/"}"""
        const val QUERY_LINUX_DISK_AVAIL = """node_filesystem_avail_bytes{mountpoint="/"}"""
        const val QUERY_LINUX_NET_RX =
            """sum by (instance) (rate(node_network_receive_bytes_total{device!="lo"}[2m]))"""
        const val QUERY_LINUX_NET_TX =
            """sum by (instance) (rate(node_network_transmit_bytes_total{device!="lo"}[2m]))"""

        const val QUERY_WINDOWS_CPU =
            """100 - (avg by (instance) (rate(windows_cpu_time_total{mode="idle"}[2m])) * 100)"""
        const val QUERY_WINDOWS_MEM_FREE = "windows_memory_physical_free_bytes"
        const val QUERY_WINDOWS_MEM_TOTAL = "windows_memory_physical_total_bytes"
        const val QUERY_WINDOWS_DISK_SIZE = """windows_logical_disk_size_bytes{volume="C:"}"""
        const val QUERY_WINDOWS_DISK_FREE = """windows_logical_disk_free_bytes{volume="C:"}"""

        const val QUERY_ESXI_CPU = """vsphere_host_cpu_usage_average{cpu="instance-total"}"""
        const val QUERY_ESXI_MEM_PCT = "vsphere_host_mem_usage_average"
        const val QUERY_ESXI_NET_RX = "sum by (instance) (vsphere_host_net_received_average)"
        const val QUERY_ESXI_NET_TX = "sum by (instance) (vsphere_host_net_transmitted_average)"
        const val QUERY_ESXI_UPTIME = "vsphere_host_sys_uptime_latest"

        const val QUERY_GPU_UTIL = "nvidia_smi_utilization_gpu_ratio"
        const val QUERY_GPU_TEMP = "nvidia_smi_temperature_gpu"
        const val QUERY_GPU_MEM_USED = "nvidia_smi_memory_used_bytes"
        const val QUERY_GPU_MEM_TOTAL = "nvidia_smi_memory_total_bytes"
        const val QUERY_GPU_POWER = "nvidia_smi_power_draw_watts"

        internal fun isHtmlOrSsoRedirect(
            encodedPath: String,
            contentType: String?,
            body: String,
        ): Boolean {
            if (encodedPath.contains("/if/flow/", ignoreCase = true) ||
                encodedPath.contains("/outpost.goauthentik.io/", ignoreCase = true) ||
                encodedPath.endsWith("/login", ignoreCase = true)
            ) {
                return true
            }
            if (contentType != null && contentType.contains("text/html", ignoreCase = true)) {
                return true
            }
            val trimmed = body.trimStart()
            return trimmed.startsWith("<html", ignoreCase = true) ||
                trimmed.startsWith("<!doctype html", ignoreCase = true) ||
                trimmed.startsWith("<", ignoreCase = true)
        }
    }
}
