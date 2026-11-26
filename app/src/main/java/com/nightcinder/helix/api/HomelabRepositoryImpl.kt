package com.nightcinder.helix.api

import com.nightcinder.helix.core.api.HomelabRepository
import com.nightcinder.helix.core.api.TermixApi
import com.nightcinder.helix.core.api.WsTicket
import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.HomelabDefaults
import com.nightcinder.helix.core.model.HostHealthItem
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.LiveHostMetrics
import com.nightcinder.helix.core.model.MetricsCollectionStatus
import com.nightcinder.helix.core.model.MetricsSummary
import com.nightcinder.helix.core.model.SnippetExecutionRequest
import com.nightcinder.helix.core.model.SnippetExecutionResult
import com.nightcinder.helix.core.model.SnippetSecretResolveRequest
import com.nightcinder.helix.core.model.TermixApiException
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.model.TermixSnippet
import com.nightcinder.helix.core.model.WolDevice
import com.nightcinder.helix.core.model.WolWakeResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Production implementation of [HomelabRepository] backed by [TermixApi].
 *
 * Exposes reactive state for hosts, live metrics, WOL devices, snippets, and loading/error states.
 * Dynamically resolves Wake-on-LAN targets at runtime without static IDs.
 */
class HomelabRepositoryImpl(
    private val api: TermixApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HomelabRepository {

    private val _hosts = MutableStateFlow<List<TermixHost>>(emptyList())
    override val hosts: StateFlow<List<TermixHost>> = _hosts.asStateFlow()

    private val _metricsSummary = MutableStateFlow<MetricsSummary?>(null)
    override val metricsSummary: StateFlow<MetricsSummary?> = _metricsSummary.asStateFlow()

    private val _healthSummary = MutableStateFlow<HealthSummary?>(null)
    override val healthSummary: StateFlow<HealthSummary?> = _healthSummary.asStateFlow()

    private val _liveMetricsByHost = MutableStateFlow<Map<Long, LiveHostMetrics>>(emptyMap())
    override val liveMetricsByHost: StateFlow<Map<Long, LiveHostMetrics>> = _liveMetricsByHost.asStateFlow()

    private val _wolDevices = MutableStateFlow<List<WolDevice>>(emptyList())
    override val wolDevices: StateFlow<List<WolDevice>> = _wolDevices.asStateFlow()

    private val _snippets = MutableStateFlow<List<TermixSnippet>>(emptyList())
    override val snippets: StateFlow<List<TermixSnippet>> = _snippets.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<TermixApiException?>(null)
    override val lastError: StateFlow<TermixApiException?> = _lastError.asStateFlow()

    override suspend fun refreshDashboard(): Result<Unit> = withContext(ioDispatcher) {
        _isLoading.value = true
        try {
            coroutineScope {
                val hostsDeferred = async { runCatching { api.getHosts() } }
                val metricsDeferred = async { runCatching { api.getMetricsSummary() } }
                val healthDeferred = async { runCatching { api.getHealthSummary() } }
                val wolDeferred = async { runCatching { api.getWolDevices() } }
                val snippetsDeferred = async { runCatching { api.getSnippets() } }

                val hostsRes = hostsDeferred.await()
                val metricsRes = metricsDeferred.await()
                val healthRes = healthDeferred.await()
                val wolRes = wolDeferred.await()
                val snippetsRes = snippetsDeferred.await()

                metricsRes.getOrNull()?.let { _metricsSummary.value = it }
                val healthSummary = healthRes.getOrNull()?.also { _healthSummary.value = it }
                wolRes.getOrNull()?.let { _wolDevices.value = it }
                snippetsRes.getOrNull()?.let { _snippets.value = it }

                hostsRes.getOrNull()?.let { rawHosts ->
                    val healthMap = healthSummary?.hosts?.associateBy { it.hostId } ?: emptyMap()
                    val enriched = rawHosts.map { host ->
                        val resolvedStatus = resolveHostStatus(host, healthMap[host.id])
                        host.copy(
                            homelabProfile = host.effectiveHomelabProfile,
                            status = resolvedStatus,
                        )
                    }
                    _hosts.value = enriched
                }

                val primaryException = hostsRes.exceptionOrNull()
                    ?: metricsRes.exceptionOrNull()
                    ?: healthRes.exceptionOrNull()
                    ?: wolRes.exceptionOrNull()
                    ?: snippetsRes.exceptionOrNull()

                if (primaryException != null) {
                    val apiEx = toTermixApiException(primaryException)
                    _lastError.value = apiEx
                    if (hostsRes.isFailure) {
                        Result.failure(apiEx)
                    } else {
                        Result.success(Unit)
                    }
                } else {
                    _lastError.value = null
                    Result.success(Unit)
                }
            }
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Health checks win when the host has dashboard checks. Otherwise a fresh sample from the
     * host-metrics poller (it SSHes to the host) means the host is reachable, so it counts as online.
     */
    private fun resolveHostStatus(host: TermixHost, health: HostHealthItem?): HostStatus {
        health?.resolvedStatus?.takeIf { it != HostStatus.UNKNOWN }?.let { return it }
        if (host.status != HostStatus.UNKNOWN) return host.status
        val sampled = _metricsSummary.value?.hosts?.any { it.hostId == host.id && it.cpuPercent != null } == true
        return if (sampled) HostStatus.ONLINE else HostStatus.UNKNOWN
    }

    override suspend fun refreshHosts(): Result<List<TermixHost>> = withContext(ioDispatcher) {
        try {
            val raw = api.getHosts()
            val healthMap = _healthSummary.value?.hosts?.associateBy { it.hostId } ?: emptyMap()
            val enriched = raw.map { host ->
                val status = resolveHostStatus(host, healthMap[host.id])
                host.copy(
                    homelabProfile = host.effectiveHomelabProfile,
                    status = status,
                )
            }
            _hosts.value = enriched
            _lastError.value = null
            Result.success(enriched)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun refreshHostLiveMetrics(
        hostId: Long,
        ensureStarted: Boolean,
    ): Result<LiveHostMetrics> = withContext(ioDispatcher) {
        try {
            if (ensureStarted) {
                try {
                    api.startHostMetrics(hostId)
                } catch (_: Exception) {
                    // Host metrics manager might already be active; continue fetching
                }
            }
            val raw = api.getLiveHostMetrics(hostId)
            // The live sample has no disk figure; the poller's summary does.
            val live = if (raw.diskPercent == null) {
                val summary = _metricsSummary.value?.hosts?.firstOrNull { it.hostId == hostId }
                raw.copy(diskPercent = summary?.diskPercent)
            } else {
                raw
            }
            _liveMetricsByHost.update { it + (hostId to live) }
            _lastError.value = null
            Result.success(live)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun heartbeatHostMetrics(hostId: Long): Result<MetricsCollectionStatus> =
        withContext(ioDispatcher) {
            try {
                val status = api.heartbeatHostMetrics(hostId)
                _lastError.value = null
                Result.success(status)
            } catch (t: Throwable) {
                val apiEx = toTermixApiException(t)
                _lastError.value = apiEx
                Result.failure(apiEx)
            }
        }

    override suspend fun refreshWolDevices(): Result<List<WolDevice>> = withContext(ioDispatcher) {
        try {
            val devices = api.getWolDevices()
            _wolDevices.value = devices
            _lastError.value = null
            Result.success(devices)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    /**
     * Resolves target WOL device dynamically from `/plugin-api/wake-on-lan/devices` at runtime
     * by MAC (normalized), hostname, or hostId. Never relies on hardcoded static IDs.
     *
     * Calls `/plugin-api/wake-on-lan/devices/{hostId}/wake`, falling back to `/host/{id}/wake`.
     */
    override suspend fun wakeDevice(
        macAddress: String?,
        hostName: String?,
        hostId: Long?,
    ): Result<WolWakeResult> = withContext(ioDispatcher) {
        try {
            val devices = try {
                val fetched = api.getWolDevices()
                _wolDevices.value = fetched
                fetched
            } catch (_: Exception) {
                _wolDevices.value
            }

            val targetMac = macAddress ?: HomelabDefaults.profileFor(hostName)?.wolMacAddress

            val matchedDevice = devices.firstOrNull { device ->
                device.matches(
                    mac = targetMac,
                    targetHostName = hostName,
                    targetHostId = hostId,
                )
            }

            val targetWakeId = matchedDevice?.effectiveHostId ?: hostId
                ?: throw TermixApiException.HttpError(
                    httpStatus = 404,
                    errorCode = "WOL_DEVICE_NOT_FOUND",
                    message = "No WOL device found matching MAC='$macAddress', hostName='$hostName', hostId=$hostId at runtime",
                )

            val wakeResult = try {
                api.wakeWolDevice(targetWakeId)
            } catch (e: TermixApiException.HttpError) {
                if (e.httpStatus == 404) {
                    api.wakeHost(targetWakeId)
                } else {
                    throw e
                }
            }

            val result = wakeResult.copy(
                deviceId = matchedDevice?.id ?: wakeResult.deviceId,
                hostId = targetWakeId,
                deviceName = matchedDevice?.name ?: hostName ?: wakeResult.deviceName,
                macAddress = matchedDevice?.macAddress ?: targetMac ?: wakeResult.macAddress,
            )
            _lastError.value = null
            Result.success(result)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun refreshSnippets(): Result<List<TermixSnippet>> = withContext(ioDispatcher) {
        try {
            val list = api.getSnippets()
            _snippets.value = list
            _lastError.value = null
            Result.success(list)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun executeSnippet(
        snippetId: Long,
        hostId: Long,
        resolveSecretsFirst: Boolean,
    ): Result<SnippetExecutionResult> = withContext(ioDispatcher) {
        try {
            val secrets = if (resolveSecretsFirst) {
                try {
                    val secretResult = api.resolveSnippetSecrets(
                        SnippetSecretResolveRequest(snippetId = snippetId, hostId = hostId)
                    )
                    secretResult.secrets
                } catch (_: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val request = SnippetExecutionRequest(
                snippetId = snippetId,
                hostId = hostId,
                resolvedSecrets = secrets,
            )
            val result = api.executeSnippet(request)
            _lastError.value = null
            Result.success(result)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override suspend fun createWsTicket(): Result<WsTicket> = withContext(ioDispatcher) {
        try {
            val ticket = api.createWsTicket()
            _lastError.value = null
            Result.success(ticket)
        } catch (t: Throwable) {
            val apiEx = toTermixApiException(t)
            _lastError.value = apiEx
            Result.failure(apiEx)
        }
    }

    override fun clearData() {
        _hosts.value = emptyList()
        _metricsSummary.value = null
        _healthSummary.value = null
        _liveMetricsByHost.value = emptyMap()
        _wolDevices.value = emptyList()
        _snippets.value = emptyList()
        _isLoading.value = false
        _lastError.value = null
    }

    private fun toTermixApiException(throwable: Throwable): TermixApiException {
        return when (throwable) {
            is TermixApiException -> throwable
            is java.io.IOException -> TermixApiException.NetworkError(
                message = throwable.message ?: "Network error",
                cause = throwable,
            )
            is kotlinx.serialization.SerializationException -> TermixApiException.ParseError(
                message = throwable.message ?: "JSON serialization error",
                cause = throwable,
            )
            else -> TermixApiException.HttpError(
                httpStatus = -1,
                message = throwable.message ?: "Unexpected error",
            )
        }
    }
}
