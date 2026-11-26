package com.nightcinder.helix.auth

import com.nightcinder.helix.core.auth.AuthManager
import com.nightcinder.helix.core.auth.PasskeyAssertionRequester
import com.nightcinder.helix.core.auth.TokenProvider
import com.nightcinder.helix.core.model.AuthComponentState
import com.nightcinder.helix.core.model.AuthError
import com.nightcinder.helix.core.model.AuthPhase
import com.nightcinder.helix.core.model.AuthSessionState
import com.nightcinder.helix.core.model.AuthTokens
import com.nightcinder.helix.core.model.AuthentikConfig
import com.nightcinder.helix.core.model.DataStatus
import com.nightcinder.helix.core.model.HostAuthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Authentik passkey-only sign-in -> per-host outpost cookies -> Termix OIDC (termix-mobile://oidc-callback) -> DEK check.
 * [httpClient] must share [cookieJar]. Static `tmx_` API keys are never used.
 */
class AuthentikPasskeyAuthManager(
    httpClient: OkHttpClient,
    private val cookieJar: EncryptedCookieJar,
    private val session: TermixSessionStore,
    private val passkeyRequester: PasskeyAssertionRequester,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthManager, TokenProvider {

    private val client: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            // The shared client's OutpostReauthInterceptor would call back into this manager; keep it out.
            interceptors().removeAll { it is TermixAuthInterceptor || it is OutpostReauthInterceptor }
            addInterceptor(TermixAuthInterceptor(session))
        }
        .build()

    // Short-timeout derived client for best-effort LAN-gated monitoring auth (avoids 20s+ off-LAN stalls).
    private val monitoringAuthClient: OkHttpClient = client.newBuilder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .callTimeout(4000, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AuthSessionState())
    override val sessionState: StateFlow<AuthSessionState> = _state.asStateFlow()

    // One-shot nonce binding an OIDC callback to the sign-in attempt that started it.
    private val stateLock = Any()
    private var pendingState: String? = null
    private var pendingStateAtMs: Long = 0L

    // Single-flight DATA_EXPIRED recovery.
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryLock = Any()
    private var recoveryInFlight: Deferred<Result<AuthSessionState>>? = null
    private var lastRecoverySuccessAtMs: Long = 0L

    private class FollowResult(
        val code: Int,
        val callbackUri: String?,
        val body: String,
        val finalUrl: HttpUrl? = null,
    )

    // ---------------------------------------------------------------- AuthManager

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            session.load()
            val tokens = session.tokens()
            val usable = session.jwt() != null
            _state.value = _state.value.copy(
                config = session.config(),
                isAuthenticated = usable,
                phase = if (usable) AuthPhase.AUTHENTICATED else AuthPhase.SIGNED_OUT,
                username = tokens?.username,
            )
            refreshStatuses()
        }
    }

    override suspend fun signInWithPasskey(config: AuthentikConfig?): Result<AuthSessionState> = mutex.withLock {
        runStep {
            if (config != null) updateConfigLocked(config)
            val cfg = session.config()
            setPhase(AuthPhase.AUTHENTICATING_PASSKEY)
            runPasskeyFlow(cfg)
            setPhase(AuthPhase.ESTABLISHING_OUTPOST_COOKIES)
            establishOutposts(cfg, force = true)
            setPhase(AuthPhase.COMPLETING_TERMIX_OIDC)
            startOidcLocked(cfg)
            verifyDekLocked()
        }
    }

    override suspend fun refreshOutpostCookies(force: Boolean): Result<AuthSessionState> = mutex.withLock {
        runStep {
            val cfg = session.config()
            establishOutposts(cfg, force)
            refreshStatuses()
            _state.value
        }
    }

    override suspend fun checkDataStatus(): Result<DataStatus> = mutex.withLock {
        runCatching { fetchDataStatus() }
    }

    override suspend fun handleDataExpired(): Result<AuthSessionState> {
        val deferred = synchronized(recoveryLock) {
            recoveryInFlight?.let { return@synchronized it }
            if (lastRecoverySuccessAtMs != 0L && clock() - lastRecoverySuccessAtMs < RECOVERY_CACHE_MS) {
                return Result.success(_state.value)
            }
            recoveryScope.async {
                val r = runDataExpiredRecovery()
                synchronized(recoveryLock) {
                    if (r.isSuccess) lastRecoverySuccessAtMs = clock()
                    recoveryInFlight = null
                }
                r
            }.also { recoveryInFlight = it }
        }
        return deferred.await()
    }

    private suspend fun runDataExpiredRecovery(): Result<AuthSessionState> = mutex.withLock {
        runStep {
            val cfg = session.config()
            setPhase(AuthPhase.DEK_EXPIRED)
            establishOutposts(cfg, force = true)
            startOidcLocked(cfg)
            val result = verifyDekLocked()
            if (!result.isDekUnlocked) throw AuthFailure(AuthError.DataExpired())
            result
        }
    }

    override suspend fun onOidcCallbackUri(callbackUri: String): Result<AuthSessionState> = mutex.withLock {
        runStep {
            completeCallback(callbackUri, external = true)
            verifyDekLocked()
        }
    }

    override suspend fun updateConfig(config: AuthentikConfig) = mutex.withLock { updateConfigLocked(config) }

    override suspend fun signOut() = mutex.withLock {
        session.clearTokens()
        cookieJar.clear()
        _state.value = AuthSessionState(config = session.config())
        refreshStatuses()
    }

    // ---------------------------------------------------------------- TokenProvider

    override suspend fun getTermixJwt(): String? = session.jwt()
    override suspend fun getAuthTokens(): AuthTokens? = session.tokens()?.takeIf { session.jwt() != null }
    override suspend fun currentTermixBaseUrl(): String = session.config().termixBaseUrl

    override suspend fun refreshSessionIfNeeded(force: Boolean): Result<AuthTokens> {
        if (!force) session.jwt()?.let { return Result.success(session.tokens()!!) }
        mutex.withLock {
            runStep {
                val cfg = session.config()
                establishOutposts(cfg, force)
                startOidcLocked(cfg)
                verifyDekLocked()
            }
        }.onFailure { return Result.failure(it) }
        return session.tokens()?.let { Result.success(it) } ?: Result.failure(IllegalStateException("No Termix JWT"))
    }

    override suspend fun onDataExpired(): Result<AuthTokens> {
        handleDataExpired().onFailure { return Result.failure(it) }
        return session.tokens()?.let { Result.success(it) } ?: Result.failure(IllegalStateException("No Termix JWT"))
    }

    // ---------------------------------------------------------------- internals

    private class AuthFailure(val error: AuthError) : Exception(error.message)

    private suspend fun runStep(block: suspend () -> AuthSessionState): Result<AuthSessionState> =
        try {
            val result = block()
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthFailure) {
            fail(e.error, e)
        } catch (e: PasskeyCancelledException) {
            fail(AuthError.PasskeyCancelled(), e)
        } catch (e: java.io.IOException) {
            fail(AuthError.Network(e.message ?: "Network error"), e)
        } catch (e: Exception) {
            fail(AuthError.Unexpected(e.message ?: e.javaClass.simpleName), e)
        }

    private suspend fun fail(error: AuthError, cause: Exception): Result<AuthSessionState> {
        _state.value = _state.value.copy(
            phase = if (error is AuthError.DataExpired) AuthPhase.DEK_EXPIRED else AuthPhase.ERROR,
            lastError = error,
            lastUpdatedEpochMs = clock(),
        )
        refreshStatuses()
        return Result.failure(cause)
    }

    private fun setPhase(phase: AuthPhase) {
        _state.value = _state.value.copy(phase = phase, lastError = null, lastUpdatedEpochMs = clock())
    }

    private suspend fun updateConfigLocked(config: AuthentikConfig) {
        session.saveConfig(config)
        _state.value = _state.value.copy(config = config)
        refreshStatuses()
    }

    private fun refreshStatuses() {
        val cfg = session.config()
        val now = clock()
        fun status(label: String, base: String, prefix: String, hasJwt: Boolean): HostAuthStatus {
            val cookie = cookieJar.findCookie(base.toHostOrSelf(), prefix)
            return HostAuthStatus(
                label = label,
                baseUrl = base,
                state = if (cookie != null) AuthComponentState.VALID else AuthComponentState.UNAUTHENTICATED,
                hasCookie = cookie != null,
                hasJwt = hasJwt,
                lastVerifiedEpochMs = now,
                expiresAtEpochMs = cookie?.takeIf { it.persistent }?.expiresAt,
            )
        }
        val hasJwt = session.jwt() != null
        val prev = _state.value
        _state.value = prev.copy(
            config = cfg,
            authentikStatus = status("Authentik SSO", cfg.authentikBaseUrl, "authentik_session", false),
            termixStatus = status("Termix Proxy", cfg.termixBaseUrl, "authentik_proxy_", hasJwt),
            moonlightStatus = status("Moonlight Proxy", cfg.moonlightBaseUrl, "authentik_proxy_", false),
            username = session.tokens()?.username ?: prev.username,
            lastUpdatedEpochMs = now,
        )
    }

    private suspend fun execute(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    /**
     * POST to Authentik's API the way its web UI does. Once an authentik_session exists, Django
     * enforces CSRF on session-authenticated POSTs: HTTPS requests need a same-origin Referer and
     * the X-authentik-CSRF header matching the authentik_csrf cookie.
     */
    private fun authentikPost(cfg: AuthentikConfig, url: String, payload: JsonObject): Request {
        val base = cfg.authentikBaseUrl.trimEnd('/')
        val builder = Request.Builder().url(url)
            .header("Referer", "$base/")
            .header("Origin", base)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
        cookieJar.findCookie(base.toHostOrSelf(), "authentik_csrf")?.let {
            builder.header("X-authentik-CSRF", it.value)
        }
        return builder.build()
    }

    /**
     * Auth-flow trace, off by default. Enable on a device with
     * `adb shell setprop log.tag.HelixAuth DEBUG`, then `adb logcat -s HelixAuth`.
     */
    private fun debugLog(msg: String) {
        runCatching {
            if (android.util.Log.isLoggable(LOG_TAG, android.util.Log.DEBUG)) android.util.Log.d(LOG_TAG, msg)
        }
    }

    private suspend fun executeWithLocation(
        request: Request,
        http: OkHttpClient = client,
    ): Triple<Int, String, String?> = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { Triple(it.code, it.body?.string().orEmpty(), it.header("Location")) }
    }

    private suspend fun follow(
        start: HttpUrl,
        maxHops: Int = 12,
        http: OkHttpClient = client,
    ): FollowResult = withContext(Dispatchers.IO) {
        var url = start
        repeat(maxHops) {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val location = resp.header("Location")
                debugLog("follow ${resp.code} ${url.host}${url.encodedPath} -> ${location?.take(160)}")
                if (resp.isRedirect && location != null) {
                    if (location.startsWith(session.config().oidcCallbackUri.substringBefore("://") + "://")) {
                        return@withContext FollowResult(resp.code, location, "")
                    }
                    url = url.resolve(location) ?: return@withContext FollowResult(resp.code, null, "")
                } else {
                    return@withContext FollowResult(resp.code, null, resp.body?.string().orEmpty(), url)
                }
            }
        }
        FollowResult(310, null, "")
    }

    /**
     * follow(), and if the chain stops on an Authentik flow (HTML page or executor API), run that
     * flow to completion and continue. Used for the outpost logins and the Termix OIDC login, which
     * both pass through the provider's authorization flow (require-passkey-authorization).
     */
    private suspend fun followThroughAuthentik(
        cfg: AuthentikConfig,
        start: HttpUrl,
        http: OkHttpClient = client,
    ): FollowResult {
        val result = follow(start, http = http)
        val stoppedOn = result.finalUrl ?: return result
        if (result.callbackUri == null &&
            stoppedOn.host == cfg.authentikBaseUrl.toHostOrSelf() &&
            (stoppedOn.encodedPath.startsWith("/if/flow/") ||
                stoppedOn.encodedPath.startsWith("/api/v3/flows/executor/"))
        ) return completeAuthentikFlow(cfg, stoppedOn, http = http)
        return result
    }

    /**
     * Drives an Authentik flow that a redirect chain stopped on (an `/if/flow/<slug>/` HTML page,
     * which a browser would run with JavaScript), e.g. the OIDC authorization/consent flow.
     * Uses the flow executor API with the page's query, answers passkey/consent stages, and follows
     * the final xak-flow-redirect so the chain can reach the app or outpost callback.
     */
    private suspend fun completeAuthentikFlow(
        cfg: AuthentikConfig,
        page: HttpUrl,
        http: OkHttpClient = client,
    ): FollowResult {
        val isExecutor = page.encodedPath.startsWith("/api/v3/flows/executor/")
        val slug = page.pathSegments.getOrNull(if (isExecutor) 4 else 2)?.takeIf { it.isNotBlank() }
            ?: throw AuthFailure(AuthError.OidcCallbackFailure("Unrecognised Authentik flow page ${page.encodedPath}"))
        // Authentik may redirect straight to the executor API (already carrying ?query=...).
        var executor = if (isExecutor) page else
            "${cfg.authentikBaseUrl.trimEnd('/')}/api/v3/flows/executor/$slug/".toHttpUrl().newBuilder()
                .apply { page.query?.let { addQueryParameter("query", it) } }
                .build()
        var r = executeWithLocation(Request.Builder().url(executor).get().build(), http = http)
        repeat(MAX_FLOW_STEPS) {
            var hops = 0
            while (r.first in 300..399 && hops++ < 4) {
                val loc = r.third
                if (loc != null && loc.startsWith(cfg.oidcCallbackUri.substringBefore("://") + "://")) {
                    return FollowResult(r.first, loc, "")
                }
                val next = loc?.let { executor.resolve(it) } ?: executor
                // Back to the executor = next stage; anywhere else = the flow is done, follow it out.
                if (next.encodedPath != executor.encodedPath) return follow(next, http = http)
                r = executeWithLocation(Request.Builder().url(next).get().build(), http = http)
            }
            if (r.first !in 200..299) {
                throw AuthFailure(AuthError.OidcCallbackFailure("Authentik flow '$slug' HTTP ${r.first}"))
            }
            val obj = json.parseToJsonElement(r.second).jsonObject
            debugLog("flow '$slug' HTTP ${r.first}: ${r.second.take(400)}")
            when (val component = obj["component"]?.jsonPrimitive?.contentOrNull) {
                "xak-flow-redirect" -> {
                    val to = obj["to"]?.jsonPrimitive?.contentOrNull
                        ?: throw AuthFailure(AuthError.OidcCallbackFailure("Authentik flow '$slug' redirect without target"))
                    if (to.startsWith(cfg.oidcCallbackUri.substringBefore("://") + "://")) {
                        return FollowResult(302, to, "")
                    }
                    val target = executor.resolve(to)
                        ?: throw AuthFailure(AuthError.OidcCallbackFailure("Bad redirect target from flow '$slug'"))
                    // Authentik first redirects the executor to itself with ?query=...; keep driving it.
                    if (target.encodedPath == executor.encodedPath) {
                        executor = target
                        r = executeWithLocation(Request.Builder().url(target).get().build(), http = http)
                    } else {
                        return follow(target, http = http)
                    }
                }
                "ak-stage-authenticator-validate" -> {
                    // e.g. require-passkey-authorization: a passkey re-check before OIDC consent.
                    val challenge = (obj["device_challenges"] as? JsonArray)
                        ?.map { it.jsonObject }
                        ?.firstOrNull { it["device_class"]?.jsonPrimitive?.contentOrNull == "webauthn" }
                        ?.get("challenge")
                        ?: throw AuthFailure(AuthError.PasskeyFailed("No WebAuthn challenge in Authentik flow '$slug'"))
                    val payload = JsonObject(mapOf("webauthn" to assertion(challenge)))
                    r = executeWithLocation(authentikPost(cfg, executor.toString(), payload), http = http)
                }
                "ak-stage-consent" -> {
                    val payload = JsonObject(mapOf("token" to (obj["token"] ?: JsonPrimitive(""))))
                    r = executeWithLocation(
                        authentikPost(cfg, executor.toString(), payload),
                        http = http,
                    )
                }
                "ak-stage-access-denied" -> throw AuthFailure(
                    AuthError.OidcCallbackFailure(obj["error_message"]?.jsonPrimitive?.contentOrNull ?: "Access denied by Authentik")
                )
                else -> throw AuthFailure(AuthError.OidcCallbackFailure("Unsupported stage '$component' in Authentik flow '$slug'"))
            }
        }
        throw AuthFailure(AuthError.OidcCallbackFailure("Authentik flow '$slug' did not complete"))
    }

    // ---- Authentik flow executor

    private suspend fun runPasskeyFlow(cfg: AuthentikConfig) {
        val flowUrl = "${cfg.authentikBaseUrl.trimEnd('/')}/api/v3/flows/executor/${cfg.flowSlug}/"
        // The flow page sets the authentik_csrf cookie that authentikPost() echoes back.
        if (cookieJar.findCookie(cfg.authentikBaseUrl.toHostOrSelf(), "authentik_csrf") == null) {
            execute(Request.Builder().url("${cfg.authentikBaseUrl.trimEnd('/')}/if/flow/${cfg.flowSlug}/").get().build())
        }
        var (code, body) = execute(Request.Builder().url(flowUrl).get().build())
        repeat(MAX_FLOW_STEPS) {
            // A finished flow answers the follow-up GET with a redirect onward (to the user
            // interface); with authentik_session issued, that means the login succeeded.
            if (code in 300..399 &&
                cookieJar.findCookie(cfg.authentikBaseUrl.toHostOrSelf(), "authentik_session") != null
            ) return
            if (code !in 200..299) throw AuthFailure(AuthError.PasskeyFailed("Authentik flow HTTP $code"))
            val obj = json.parseToJsonElement(body).jsonObject
            val component = obj["component"]?.jsonPrimitive?.contentOrNull
            val payload: JsonObject = when (component) {
                "xak-flow-redirect" -> {
                    if (cookieJar.findCookie(cfg.authentikBaseUrl.toHostOrSelf(), "authentik_session") == null) {
                        throw AuthFailure(AuthError.PasskeyFailed("Authentik did not issue authentik_session"))
                    }
                    return
                }
                "ak-stage-access-denied" ->
                    throw AuthFailure(AuthError.PasskeyFailed(obj["error_message"]?.jsonPrimitive?.contentOrNull ?: "Access denied"))
                "ak-stage-authenticator-validate" -> {
                    val challenge = (obj["device_challenges"] as? JsonArray)
                        ?.map { it.jsonObject }
                        ?.firstOrNull { it["device_class"]?.jsonPrimitive?.contentOrNull == "webauthn" }
                        ?.get("challenge")
                        ?: throw AuthFailure(AuthError.PasskeyFailed("No WebAuthn challenge from Authentik"))
                    JsonObject(mapOf("webauthn" to assertion(challenge)))
                }
                "ak-stage-identification" -> {
                    val challenge = obj["passkey_challenge"]
                        ?: throw AuthFailure(AuthError.PasskeyFailed("Flow requires identification; no passkey challenge"))
                    JsonObject(mapOf("passkey" to assertion(challenge)))
                }
                else -> throw AuthFailure(AuthError.PasskeyFailed("Unsupported Authentik stage: $component"))
            }
            var r = executeWithLocation(authentikPost(cfg, flowUrl, payload))
            // The flow executor answers a stage POST with a 302 back to itself; the next
            // challenge comes from a GET on that location (as the web UI does).
            if (r.first in 300..399) {
                val next = r.third?.let { flowUrl.toHttpUrl().resolve(it) } ?: flowUrl.toHttpUrl()
                r = executeWithLocation(Request.Builder().url(next).get().build())
            }
            code = r.first; body = r.second
        }
        throw AuthFailure(AuthError.PasskeyFailed("Authentik flow did not complete"))
    }

    private suspend fun assertion(challenge: JsonElement): JsonElement {
        val result = passkeyRequester.requestAssertion(challenge.toString())
        val raw = result.getOrElse { throw it }
        return json.parseToJsonElement(raw)
    }

    // ---- Outpost

    private suspend fun establishOutposts(cfg: AuthentikConfig, force: Boolean) {
        if (cookieJar.findCookie(cfg.authentikBaseUrl.toHostOrSelf(), "authentik_session") == null) {
            throw AuthFailure(AuthError.OutpostFailure(cfg.authentikBaseUrl.toHostOrSelf(), "No valid authentik_session"))
        }
        for (base in listOf(cfg.termixBaseUrl, cfg.moonlightBaseUrl)) {
            val host = base.toHostOrSelf()
            val metaKey = "outpost_$host"
            val last = session.getMeta(metaKey)?.toLongOrNull() ?: 0L
            val hasCookie = cookieJar.findCookie(host, "authentik_proxy_") != null
            if (!force && hasCookie && clock() - last < cfg.proxyRefreshIntervalMs) continue
            val start = base.trimEnd('/').plus(cfg.outpostStartPath).toHttpUrl().newBuilder()
                .addQueryParameter("rd", base.trimEnd('/') + "/").build()
            val result = followThroughAuthentik(cfg, start)
            if (cookieJar.findCookie(host, "authentik_proxy_") == null) {
                throw AuthFailure(AuthError.OutpostFailure(host, "No authentik_proxy_* cookie issued", result.code))
            }
            session.putMeta(metaKey, clock().toString())
        }
        establishBestEffortMonitoringAuth(cfg, force)
    }

    /**
     * Best-effort authentication for LAN-gated monitoring services (Prometheus outpost + Grafana OIDC).
     * Uses [monitoringAuthClient] with tight timeouts and never throws or blocks required Termix/Moonlight
     * sign-in when off-LAN (403/timeout), while preserving coroutine cancellation.
     */
    private suspend fun establishBestEffortMonitoringAuth(cfg: AuthentikConfig, force: Boolean) {
        val promBase = cfg.prometheusBaseUrl.trim().trimEnd('/')
        if (promBase.isNotEmpty()) {
            try {
                val host = promBase.toHostOrSelf()
                val metaKey = "outpost_$host"
                val last = session.getMeta(metaKey)?.toLongOrNull() ?: 0L
                val hasCookie = cookieJar.findCookie(host, "authentik_proxy_") != null
                if (force || !hasCookie || clock() - last >= cfg.proxyRefreshIntervalMs) {
                    val start = (promBase + cfg.outpostStartPath).toHttpUrl().newBuilder()
                        .addQueryParameter("rd", "$promBase/").build()
                    followThroughAuthentik(cfg, start, http = monitoringAuthClient)
                    if (cookieJar.findCookie(host, "authentik_proxy_") != null) {
                        session.putMeta(metaKey, clock().toString())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog("Best-effort Prometheus outpost skipped: ${e.message}")
            }
        }

        val grafanaBase = cfg.grafanaBaseUrl.trim().trimEnd('/')
        if (grafanaBase.isNotEmpty()) {
            try {
                val host = grafanaBase.toHostOrSelf()
                val metaKey = "grafana_oauth_$host"
                val last = session.getMeta(metaKey)?.toLongOrNull() ?: 0L
                val hasCookie = cookieJar.findCookie(host, "grafana_session") != null
                if (force || !hasCookie || clock() - last >= cfg.proxyRefreshIntervalMs) {
                    val start = "$grafanaBase/login/generic_oauth".toHttpUrl()
                    followThroughAuthentik(cfg, start, http = monitoringAuthClient)
                    if (cookieJar.findCookie(host, "grafana_session") != null) {
                        session.putMeta(metaKey, clock().toString())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog("Best-effort Grafana OIDC skipped: ${e.message}")
            }
        }
    }

    // ---- Termix OIDC

    private suspend fun startOidcLocked(cfg: AuthentikConfig) {
        val start = (cfg.termixBaseUrl.trimEnd('/') + cfg.oidcStartPath).toHttpUrl().newBuilder()
            .addQueryParameter("returnTo", withState(cfg.oidcCallbackUri, newPendingState())).build()
        var result = followThroughAuthentik(cfg, start)
        if (result.callbackUri == null && result.code in 200..299) {
            // Some Termix builds answer with JSON {"url": "..."} instead of a 302.
            val next = runCatching {
                val o = json.parseToJsonElement(result.body).jsonObject
                (o["url"] ?: o["authUrl"] ?: o["redirectUrl"])?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            next?.toHttpUrlOrNull()?.let { result = followThroughAuthentik(cfg, it) }
        }
        val stoppedOn = result.finalUrl
        if (result.callbackUri == null && stoppedOn != null &&
            stoppedOn.host == cfg.authentikBaseUrl.toHostOrSelf() &&
            (stoppedOn.encodedPath.startsWith("/if/flow/") ||
                stoppedOn.encodedPath.startsWith("/api/v3/flows/executor/"))
        ) {
            // Authentik's authorization (consent) flow page: run it through the executor API.
            result = completeAuthentikFlow(cfg, stoppedOn)
        }
        // Termix's SSO plugin callback ignores returnTo and lands on the web UI (/?success=true),
        // setting the session JWT as a cookie instead of redirecting to termix-mobile://.
        val termixHost = cfg.termixBaseUrl.toHostOrSelf()
        val cookieJwt = if (result.callbackUri == null && result.finalUrl?.host == termixHost) {
            listOf("__Host-jwt", "jwt").firstNotNullOfOrNull { name ->
                cookieJar.findCookie(termixHost, name)?.takeIf { it.name == name }?.value
            }
        } else null
        debugLog("termix oidc end ${result.finalUrl?.host}${result.finalUrl?.encodedPath} cookies=" +
            cookieJar.cookiesForHost(termixHost).joinToString { it.name })
        val cb = result.callbackUri
            ?: cookieJwt?.let { cfg.oidcCallbackUri + (if (cfg.oidcCallbackUri.contains('?')) "&" else "?") + "token=" + it }
            ?: throw AuthFailure(AuthError.OidcCallbackFailure("Termix OIDC did not redirect to callback (HTTP ${result.code} at ${result.finalUrl?.host}${result.finalUrl?.encodedPath ?: ""})"))
        completeCallback(cb, external = false)
    }

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    /** Creates and stores the one-shot nonce that must come back as `helix_state` on external callbacks. */
    internal fun newPendingState(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        synchronized(stateLock) {
            pendingState = nonce
            pendingStateAtMs = clock()
        }
        return nonce
    }

    private fun withState(uri: String, state: String): String =
        uri + (if (uri.contains('?')) "&" else "?") + "helix_state=" + java.net.URLEncoder.encode(state, "UTF-8")

    private fun consumePendingState(): Pair<String?, Long> = synchronized(stateLock) {
        val r = pendingState to pendingStateAtMs
        pendingState = null
        pendingStateAtMs = 0L
        r
    }

    private suspend fun completeCallback(callbackUri: String, external: Boolean) {
        val uri = runCatching { URI(callbackUri) }.getOrNull()
            ?: throw AuthFailure(AuthError.OidcCallbackFailure("Malformed callback URI"))
        val expected = session.config().oidcCallbackUri
        if (!callbackUri.startsWith(expected.substringBefore('?'))) {
            throw AuthFailure(AuthError.OidcCallbackFailure("Unexpected callback URI"))
        }
        val params = parseParams(uri.rawQuery) + parseParams(uri.rawFragment)
        val (pending, pendingAt) = consumePendingState()
        val returnedState = params["helix_state"]
        val stateOk = pending != null && returnedState != null &&
            java.security.MessageDigest.isEqual(pending.toByteArray(), returnedState.toByteArray())
        if (external) {
            // Intent-delivered callbacks are untrusted: require a fresh, matching nonce.
            if (!stateOk || clock() - pendingAt > PENDING_STATE_MAX_AGE_MS) {
                throw AuthFailure(AuthError.OidcCallbackFailure("Unsolicited OIDC callback rejected"))
            }
        } else if (returnedState != null && !stateOk) {
            throw AuthFailure(AuthError.OidcCallbackFailure("OIDC callback state mismatch"))
        }
        params["error"]?.let { throw AuthFailure(AuthError.OidcCallbackFailure("Termix OIDC error: $it")) }
        val jwt = params["token"] ?: params["jwt"] ?: params["access_token"]
            ?: throw AuthFailure(AuthError.OidcCallbackFailure("Callback missing token"))
        if (jwt.startsWith("tmx_")) throw AuthFailure(AuthError.OidcCallbackFailure("Static API keys are not supported"))
        val now = clock()
        val tokens = AuthTokens(
            termixJwt = jwt,
            issuedAtEpochMs = now,
            expiresAtEpochMs = jwtExpiryMs(jwt) ?: (now + THIRTY_DAYS_MS),
            userId = params["userId"],
            username = params["username"] ?: jwtClaim(jwt, "username"),
        )
        session.saveTokens(tokens)
        _state.value = _state.value.copy(isAuthenticated = true, username = tokens.username)
    }

    private fun parseParams(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null
            else java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun jwtPayload(jwt: String): JsonObject? = runCatching {
        val part = jwt.split('.').getOrNull(1) ?: return null
        json.parseToJsonElement(String(Base64.getUrlDecoder().decode(part))).jsonObject
    }.getOrNull()

    private fun jwtExpiryMs(jwt: String): Long? =
        jwtPayload(jwt)?.get("exp")?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.times(1000)

    private fun jwtClaim(jwt: String, name: String): String? =
        (jwtPayload(jwt)?.get(name) as? JsonPrimitive)?.contentOrNull

    // ---- DEK

    private suspend fun fetchDataStatus(): DataStatus {
        val url = session.config().termixBaseUrl.trimEnd('/') + "/users/data-status"
        val (code, body) = execute(Request.Builder().url(url).get().build())
        if (code == 401 && AuthError.isDataExpiredBody(body)) {
            throw AuthFailure(AuthError.DataExpired())
        }
        if (code !in 200..299) throw AuthFailure(AuthError.Unexpected("data-status HTTP $code"))
        val status = runCatching { json.decodeFromString(DataStatus.serializer(), body) }.getOrNull()
            ?: throw AuthFailure(AuthError.Unexpected("Unparseable data-status"))
        return status
    }

    private suspend fun verifyDekLocked(): AuthSessionState {
        setPhase(AuthPhase.VERIFYING_DEK)
        val status = try {
            fetchDataStatus()
        } catch (e: AuthFailure) {
            if (e.error is AuthError.DataExpired) {
                _state.value = _state.value.copy(
                    phase = AuthPhase.DEK_EXPIRED, isDekUnlocked = false, lastError = e.error,
                    lastUpdatedEpochMs = clock(),
                )
                refreshStatuses()
                return _state.value
            }
            throw e
        }
        _state.value = _state.value.copy(
            phase = if (status.isDataExpired) AuthPhase.DEK_EXPIRED else AuthPhase.AUTHENTICATED,
            isAuthenticated = true,
            isDekUnlocked = status.isDekUnlocked && !status.isDataExpired,
            dataStatus = status,
            lastError = null,
            lastUpdatedEpochMs = clock(),
        )
        refreshStatuses()
        return _state.value
    }

    private companion object {
        const val LOG_TAG = "HelixAuth"
        const val MAX_FLOW_STEPS = 8
        const val PENDING_STATE_MAX_AGE_MS = 10L * 60 * 1000
        const val RECOVERY_CACHE_MS = 5_000L
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
