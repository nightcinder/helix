package com.nightcinder.helix.spotify

import com.nightcinder.helix.auth.BiometricLockManager
import com.nightcinder.helix.auth.BiometricLockPrefs
import com.nightcinder.helix.auth.BiometricLockStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyAuthServiceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun authorizeUrlCarriesS256ChallengeStateScopesAndNoSecret() = runTest {
        val env = TestEnv(server, this)
        val url = env.auth.beginAuthorization().getOrThrow().toHttpUrl()
        assertEquals("/authorize", url.encodedPath)
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(SpotifyAuthService.REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals(SpotifyAuthService.SCOPES.joinToString(" "), url.queryParameter("scope"))
        assertNull(url.queryParameter("client_secret"))
        val verifier = env.store.getString(SpotifyAuthService.KEY_VERIFIER)!!
        assertEquals(Pkce.challengeS256(verifier), url.queryParameter("code_challenge"))
        assertEquals(env.store.getString(SpotifyAuthService.KEY_PENDING_STATE), url.queryParameter("state"))
    }

    @Test
    fun callbackExchangesCodeWithVerifierAndNoClientSecret() = runTest {
        val env = TestEnv(server, this)
        server.enqueue(MockResponse().setBody(tokenJson("access-1")))
        assertEquals(SpotifyCallbackResult.SignedIn, env.signInVia(code = "abc"))
        val verifier = env.store.getString(SpotifyAuthService.KEY_ACCESS)
        assertEquals("access-1", verifier)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(body.contains("code=abc"))
        assertTrue(body.contains("code_verifier="))
        assertFalse(body.contains("client_secret"))
        assertEquals(SpotifyAuthState.SignedIn, env.auth.state.value)
        assertEquals("refresh-1", env.store.getString(SpotifyAuthService.KEY_REFRESH))
    }

    @Test
    fun stateMismatchIsRejectedAndConsumesPending() = runTest {
        val env = TestEnv(server, this)
        val result = env.signInVia(state = "forged")
        assertTrue(result is SpotifyCallbackResult.Rejected)
        assertEquals(0, server.requestCount)
        assertNull(env.store.getString(SpotifyAuthService.KEY_VERIFIER))
        assertNull(env.store.getString(SpotifyAuthService.KEY_PENDING_STATE))
        assertNull(env.store.getString(SpotifyAuthService.KEY_ACCESS))
    }

    @Test
    fun replayedCallbackIsRejected() = runTest {
        val env = TestEnv(server, this)
        server.enqueue(MockResponse().setBody(tokenJson("access-1")))
        val url = env.auth.beginAuthorization().getOrThrow()
        val state = url.substringAfter("state=").substringBefore('&')
        val cb = "${SpotifyAuthService.REDIRECT_URI}?code=c&state=$state"
        assertEquals(SpotifyCallbackResult.SignedIn, env.auth.handleCallback(cb))
        assertTrue(env.auth.handleCallback(cb) is SpotifyCallbackResult.Rejected)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun callbackIsConsumedWhileBiometricLockIsLocked() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val lock = BiometricLockManager(
            store = object : BiometricLockStore {
                override suspend fun read() = BiometricLockPrefs(enabled = true, timeoutMinutes = 5)
                override suspend fun write(prefs: BiometricLockPrefs) = Unit
            },
            scope = scope,
            deviceSupportsAuth = { true },
        )
        scope.advanceUntilIdle()
        lock.lockNow(isAuthenticated = true)
        scope.advanceUntilIdle()
        assertTrue(lock.state.value.isLocked)

        val env = TestEnv(server, this)
        server.enqueue(MockResponse().setBody(tokenJson("access-locked")))
        assertEquals(SpotifyCallbackResult.SignedIn, env.signInVia())
        assertNull(env.store.getString(SpotifyAuthService.KEY_PENDING_STATE))
        assertTrue(lock.state.value.isLocked)
    }

    @Test
    fun accessTokenRefreshesOnExpiryAndKeepsRefreshTokenWhenOmitted() = runTest {
        var now = 1_000_000L
        val env = TestEnv(server, this, clock = { now })
        server.enqueue(MockResponse().setBody(tokenJson("access-1", expiresIn = 3600)))
        env.signInVia()
        server.takeRequest()
        assertEquals("access-1", env.auth.accessToken())
        now += 3_600_000L
        server.enqueue(MockResponse().setBody(tokenJson("access-2", refresh = null)))
        assertEquals("access-2", env.auth.accessToken())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=refresh-1"))
        assertEquals("refresh-1", env.store.getString(SpotifyAuthService.KEY_REFRESH))
    }

    @Test
    fun revokedRefreshTokenSignsOut() = runTest {
        var now = 1_000_000L
        val env = TestEnv(server, this, clock = { now })
        server.enqueue(MockResponse().setBody(tokenJson("access-1")))
        env.signInVia()
        now += 4_000_000L
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        assertNull(env.auth.accessToken())
        assertEquals(SpotifyAuthState.SignedOut, env.auth.state.value)
        assertNull(env.store.getString(SpotifyAuthService.KEY_REFRESH))
    }

    @Test
    fun signOutWipesTokensAndPendingState() = runTest {
        val env = TestEnv(server, this)
        server.enqueue(MockResponse().setBody(tokenJson("access-1")))
        env.signInVia()
        env.auth.beginAuthorization()
        env.auth.signOut()
        listOf(
            SpotifyAuthService.KEY_ACCESS, SpotifyAuthService.KEY_REFRESH, SpotifyAuthService.KEY_EXPIRY,
            SpotifyAuthService.KEY_VERIFIER, SpotifyAuthService.KEY_PENDING_STATE,
        ).forEach { assertNull(it, env.store.getString(it)) }
        assertEquals(SpotifyAuthState.SignedOut, env.auth.state.value)
    }

    @Test
    fun blankClientIdIsNotConfiguredAndOverrideEnablesIt() = runTest {
        val env = TestEnv(server, this, clientId = "  ")
        env.auth.refreshState()
        assertEquals(SpotifyAuthState.NotConfigured, env.auth.state.value)
        val r = env.auth.beginAuthorization()
        assertEquals(SpotifyAuthService.NOT_CONFIGURED, r.exceptionOrNull()?.message)
        assertFalse(env.config.setOverride("bad id!"))
        assertTrue(env.config.setOverride("override0123456789"))
        env.auth.refreshState()
        assertEquals(SpotifyAuthState.SignedOut, env.auth.state.value)
    }

    @Test
    fun deniedAuthorizationReportsDenied() = runTest {
        val env = TestEnv(server, this)
        val url = env.auth.beginAuthorization().getOrThrow()
        val state = url.substringAfter("state=").substringBefore('&')
        val r = env.auth.handleCallback("${SpotifyAuthService.REDIRECT_URI}?error=access_denied&state=$state")
        assertEquals(SpotifyCallbackResult.Denied("access_denied"), r)
    }
}
