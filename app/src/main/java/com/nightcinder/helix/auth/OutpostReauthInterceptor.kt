package com.nightcinder.helix.auth

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Renews expired Authentik proxy-outpost cookies without user interaction and retries the request once.
 *
 * Termix and moonlight-web sit behind Authentik forward auth. Their `authentik_proxy_*` cookies expire after a
 * few hours, while the `authentik_session` cookie lasts much longer. Once the outpost cookie is gone, the proxy
 * answers every request with a redirect to the outpost start page, which ends on an Authentik HTML page, so API
 * calls failed with a parse error until the user signed in again. With a valid `authentik_session`,
 * [refreshOutposts] (`AuthManager.refreshOutpostCookies(force = true)`) issues new outpost cookies. That needs no
 * passkey prompt as long as the proxy providers use an implicit-consent authorization flow (docs/server-setup.md);
 * a provider whose authorization flow re-checks the passkey would show the Credential Manager prompt here.
 *
 * Concurrent bounces wait on one lock and share a single refresh; a refresh already running in the auth manager
 * (for example the one on resume) is waited for by the auth manager's own lock.
 *
 * Only installed on the app's shared client. The auth manager builds its own client without it, so the refresh
 * requests never come back through here.
 */
class OutpostReauthInterceptor(
    /** Hosts behind Authentik forward auth (Termix, moonlight-web). */
    private val protectedHosts: () -> Set<String>,
    /** Authentik's own host. */
    private val authentikHost: () -> String,
    /** Blocking outpost-cookie refresh; true when new cookies were issued. */
    private val refreshOutposts: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {

    private val lock = Any()
    private var lastRefreshAtMs = 0L
    private var lastRefreshOk = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isEligible(request.url)) return chain.proceed(request)

        val response = chain.proceed(request)
        if (!isOutpostBounce(request.url, response)) return response
        // A one-shot body (a stream) cannot be sent twice; let the caller see the failure.
        if (request.body?.isOneShot() == true) return response

        response.close()
        refreshOnce()
        return chain.proceed(request)
    }

    private fun isEligible(url: HttpUrl): Boolean =
        url.host in protectedHosts() && !url.encodedPath.startsWith(OUTPOST_PATH_PREFIX)

    /**
     * The proxy bounced the request to Authentik: a followed redirect that ended on the Authentik host or an
     * outpost path, or (for clients that do not follow redirects) a redirect pointing there.
     */
    internal fun isOutpostBounce(original: HttpUrl, response: Response): Boolean {
        val finalUrl = response.request.url
        if (finalUrl.host != original.host && isAuthUrl(finalUrl)) return true
        if (response.priorResponse != null && finalUrl.encodedPath.startsWith(OUTPOST_PATH_PREFIX)) return true
        if (response.code in 300..399) {
            val location = response.header("Location") ?: return false
            val target = original.resolve(location) ?: return false
            return isAuthUrl(target)
        }
        return false
    }

    private fun isAuthUrl(url: HttpUrl): Boolean =
        url.host == authentikHost() || url.encodedPath.startsWith(OUTPOST_PATH_PREFIX)

    /** Single-flight: concurrent bounces share one refresh, and a recent result is reused. */
    private fun refreshOnce(): Boolean = synchronized(lock) {
        val now = clock()
        if (lastRefreshAtMs != 0L && now - lastRefreshAtMs < REUSE_WINDOW_MS) return lastRefreshOk
        lastRefreshOk = runCatching { refreshOutposts() }.getOrDefault(false)
        lastRefreshAtMs = clock()
        lastRefreshOk
    }

    companion object {
        const val OUTPOST_PATH_PREFIX = "/outpost.goauthentik.io/"
        const val REUSE_WINDOW_MS = 30_000L
    }
}
