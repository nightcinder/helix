package com.nightcinder.helix.api

import com.nightcinder.helix.core.api.TermixApi
import com.nightcinder.helix.core.api.WsTicket
import com.nightcinder.helix.core.model.AuthError
import com.nightcinder.helix.core.model.AuthentikConfig
import com.nightcinder.helix.core.model.DataStatus
import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.HostHealthItem
import com.nightcinder.helix.core.model.HostMetricsOverview
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.LiveHostMetrics
import com.nightcinder.helix.core.model.MetricsCollectionStatus
import com.nightcinder.helix.core.model.MetricsSummary
import com.nightcinder.helix.core.model.SnippetExecutionRequest
import com.nightcinder.helix.core.model.SnippetExecutionResult
import com.nightcinder.helix.core.model.SnippetSecretResolveRequest
import com.nightcinder.helix.core.model.SnippetSecretResolveResult
import com.nightcinder.helix.core.model.TermixApiException
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.TermixSnippet
import com.nightcinder.helix.core.model.WolDevice
import com.nightcinder.helix.core.model.WolWakeResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val DefaultTermixJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Low-level REST client for Termix v26.11.2.
 *
 * Enforces verified contracts:
 * - Mutating calls attach `X-Electron-App: true` without header duplication.
 * - `POST /users/ws-ticket` is used for WebSocket authentication (never `/plugins/:id/frame-token`).
 * - Maps `401` / `DATA_EXPIRED` to structured [TermixApiException.DataExpired].
 */
