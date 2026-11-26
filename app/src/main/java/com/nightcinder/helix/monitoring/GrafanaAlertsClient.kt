package com.nightcinder.helix.monitoring

import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.MonitoringAlert
import com.nightcinder.helix.core.model.MonitoringSourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Live Grafana Alerting client querying:
 * - `GET /api/alertmanager/grafana/api/v2/alerts?active=true` (including suppressed/silenced metadata
 *   so silenced alerts are excluded and never resurrected by `/v1/rules`)
 * - `GET /api/prometheus/grafana/api/v1/rules` (supplementary firing rule check, deduplicated)
 *
 * Detects 401/403 and HTML/SSO redirects honestly via [MonitoringSourceException].
 */
class GrafanaAlertsClient(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val json: Json = DefaultMonitoringJson,
) {

    private data class AlertmanagerParseResult(
        val activeAlerts: List<MonitoringAlert>,
        val suppressedKeys: Set<String>,
        val suppressedTitles: Set<String>,
    )

    suspend fun fetchActiveAlerts(): List<MonitoringAlert> {
        val base = normalizedBaseUrl()
        val amBody = executeGet("$base/api/alertmanager/grafana/api/v2/alerts?active=true")
        val amParsed = parseAlertmanagerV2Alerts(amBody)
        val alerts = amParsed.activeAlerts.toMutableList()
        val seenKeys = alerts.map { "${it.title.lowercase(Locale.US)}|${it.instance.orEmpty().lowercase(Locale.US)}" }.toMutableSet()
        val activeTitles = alerts.map { it.title.lowercase(Locale.US) }.toMutableSet()

        // Supplementary check for Grafana-managed alerting rules that may be firing in /v1/rules
        val rulesBody = try {
            executeGet("$base/api/prometheus/grafana/api/v1/rules")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        if (rulesBody != null) {
            for (ruleAlert in parseGrafanaRules(rulesBody)) {
                val titleLower = ruleAlert.title.lowercase(Locale.US)
                val instLower = ruleAlert.instance.orEmpty().lowercase(Locale.US)
                val dedupKey = "$titleLower|$instLower"

                if (dedupKey in amParsed.suppressedKeys || titleLower in amParsed.suppressedTitles) {
                    continue
                }
                if (ruleAlert.instance == null && titleLower in activeTitles) {
                    continue
                }
                if (seenKeys.add(dedupKey)) {
                    activeTitles.add(titleLower)
                    alerts.add(ruleAlert)
                }
            }
        }

        return alerts
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
                source = AlertSource.GRAFANA,
                message = "Grafana network error: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }

        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                throw MonitoringSourceException(
                    source = AlertSource.GRAFANA,
                    message = "Grafana access denied (HTTP ${resp.code} — LAN or Grafana OIDC session required)",
                    httpStatus = resp.code,
                )
            }
            if (!resp.isSuccessful) {
                throw MonitoringSourceException(
                    source = AlertSource.GRAFANA,
                    message = "Grafana HTTP ${resp.code}",
                    httpStatus = resp.code,
                )
            }
            if (PrometheusApiClient.isHtmlOrSsoRedirect(resp.request.url.encodedPath, resp.header("Content-Type"), body)) {
                throw MonitoringSourceException(
                    source = AlertSource.GRAFANA,
                    message = "Grafana redirected to SSO/login page (HTML response)",
                    httpStatus = resp.code,
                )
            }
            body
        }
    }

    private fun parseAlertmanagerV2Alerts(body: String): AlertmanagerParseResult {
        val array = try {
            json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            throw MonitoringSourceException(
                source = AlertSource.GRAFANA,
                message = "Invalid Grafana Alertmanager JSON: ${e.message}",
                cause = e,
            )
        }

        val activeAlerts = mutableListOf<MonitoringAlert>()
        val suppressedKeys = mutableSetOf<String>()
        val suppressedTitles = mutableSetOf<String>()

        for (elem in array) {
            val obj = elem as? JsonObject ?: continue
            val labels = obj["labels"].toStringMap()
            val annotations = obj["annotations"].toStringMap()

            val alertName = labels["alertname"]?.takeIf { it.isNotBlank() }
                ?: labels["rulename"]?.takeIf { it.isNotBlank() }
                ?: "Grafana Alert"
            val instance = labels["instance"]?.takeIf { it.isNotBlank() }
                ?: labels["host"]?.takeIf { it.isNotBlank() }

            val statusObj = obj["status"] as? JsonObject
            val state = statusObj?.get("state")?.jsonPrimitive?.contentOrNull ?: "active"
            val silencedBy = statusObj?.get("silencedBy") as? JsonArray
            val inhibitedBy = statusObj?.get("inhibitedBy") as? JsonArray
            val isSuppressed = state.equals("suppressed", ignoreCase = true) ||
                state.equals("silenced", ignoreCase = true) ||
                state.equals("inhibited", ignoreCase = true) ||
                !silencedBy.isNullOrEmpty() ||
                !inhibitedBy.isNullOrEmpty()

            if (isSuppressed) {
                val titleLower = alertName.lowercase(Locale.US)
                suppressedKeys.add("$titleLower|${instance.orEmpty().lowercase(Locale.US)}")
                suppressedTitles.add(titleLower)
                continue
            }

            val fingerprint = obj["fingerprint"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val startsAtMs = obj["startsAt"]?.jsonPrimitive?.contentOrNull?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }

            val severity = mapSeverity(labels["severity"] ?: annotations["severity"])
            val summary = annotations["summary"]?.takeIf { it.isNotBlank() }
                ?: annotations["description"]?.takeIf { it.isNotBlank() }
                ?: annotations["message"]?.takeIf { it.isNotBlank() }
                ?: instance?.let { "$alertName ($it)" }
                ?: alertName

            val targetHost = resolveTargetHost(labels, alertName)
            val digest = HostKeyMatcher.stableLabelsDigest(labels)

            activeAlerts.add(
                MonitoringAlert(
                    id = "grafana:${fingerprint ?: "$alertName:${instance.orEmpty()}:$digest"}",
                    source = AlertSource.GRAFANA,
                    severity = severity,
                    title = alertName,
                    summary = summary,
                    targetHostName = targetHost,
                    instance = instance,
                    triggeredAtEpochMs = startsAtMs,
                    state = if (state.equals("active", ignoreCase = true)) "firing" else state.lowercase(Locale.US),
                ),
            )
        }

        return AlertmanagerParseResult(
            activeAlerts = activeAlerts,
            suppressedKeys = suppressedKeys,
            suppressedTitles = suppressedTitles,
        )
    }

    private fun parseGrafanaRules(body: String): List<MonitoringAlert> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        if (root["status"]?.jsonPrimitive?.contentOrNull != "success") return emptyList()
        val groups = (root["data"] as? JsonObject)?.get("groups") as? JsonArray ?: return emptyList()

        val result = mutableListOf<MonitoringAlert>()
        for (groupElem in groups) {
            val groupObj = groupElem as? JsonObject ?: continue
            val rules = groupObj["rules"] as? JsonArray ?: continue
            for (ruleElem in rules) {
                val ruleObj = ruleElem as? JsonObject ?: continue
                val ruleState = ruleObj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!ruleState.equals("firing", ignoreCase = true) && !ruleState.equals("alerting", ignoreCase = true)) {
                    continue
                }
                val ruleName = ruleObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: "Grafana Rule"
                val ruleLabels = ruleObj["labels"].toStringMap()
                val ruleAnnotations = ruleObj["annotations"].toStringMap()
                val nestedAlerts = ruleObj["alerts"] as? JsonArray

                if (nestedAlerts != null && nestedAlerts.isNotEmpty()) {
                    for (nestedElem in nestedAlerts) {
                        val nestedObj = nestedElem as? JsonObject ?: continue
                        val nestedState = nestedObj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (!nestedState.equals("firing", ignoreCase = true) && !nestedState.equals("alerting", ignoreCase = true)) {
                            continue
                        }
                        val labels = ruleLabels + nestedObj["labels"].toStringMap()
                        val annotations = ruleAnnotations + nestedObj["annotations"].toStringMap()
                        val alertName = labels["alertname"]?.takeIf { it.isNotBlank() } ?: ruleName
                        val instance = labels["instance"]?.takeIf { it.isNotBlank() } ?: labels["host"]
                        val activeAtMs = nestedObj["activeAt"]?.jsonPrimitive?.contentOrNull?.let {
                            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                        }
                        val summary = annotations["summary"]?.takeIf { it.isNotBlank() }
                            ?: annotations["description"]?.takeIf { it.isNotBlank() }
                            ?: alertName
                        val digest = HostKeyMatcher.stableLabelsDigest(labels)
                        result.add(
                            MonitoringAlert(
                                id = "grafana-rule:$alertName:${instance.orEmpty()}:$digest",
                                source = AlertSource.GRAFANA,
                                severity = mapSeverity(labels["severity"] ?: annotations["severity"]),
                                title = alertName,
                                summary = summary,
                                targetHostName = resolveTargetHost(labels, alertName),
                                instance = instance,
                                triggeredAtEpochMs = activeAtMs,
                                state = "firing",
                            ),
                        )
                    }
                } else {
                    val instance = ruleLabels["instance"] ?: ruleLabels["host"]
                    val summary = ruleAnnotations["summary"]?.takeIf { it.isNotBlank() }
                        ?: ruleAnnotations["description"]?.takeIf { it.isNotBlank() }
                        ?: ruleName
                    val digest = HostKeyMatcher.stableLabelsDigest(ruleLabels + mapOf("rule" to ruleName))
                    result.add(
                        MonitoringAlert(
                            id = "grafana-rule:$ruleName:${instance.orEmpty()}:$digest",
                            source = AlertSource.GRAFANA,
                            severity = mapSeverity(ruleLabels["severity"] ?: ruleAnnotations["severity"]),
                            title = ruleName,
                            summary = summary,
                            targetHostName = resolveTargetHost(ruleLabels, ruleName),
                            instance = instance,
                            state = "firing",
                        ),
                    )
                }
            }
        }
        return result
    }

    private fun resolveTargetHost(labels: Map<String, String>, fallbackAlertName: String): String? {
        return HostKeyMatcher.knownCanonicalHostOrNull(labels["host"])
            ?: HostKeyMatcher.knownCanonicalHostOrNull(labels["instance"])
            ?: HostKeyMatcher.knownCanonicalHostOrNull(labels["nodename"])
            ?: HostKeyMatcher.knownCanonicalHostOrNull(fallbackAlertName)
    }

    private fun mapSeverity(raw: String?): AlertSeverity = when (raw?.trim()?.lowercase(Locale.US)) {
        "critical", "crit", "page", "error", "high" -> AlertSeverity.CRITICAL
        "info", "informational", "low", "none" -> AlertSeverity.INFO
        "warning", "warn", "medium" -> AlertSeverity.WARNING
        else -> AlertSeverity.WARNING
    }

    private fun kotlinx.serialization.json.JsonElement?.toStringMap(): Map<String, String> {
        val obj = this as? JsonObject ?: return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            v.jsonPrimitive.contentOrNull?.let { k to it }
        }.toMap()
    }

    private fun normalizedBaseUrl(): String {
        val raw = baseUrlProvider().trim().trimEnd('/')
        if (raw.isEmpty()) {
            throw MonitoringSourceException(AlertSource.GRAFANA, "Grafana base URL is blank")
        }
        return raw
    }
}
