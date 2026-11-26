package com.nightcinder.helix.spotify

import com.nightcinder.helix.auth.InMemoryCryptoStore
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.net.URLEncoder

internal fun MockWebServer.baseUrl(): String = url("/").toString().trimEnd('/')

internal class TestEnv(
    val server: MockWebServer,
    val scope: CoroutineScope,
    clientId: String = "abcdef0123456789abcdef0123456789",
    val clock: () -> Long = { 1_000_000L },
) {
    val store = InMemoryCryptoStore()
    val config = SpotifyClientConfig(buildClientId = clientId, store = store)
    val auth = SpotifyAuthService(
        client = OkHttpClient(),
        store = store,
        config = config,
        scope = scope,
        accountsBaseUrl = server.baseUrl(),
        clock = clock,
    )
    val api = SpotifyWebApi(OkHttpClient(), auth, server.baseUrl())

    suspend fun signInVia(state: String? = null, code: String = "auth-code"): SpotifyCallbackResult {
        val url = auth.beginAuthorization().getOrThrow()
        val realState = state ?: url.substringAfter("state=").substringBefore('&')
        return auth.handleCallback(
            "${SpotifyAuthService.REDIRECT_URI}?code=${URLEncoder.encode(code, "UTF-8")}&state=$realState",
        )
    }
}

internal fun tokenJson(access: String, refresh: String? = "refresh-1", expiresIn: Long = 3600) =
    buildString {
        append("""{"access_token":"$access","token_type":"Bearer","expires_in":$expiresIn""")
        if (refresh != null) append(""","refresh_token":"$refresh"""")
        append("}")
    }