class TermixApiClient(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val json: Json = DefaultTermixJson,
) : TermixApi {

    constructor(
        client: OkHttpClient,
        baseUrl: String = AuthentikConfig.DEFAULT_TERMIX_URL,
        json: Json = DefaultTermixJson,
    ) : this(
        client = client,
        baseUrlProvider = { baseUrl },
        json = json,
    )

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()
    private val emptyJsonBody = "{}".toRequestBody(mediaTypeJson)

    // --- Hosts & Status ---

    override suspend fun getHosts(): List<TermixHost> {
        val request = newRequest("GET", "/host/db/host")
        return executeAndDecodeList(request, "hosts", "items", "data")
    }

    override suspend fun getHost(hostId: Long): TermixHost {
        val request = newRequest("GET", "/host/db/host/$hostId")
        return executeAndDecodeObject(request, "host", "data")
    }

    override suspend fun getDataStatus(): DataStatus {
        val request = newRequest("GET", "/users/data-status")
        return executeAndDecodeObject(request, "dataStatus", "data")
    }

    override suspend fun createWsTicket(): WsTicket {
        val request = newRequest("POST", "/users/ws-ticket", emptyJsonBody)
        return executeAndDecodeObject(request, "ticket", "data")
    }

    // --- Host Metrics ---

    // host-metrics v26.11 returns bare JSON arrays for both summaries:
    //   metrics: [{ hostId, cpu, memory, disk, lastChecked }]
    //   health:  [{ hostId, level: "ok"|"warn"|"crit", checks: [...] }] (only hosts with dashboard checks)
    override suspend fun getMetricsSummary(): MetricsSummary {
        val request = newRequest("GET", "/plugin-api/host-metrics/metrics/summary")
        val hosts: List<HostMetricsOverview> = executeAndDecodeList(request, "hosts", "items", "data", "summary")
        return MetricsSummary(hosts = hosts, totalHosts = hosts.size, onlineHosts = hosts.size)
    }

    override suspend fun getHealthSummary(): HealthSummary {
        val request = newRequest("GET", "/plugin-api/host-metrics/health/summary")
        val hosts: List<HostHealthItem> = executeAndDecodeList(request, "hosts", "items", "data", "summary")
        return HealthSummary(
            hosts = hosts,
            healthyCount = hosts.count { it.resolvedStatus == HostStatus.ONLINE },
            unhealthyCount = hosts.count { it.resolvedStatus != HostStatus.ONLINE },
        )
    }

    override suspend fun getLiveHostMetrics(hostId: Long): LiveHostMetrics {
        val request = newRequest("GET", "/plugin-api/host-metrics/host-metrics/managers/live/$hostId")
        val response = executeRaw(request)
        val metrics = handleResponse(response) { body -> parseLiveHostMetrics(json, body) }
        return if (metrics.hostId == 0L) metrics.copy(hostId = hostId) else metrics
    }

    override suspend fun startHostMetrics(hostId: Long): MetricsCollectionStatus {
        val request = newRequest("POST", "/plugin-api/host-metrics/metrics/start/$hostId", emptyJsonBody)
        val status: MetricsCollectionStatus = executeAndDecodeObject(request, "status", "data")
        return if (status.hostId == 0L) status.copy(hostId = hostId) else status
    }

    override suspend fun heartbeatHostMetrics(hostId: Long): MetricsCollectionStatus {
        val request = newRequest("POST", "/plugin-api/host-metrics/metrics/heartbeat/$hostId", emptyJsonBody)
        val status: MetricsCollectionStatus = executeAndDecodeObject(request, "status", "data")
        return if (status.hostId == 0L) status.copy(hostId = hostId) else status
    }

    // --- Wake-on-LAN ---

    override suspend fun getWolDevices(): List<WolDevice> {
        val request = newRequest("GET", "/plugin-api/wake-on-lan/devices")
        return executeAndDecodeList(request, "devices", "items", "data")
    }

    override suspend fun wakeWolDevice(hostId: Long): WolWakeResult {
        val path = "/plugin-api/wake-on-lan/devices/$hostId/wake"
        val request = newRequest("POST", path, emptyJsonBody)
        val result: WolWakeResult = executeAndDecodeObject(request, "result", "data")
        return result.copy(
            hostId = result.hostId ?: hostId,
            endpointUsed = result.endpointUsed ?: path,
        )
    }

    override suspend fun wakeHost(hostId: Long): WolWakeResult {
        val path = "/plugin-api/wake-on-lan/host/$hostId/wake"
        val request = newRequest("POST", path, emptyJsonBody)
        val result: WolWakeResult = executeAndDecodeObject(request, "result", "data")
        return result.copy(
            hostId = result.hostId ?: hostId,
            endpointUsed = result.endpointUsed ?: path,
        )
    }

    // --- Snippets ---

    override suspend fun getSnippets(): List<TermixSnippet> {
        val request = newRequest("GET", "/plugin-api/snippets")
        return executeAndDecodeList(request, "snippets", "items", "data")
    }

    override suspend fun resolveSnippetSecrets(
        request: SnippetSecretResolveRequest,
    ): SnippetSecretResolveResult {
        val body = json.encodeToString(SnippetSecretResolveRequest.serializer(), request)
            .toRequestBody(mediaTypeJson)
        val httpRequest = newRequest("POST", "/plugin-api/snippets/secrets/resolve", body)
        return executeAndDecodeObject(httpRequest, "secrets", "data")
    }

    override suspend fun executeSnippet(
        request: SnippetExecutionRequest,
    ): SnippetExecutionResult {
        val body = json.encodeToString(SnippetExecutionRequest.serializer(), request)
            .toRequestBody(mediaTypeJson)
        val httpRequest = newRequest("POST", "/plugin-api/snippets/execute", body)
        val result: SnippetExecutionResult = executeAndDecodeObject(httpRequest, "result", "data")
        return result.copy(
            snippetId = result.snippetId ?: request.snippetId,
            hostId = result.hostId ?: request.hostId,
        )
    }

    // --- Internal HTTP Execution & Parsing ---

    private fun newRequest(
        method: String,
        path: String,
        body: RequestBody? = null,
    ): Request {
        val base = baseUrlProvider().trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val fullUrl = "$base$cleanPath"

        val builder = Request.Builder()
            .url(fullUrl)
            .header("Accept", "application/json")

        val isMutating = method.equals("POST", ignoreCase = true) ||
            method.equals("PUT", ignoreCase = true) ||
            method.equals("PATCH", ignoreCase = true) ||
            method.equals("DELETE", ignoreCase = true)

        if (isMutating) {
            builder.header("X-Electron-App", "true")
        }

        builder.method(method, body)
        return builder.build()
    }

    private suspend fun executeRaw(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            TermixApiException.NetworkError(
                                message = e.message ?: "Network error connecting to ${request.url}",
                                cause = e,
                            )
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    }
                }
            })
        }

    private suspend inline fun <reified T> executeAndDecodeObject(
        request: Request,
        vararg wrapperKeys: String,
    ): T {
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            decodeObject(bodyString, serializer<T>(), *wrapperKeys)
        }
    }

    private suspend inline fun <reified T> executeAndDecodeList(
        request: Request,
        vararg wrapperKeys: String,
    ): List<T> {
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            decodeList(bodyString, serializer<List<T>>(), *wrapperKeys)
        }
    }

    private inline fun <R> handleResponse(
        response: Response,
        decoder: (String) -> R,
    ): R {
        response.use { resp ->
            val code = resp.code
            val bodyString = resp.body?.string().orEmpty()

            if (code == 401 || AuthError.isDataExpiredBody(bodyString)) {
                if (AuthError.isDataExpiredBody(bodyString)) {
                    throw TermixApiException.DataExpired(
                        message = "Termix DEK expired (DATA_EXPIRED): $bodyString",
                        code = AuthError.CODE_DATA_EXPIRED,
                        httpStatus = code,
                    )
                } else {
                    throw TermixApiException.Unauthorized(
                        message = "Unauthorized (HTTP $code)",
                        httpStatus = code,
                        responseBody = bodyString,
                    )
                }
            }

            if (!resp.isSuccessful) {
                throw TermixApiException.HttpError(
                    httpStatus = code,
                    responseBody = bodyString,
                    message = "HTTP $code from ${resp.request.url}: $bodyString",
                )
            }

            return try {
                decoder(bodyString)
            } catch (e: TermixApiException) {
                throw e
            } catch (e: Exception) {
                throw TermixApiException.ParseError(
                    message = "Failed to deserialize JSON response: ${e.message}",
                    cause = e,
                )
            }
        }
    }

    fun <T> decodeObject(
        bodyString: String,
        serializer: KSerializer<T>,
        vararg wrapperKeys: String,
    ): T {
        val trimmed = bodyString.trim()
        if (trimmed.isEmpty()) {
            // Attempt decoding empty JSON object
            return try {
                json.decodeFromString(serializer, "{}")
            } catch (e: Exception) {
                throw TermixApiException.ParseError("Empty response body cannot be deserialized", e)
            }
        }

        try {
            return json.decodeFromString(serializer, trimmed)
        } catch (directEx: Exception) {
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    val root = json.parseToJsonElement(trimmed)
                    if (root is JsonObject) {
                        val candidates = wrapperKeys.toList() + listOf("data", "result", "item")
                        for (key in candidates) {
                            val candidateElement = root[key]
                            if (candidateElement != null && candidateElement !is JsonArray) {
                                try {
                                    return json.decodeFromJsonElement(serializer, candidateElement)
                                } catch (_: Exception) {
                                    // continue trying other keys
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Fall back to throwing directEx
                }
            }
            throw TermixApiException.ParseError(
                message = "Failed to decode object: ${directEx.message}",
                cause = directEx,
            )
        }
    }

    fun <T> decodeList(
        bodyString: String,
        listSerializer: KSerializer<List<T>>,
        vararg wrapperKeys: String,
    ): List<T> {
        val trimmed = bodyString.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("[")) {
            return try {
                json.decodeFromString(listSerializer, trimmed)
            } catch (e: Exception) {
                throw TermixApiException.ParseError("Failed to decode JSON array: ${e.message}", e)
            }
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val root = json.parseToJsonElement(trimmed)
                if (root is JsonObject) {
                    val candidates = wrapperKeys.toList() + listOf("data", "items", "results")
                    for (key in candidates) {
                        val candidateElement = root[key]
                        if (candidateElement is JsonArray) {
                            try {
                                return json.decodeFromJsonElement(listSerializer, candidateElement)
                            } catch (_: Exception) {
                                // continue trying other keys
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                throw TermixApiException.ParseError("Failed to parse JSON wrapper object: ${e.message}", e)
            }
        }

        return try {
            json.decodeFromString(listSerializer, trimmed)
        } catch (e: Exception) {
            throw TermixApiException.ParseError(
                message = "Failed to decode list from body: ${e.message}",
                cause = e,
            )
        }
    }
}
