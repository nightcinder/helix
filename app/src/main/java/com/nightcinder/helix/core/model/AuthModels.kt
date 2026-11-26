package com.nightcinder.helix.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthentikConfig(
    val authentikBaseUrl: String = DEFAULT_AUTHENTIK_URL,
    val termixBaseUrl: String = DEFAULT_TERMIX_URL,
    val moonlightBaseUrl: String = DEFAULT_MOONLIGHT_URL,
    val flowSlug: String = DEFAULT_FLOW_SLUG,
    val oidcCallbackUri: String = DEFAULT_OIDC_CALLBACK_URI,
    val oidcStartPath: String = DEFAULT_OIDC_START_PATH,
    val outpostStartPath: String = DEFAULT_OUTPOST_START_PATH,
    val proxyRefreshIntervalMs: Long = DEFAULT_PROXY_REFRESH_INTERVAL_MS,
    val prometheusBaseUrl: String = DEFAULT_PROMETHEUS_URL,
    val grafanaBaseUrl: String = DEFAULT_GRAFANA_URL,
) {
    companion object {
        // Build-time defaults (HELIX_*_URL); blank until set here or on the sign-in screen.
        const val DEFAULT_AUTHENTIK_URL = SiteConfig.AUTHENTIK_URL
        const val DEFAULT_TERMIX_URL = SiteConfig.TERMIX_URL
        const val DEFAULT_MOONLIGHT_URL = SiteConfig.MOONLIGHT_URL
        const val DEFAULT_PROMETHEUS_URL = SiteConfig.PROMETHEUS_URL
        const val DEFAULT_GRAFANA_URL = SiteConfig.GRAFANA_URL
        const val DEFAULT_FLOW_SLUG = "passkey-only-authentication"
        const val DEFAULT_OIDC_CALLBACK_URI = "termix-mobile://oidc-callback"
        const val DEFAULT_OIDC_START_PATH = "/users/auth/oidc/start"
        const val DEFAULT_OUTPOST_START_PATH = "/outpost.goauthentik.io/start"
        const val DEFAULT_PROXY_REFRESH_INTERVAL_MS = 4L * 60L * 60L * 1000L
    }
}

@Serializable
data class AuthTokens(
    val termixJwt: String,
    val issuedAtEpochMs: Long = 0L,
    val expiresAtEpochMs: Long? = null,
    val userId: String? = null,
    val username: String? = null,
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs != null && nowEpochMs >= expiresAtEpochMs
}

@Serializable
data class DataStatus(
    val unlocked: Boolean = false,
    val dataUnlocked: Boolean? = null,
    val hasData: Boolean? = null,
    val encrypted: Boolean? = null,
    val initialized: Boolean? = null,
    val requiresPassword: Boolean? = null,
    val code: String? = null,
    val status: String? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val isDekUnlocked: Boolean
        get() = unlocked || dataUnlocked == true || status.equals("unlocked", ignoreCase = true)

    val isDataExpired: Boolean
        get() = code.equals(AuthError.CODE_DATA_EXPIRED, ignoreCase = true) ||
            error.equals(AuthError.CODE_DATA_EXPIRED, ignoreCase = true) ||
            status.equals(AuthError.CODE_DATA_EXPIRED, ignoreCase = true)
}

@Serializable
enum class AuthComponentState {
    UNKNOWN,
    VALID,
    REFRESHING,
    EXPIRED,
    UNAUTHENTICATED,
    ERROR,
}

@Serializable
data class HostAuthStatus(
    val label: String,
    val baseUrl: String,
    val state: AuthComponentState = AuthComponentState.UNKNOWN,
    val hasCookie: Boolean = false,
    val hasJwt: Boolean = false,
    val lastVerifiedEpochMs: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val detail: String? = null,
)

@Serializable
enum class AuthPhase {
    SIGNED_OUT,
    AUTHENTICATING_PASSKEY,
    ESTABLISHING_OUTPOST_COOKIES,
    COMPLETING_TERMIX_OIDC,
    VERIFYING_DEK,
    AUTHENTICATED,
    DEK_EXPIRED,
    ERROR,
}

data class AuthSessionState(
    val phase: AuthPhase = AuthPhase.SIGNED_OUT,
    val config: AuthentikConfig = AuthentikConfig(),
    val isAuthenticated: Boolean = false,
    val isDekUnlocked: Boolean = false,
    val username: String? = null,
    val authentikStatus: HostAuthStatus = HostAuthStatus(
        label = "Authentik SSO",
        baseUrl = AuthentikConfig.DEFAULT_AUTHENTIK_URL,
    ),
    val termixStatus: HostAuthStatus = HostAuthStatus(
        label = "Termix Proxy",
        baseUrl = AuthentikConfig.DEFAULT_TERMIX_URL,
    ),
    val moonlightStatus: HostAuthStatus = HostAuthStatus(
        label = "Moonlight Proxy",
        baseUrl = AuthentikConfig.DEFAULT_MOONLIGHT_URL,
    ),
    val dataStatus: DataStatus? = null,
    val lastError: AuthError? = null,
    val lastUpdatedEpochMs: Long = 0L,
)

@Serializable
sealed class AuthError {
    abstract val message: String

    @Serializable
    data class DataExpired(
        override val message: String = "Termix data encryption key expired (DATA_EXPIRED)",
        val code: String = CODE_DATA_EXPIRED,
        val wsCloseCode: Int? = null,
    ) : AuthError()

    @Serializable
    data class PasskeyCancelled(
        override val message: String = "Passkey sign-in was cancelled",
    ) : AuthError()

    @Serializable
    data class PasskeyFailed(
        override val message: String,
    ) : AuthError()

    @Serializable
    data class OutpostFailure(
        val host: String,
        override val message: String,
        val httpCode: Int? = null,
    ) : AuthError()

    @Serializable
    data class OidcCallbackFailure(
        override val message: String,
    ) : AuthError()

    @Serializable
    data class Network(
        override val message: String,
    ) : AuthError()

    @Serializable
    data class Unexpected(
        override val message: String,
    ) : AuthError()

    companion object {
        const val CODE_DATA_EXPIRED = "DATA_EXPIRED"
        const val WS_CLOSE_DATA_EXPIRED = 1008

        fun isDataExpiredBody(body: String?): Boolean {
            if (body.isNullOrBlank()) return false
            return body.contains(CODE_DATA_EXPIRED, ignoreCase = true)
        }
    }
}

sealed class TermixApiException(
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class DataExpired(
        message: String = "Termix DEK expired (DATA_EXPIRED)",
        val code: String = AuthError.CODE_DATA_EXPIRED,
        val httpStatus: Int = 401,
        val wsCloseCode: Int? = null,
    ) : TermixApiException(message)

    class Unauthorized(
        message: String = "Unauthorized",
        val httpStatus: Int = 401,
        val responseBody: String? = null,
    ) : TermixApiException(message)

    class HttpError(
        val httpStatus: Int,
        val errorCode: String? = null,
        val responseBody: String? = null,
        message: String = "HTTP $httpStatus${errorCode?.let { " ($it)" }.orEmpty()}",
    ) : TermixApiException(message)

    class NetworkError(
        message: String,
        cause: Throwable? = null,
    ) : TermixApiException(message, cause)

    class ParseError(
        message: String,
        cause: Throwable? = null,
    ) : TermixApiException(message, cause)
}
