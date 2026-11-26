package com.nightcinder.helix.core.auth

import android.app.Activity
import com.nightcinder.helix.core.model.AuthSessionState
import com.nightcinder.helix.core.model.AuthTokens
import com.nightcinder.helix.core.model.AuthentikConfig
import com.nightcinder.helix.core.model.DataStatus
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Provides Termix Bearer JWT tokens and handles DEK expiry notifications from HTTP/WebSocket layers.
 * Static `tmx_*` API keys are intentionally unsupported; only session JWTs unlocked via Authentik OIDC
 * are returned.
 */
interface TokenProvider {
    suspend fun getTermixJwt(): String?
    suspend fun getAuthTokens(): AuthTokens?
    suspend fun currentTermixBaseUrl(): String
    suspend fun refreshSessionIfNeeded(force: Boolean = false): Result<AuthTokens>
    suspend fun onDataExpired(): Result<AuthTokens>
}

/**
 * Bridges WebAuthn assertion challenges from Authentik's Flow Executor to Android's CredentialManager
 * (using the current foreground [Activity]) or a test stub.
 */
fun interface PasskeyAssertionRequester {
    suspend fun requestAssertion(requestJson: String): Result<String>
}

/**
 * Lifecycle-safe holder for the current foreground [Activity] required by Android CredentialManager.
 */
interface ActivityProvider {
    fun attachActivity(activity: Activity)
    fun detachActivity(activity: Activity)
    fun currentActivity(): Activity?
}

class WeakReferenceActivityProvider : ActivityProvider {
    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    override fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    override fun detachActivity(activity: Activity) {
        val current = activityRef?.get()
        if (current == null || current === activity) {
            activityRef = null
        }
    }

    override fun currentActivity(): Activity? = activityRef?.get()
}

/**
 * Coordinates Passkey sign-in via Authentik, per-host `forward_single` outpost cookie acquisition,
 * Termix OIDC callback completion (`termix-mobile://oidc-callback`), and DEK unlock verification.
 */
interface AuthManager {
    val sessionState: StateFlow<AuthSessionState>

    suspend fun initialize()

    suspend fun signInWithPasskey(config: AuthentikConfig? = null): Result<AuthSessionState>

    suspend fun refreshOutpostCookies(force: Boolean = false): Result<AuthSessionState>

    suspend fun checkDataStatus(): Result<DataStatus>

    suspend fun handleDataExpired(): Result<AuthSessionState>

    suspend fun onOidcCallbackUri(callbackUri: String): Result<AuthSessionState>

    suspend fun updateConfig(config: AuthentikConfig)

    suspend fun signOut()
}
