package com.nightcinder.helix.auth

import com.nightcinder.helix.core.model.AuthError
import okhttp3.Interceptor
import okhttp3.Response

fun interface DataExpiredListener {
    fun onDataExpired()
}

/**
 * Adds `Authorization: Bearer <jwt>` and (on mutating calls) `X-Electron-App: true` for Termix requests.
 * On `401 DATA_EXPIRED` notifies [listener] (to trigger DEK re-unlock); the response is returned unchanged so
 * callers can map it to `TermixApiException.DataExpired`.
 */
class TermixAuthInterceptor(
    private val session: TermixSessionStore,
    private val termixHost: () -> String = { session.config().termixBaseUrl.toHostOrSelf() },
    @Volatile var listener: DataExpiredListener? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != termixHost()) return chain.proceed(request)

        val builder = request.newBuilder()
        session.jwt()?.let { builder.header("Authorization", "Bearer $it") }
        if (request.method in MUTATING) builder.header("X-Electron-App", "true")

        val response = chain.proceed(builder.build())
        if (response.code == 401) {
            val body = runCatching { response.peekBody(PEEK_BYTES).string() }.getOrNull()
            if (AuthError.isDataExpiredBody(body)) listener?.onDataExpired()
        }
        return response
    }

    private companion object {
        val MUTATING = setOf("POST", "PUT", "DELETE", "PATCH")
        const val PEEK_BYTES = 8192L
    }
}

internal fun String.toHostOrSelf(): String =
    runCatching { java.net.URI(this).host }.getOrNull() ?: this
