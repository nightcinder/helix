package com.nightcinder.helix.spotify

import com.nightcinder.helix.auth.CryptoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

sealed interface SpotifyAuthState {
    data object NotConfigured : SpotifyAuthState
    data object SignedOut : SpotifyAuthState
    data object Authorizing : SpotifyAuthState
    data object SignedIn : SpotifyAuthState
    data class Failed(val message: String) : SpotifyAuthState
}

sealed interface SpotifyCallbackResult {
    data object SignedIn : SpotifyCallbackResult
    data class Denied(val error: String) : SpotifyCallbackResult
    data class Rejected(val reason: String) : SpotifyCallbackResult
    data class Failed(val message: String) : SpotifyCallbackResult
}

/** Supplies Web API bearer tokens; refreshes transparently. */
interface SpotifyTokenSource {
    suspend fun accessToken(): String?

    /** Called after a 401 for [stale]; returns a usable replacement token or null (signed out). */
    suspend fun refreshAfterUnauthorized(stale: String): String?
}

/**
 * Zero-secret OAuth2 PKCE service. Deliberately independent of `BiometricLockManager`: the callback is consumed
 * (and the one-shot state/verifier cleared) even while the session is biometric-locked.
 */
class SpotifyAuthService(
    private val client: OkHttpClient,
    private val store: CryptoStore,
    private val config: SpotifyClientConfig,
    private val scope: CoroutineScope,
    private val accountsBaseUrl: String = DEFAULT_ACCOUNTS_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : SpotifyTokenSource {

    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private val _state = MutableStateFlow<SpotifyAuthState>(SpotifyAuthState.SignedOut)
    val state: StateFlow<SpotifyAuthState> = _state.asStateFlow()

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    private sealed interface TokenOutcome {
        data class Ok(val token: TokenResponse) : TokenOutcome
        data class Rejected(val code: Int) : TokenOutcome
        data class Failure(val message: String) : TokenOutcome
    }

    /** Recomputes configured / signed-in state from the store (call at startup and after a client-id change). */
    suspend fun refreshState() {
        _state.value = when {
            config.clientId() == null -> SpotifyAuthState.NotConfigured
            store.getString(KEY_REFRESH) != null || store.getString(KEY_ACCESS) != null -> SpotifyAuthState.SignedIn
            else -> SpotifyAuthState.SignedOut
        }
    }

    /** Generates verifier + state, persists them (one-shot) and returns the authorize URL to open in the browser. */
    suspend fun beginAuthorization(): Result<String> {
        val clientId = config.clientId()
        if (clientId == null) {
            _state.value = SpotifyAuthState.NotConfigured
            return Result.failure(IllegalStateException(NOT_CONFIGURED))
        }
        val verifier = Pkce.newVerifier(random)
        val state = Pkce.newState(random)
        store.putString(KEY_VERIFIER, verifier)
        store.putString(KEY_PENDING_STATE, state)
        val url = "$accountsBaseUrl/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", Pkce.challengeS256(verifier))
            .addQueryParameter("state", state)
            .addQueryParameter("scope", SCOPES.joinToString(" "))
            .build()
        _state.value = SpotifyAuthState.Authorizing
        return Result.success(url.toString())
    }

    /** Fire-and-forget entry for the callback trampoline; returns immediately. */
    fun onCallback(uri: String) {
        scope.launch { handleCallback(uri) }
    }

    suspend fun handleCallback(uri: String): SpotifyCallbackResult {
        // Consume the one-shot verifier + state BEFORE validating, so any replay finds nothing pending.
        val verifier = store.getString(KEY_VERIFIER)
        val expectedState = store.getString(KEY_PENDING_STATE)
        store.remove(KEY_VERIFIER)
        store.remove(KEY_PENDING_STATE)

        val params = parseCallback(uri)
            ?: return rejected("Unrecognised callback", expectedState != null)
        if (verifier == null || expectedState == null) return rejected("No authorization in progress", false)
        val gotState = params["state"].orEmpty()
        if (!MessageDigest.isEqual(gotState.toByteArray(), expectedState.toByteArray())) {
            return rejected("State mismatch", true)
        }
        params["error"]?.let { err ->
            _state.value = SpotifyAuthState.SignedOut
            return SpotifyCallbackResult.Denied(err)
        }
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return rejected("Missing code", true)
        val clientId = config.clientId() ?: return rejected(NOT_CONFIGURED, true)

        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        return tokenMutex.withLock {
            when (val outcome = postToken(form)) {
                is TokenOutcome.Ok -> {
                    persist(outcome.token, previousRefresh = null)
                    _state.value = SpotifyAuthState.SignedIn
                    SpotifyCallbackResult.SignedIn
                }
                is TokenOutcome.Rejected -> failed("Token exchange rejected (HTTP ${outcome.code})")
                is TokenOutcome.Failure -> failed(outcome.message)
            }
        }
    }

    override suspend fun accessToken(): String? = tokenMutex.withLock {
        val access = store.getString(KEY_ACCESS)
        val expiry = store.getString(KEY_EXPIRY)?.toLongOrNull() ?: 0L
        if (access != null && clock() < expiry - EXPIRY_SKEW_MS) return@withLock access
        refreshLocked()
    }

    override suspend fun refreshAfterUnauthorized(stale: String): String? = tokenMutex.withLock {
        val current = store.getString(KEY_ACCESS)
        if (current != null && current != stale) return@withLock current
        refreshLocked()
    }

    suspend fun signOut() {
        tokenMutex.withLock { wipeTokens() }
        store.remove(KEY_VERIFIER)
        store.remove(KEY_PENDING_STATE)
        _state.value = if (config.clientId() == null) SpotifyAuthState.NotConfigured else SpotifyAuthState.SignedOut
    }

    private suspend fun refreshLocked(): String? {
        val refresh = store.getString(KEY_REFRESH)
        val clientId = config.clientId()
        if (refresh == null || clientId == null) {
            wipeTokens()
            _state.value = if (clientId == null) SpotifyAuthState.NotConfigured else SpotifyAuthState.SignedOut
            return null
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId)
            .build()
        return when (val outcome = postToken(form)) {
            is TokenOutcome.Ok -> {
                persist(outcome.token, previousRefresh = refresh)
                outcome.token.accessToken
            }
            is TokenOutcome.Rejected -> {
                // Refresh token revoked/invalid: signed out, never retried with the same token.
                if (outcome.code == 400 || outcome.code == 401) {
                    wipeTokens()
                    _state.value = SpotifyAuthState.SignedOut
                }
                null
            }
            is TokenOutcome.Failure -> null
        }
    }

    private suspend fun persist(token: TokenResponse, previousRefresh: String?) {
        store.putString(KEY_ACCESS, token.accessToken)
        (token.refreshToken ?: previousRefresh)?.let { store.putString(KEY_REFRESH, it) }
        store.putString(KEY_EXPIRY, (clock() + token.expiresIn * 1000L).toString())
    }

    private suspend fun wipeTokens() {
        store.remove(KEY_ACCESS)
        store.remove(KEY_REFRESH)
        store.remove(KEY_EXPIRY)
    }

    private suspend fun postToken(form: FormBody): TokenOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$accountsBaseUrl/api/token").post(form).build()
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use TokenOutcome.Rejected(resp.code)
                runCatching { json.decodeFromString<TokenResponse>(body) }
                    .fold({ TokenOutcome.Ok(it) }, { TokenOutcome.Failure("Malformed token response") })
            }
        } catch (e: IOException) {
            TokenOutcome.Failure(e.message ?: "Network error")
        }
    }

    private fun rejected(reason: String, wasPending: Boolean): SpotifyCallbackResult {
        if (wasPending) _state.value = SpotifyAuthState.Failed(reason)
        return SpotifyCallbackResult.Rejected(reason)
    }

    private fun failed(message: String): SpotifyCallbackResult {
        _state.value = SpotifyAuthState.Failed(message)
        return SpotifyCallbackResult.Failed(message)
    }

    private fun parseCallback(raw: String): Map<String, String>? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != CALLBACK_SCHEME || uri.host != CALLBACK_HOST) return null
        return uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val idx = pair.indexOf('=')
            val k = if (idx < 0) pair else pair.substring(0, idx)
            val v = if (idx < 0) "" else pair.substring(idx + 1)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }

    companion object {
        const val DEFAULT_ACCOUNTS_URL = "https://accounts.spotify.com"
        const val CALLBACK_SCHEME = "com.nightcinder.helix"
        const val CALLBACK_HOST = "spotify-callback"
        const val REDIRECT_URI = "$CALLBACK_SCHEME://$CALLBACK_HOST"
        const val NOT_CONFIGURED = "Spotify not configured"
        val SCOPES = listOf(
            "playlist-read-private",
            "user-library-read",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
        )
        const val KEY_VERIFIER = "spotify.pkce.verifier"
        const val KEY_PENDING_STATE = "spotify.pkce.state"
        const val KEY_ACCESS = "spotify.token.access"
        const val KEY_REFRESH = "spotify.token.refresh"
        const val KEY_EXPIRY = "spotify.token.expiry_ms"
        private const val EXPIRY_SKEW_MS = 60_000L
    }
}
