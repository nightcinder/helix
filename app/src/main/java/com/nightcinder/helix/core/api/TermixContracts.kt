package com.nightcinder.helix.core.api

import com.nightcinder.helix.core.model.DataStatus
import com.nightcinder.helix.core.model.HealthSummary
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WsTicket(
    @JsonNames("ticket", "token", "wsTicket")
    val ticket: String = "",
    val expiresInSeconds: Long? = null,
    val expiresAtEpochMs: Long? = null,
)

/**
 * Low-level Termix v26.11.2 REST contract.
 *
 * Implementations must:
 * - Attach `X-Electron-App: true` on mutating requests (via interceptor or client).
 * - Use `POST /users/ws-ticket` (never `/plugins/:id/frame-token`).
 * - Map `401` / `DATA_EXPIRED` payloads to [TermixApiException.DataExpired].
 */
interface TermixApi {
    suspend fun getHosts(): List<TermixHost>

    suspend fun getHost(hostId: Long): TermixHost

    suspend fun getDataStatus(): DataStatus

    suspend fun createWsTicket(): WsTicket

    suspend fun getMetricsSummary(): MetricsSummary

    suspend fun getHealthSummary(): HealthSummary

    suspend fun getLiveHostMetrics(hostId: Long): LiveHostMetrics

    suspend fun startHostMetrics(hostId: Long): MetricsCollectionStatus

    suspend fun heartbeatHostMetrics(hostId: Long): MetricsCollectionStatus

    suspend fun getWolDevices(): List<WolDevice>

    suspend fun wakeWolDevice(hostId: Long): WolWakeResult

    suspend fun wakeHost(hostId: Long): WolWakeResult

    suspend fun getSnippets(): List<TermixSnippet>

    suspend fun resolveSnippetSecrets(
        request: SnippetSecretResolveRequest,
    ): SnippetSecretResolveResult

    suspend fun executeSnippet(request: SnippetExecutionRequest): SnippetExecutionResult
}

/**
 * Reactive homelab data repository backed by [TermixApi].
 */
interface HomelabRepository {
    val hosts: StateFlow<List<TermixHost>>
    val metricsSummary: StateFlow<MetricsSummary?>
    val healthSummary: StateFlow<HealthSummary?>
    val liveMetricsByHost: StateFlow<Map<Long, LiveHostMetrics>>
    val wolDevices: StateFlow<List<WolDevice>>
    val snippets: StateFlow<List<TermixSnippet>>
    val isLoading: StateFlow<Boolean>
    val lastError: StateFlow<TermixApiException?>

    suspend fun refreshDashboard(): Result<Unit>

    suspend fun refreshHosts(): Result<List<TermixHost>>

    suspend fun refreshHostLiveMetrics(
        hostId: Long,
        ensureStarted: Boolean = true,
    ): Result<LiveHostMetrics>

    suspend fun heartbeatHostMetrics(hostId: Long): Result<MetricsCollectionStatus>

    suspend fun refreshWolDevices(): Result<List<WolDevice>>

    /**
     * Dynamically resolves the target device from `/plugin-api/wake-on-lan/devices` by
     * case/separator-normalized [macAddress], [hostName], or [hostId] without static IDs.
     */
    suspend fun wakeDevice(
        macAddress: String? = null,
        hostName: String? = null,
        hostId: Long? = null,
    ): Result<WolWakeResult>

    suspend fun refreshSnippets(): Result<List<TermixSnippet>>

    suspend fun executeSnippet(
        snippetId: Long,
        hostId: Long,
        resolveSecretsFirst: Boolean = true,
    ): Result<SnippetExecutionResult>

    suspend fun createWsTicket(): Result<WsTicket>

    fun clearData()
}
