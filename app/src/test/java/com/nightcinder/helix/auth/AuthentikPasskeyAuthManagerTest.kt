package com.nightcinder.helix.auth

import com.nightcinder.helix.core.auth.PasskeyAssertionRequester
import com.nightcinder.helix.core.model.AuthPhase
import com.nightcinder.helix.core.model.AuthentikConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class AuthentikPasskeyAuthManagerTest {
    private val authentik = MockWebServer()
    private val termix = MockWebServer()
    private val moonlight = MockWebServer()

    private val authLog = mutableListOf<String>()
    private val termixLog = mutableListOf<String>()
    private val moonlightLog = mutableListOf<String>()
    private var dataStatusBody = """{"unlocked":true}"""
    private var dataStatusCode = 200
    private var requestedChallenge: String? = null

    private val jwt = "header.eyJ1c2VybmFtZSI6InVzZXIifQ.sig"

    private fun cfg(
        prometheusBaseUrl: String = "",
        grafanaBaseUrl: String = "",
    ) = AuthentikConfig(
        authentikBaseUrl = "http://authentik.example.com:${authentik.port}",
        termixBaseUrl = "http://termix.example.com:${termix.port}",
        moonlightBaseUrl = "http://moonlight.example.com:${moonlight.port}",
        prometheusBaseUrl = prometheusBaseUrl,
        grafanaBaseUrl = grafanaBaseUrl,
    )

    @Before
    fun setUp() {
        authentik.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                authLog += "${request.method} ${request.path}"
                return when {
                    request.method == "GET" && request.path!!.startsWith("/api/v3/flows/executor/passkey-only-authentication/") ->
                        MockResponse().setBody(
                            """{"component":"ak-stage-authenticator-validate","device_challenges":[
                               {"device_class":"webauthn","challenge":{"challenge":"abc","rpId":"example.com"}}]}""",
                        )
                    request.method == "POST" ->
                        MockResponse()
                            .addHeader("Set-Cookie", "authentik_session=sess; Path=/; HttpOnly; Max-Age=86400")
                            .setBody("""{"component":"xak-flow-redirect","to":"/"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        termix.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                termixLog += "${request.method} ${request.path} auth=${request.getHeader("Authorization")}"
                val p = request.path!!
                return when {
                    p.startsWith("/outpost.goauthentik.io/start") ->
                        MockResponse().setResponseCode(302)
                            .addHeader("Set-Cookie", "authentik_proxy_t1=tp; Path=/; Max-Age=14400")
                            .addHeader("Location", "/")
                    p.startsWith("/users/auth/oidc/start") ->
                        if (request.getHeader("Cookie")?.contains("authentik_proxy_t1=tp") == true)
                            MockResponse().setResponseCode(302)
                                .addHeader("Location", "termix-mobile://oidc-callback?token=$jwt")
                        else MockResponse().setResponseCode(403)
                    p.startsWith("/users/data-status") ->
                        MockResponse().setResponseCode(dataStatusCode).setBody(dataStatusBody)
                    else -> MockResponse().setBody("ok")
                }
            }
        }
        moonlight.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                moonlightLog += "${request.method} ${request.path}"
                return MockResponse().setResponseCode(302)
                    .addHeader("Set-Cookie", "authentik_proxy_m1=mp; Path=/; Max-Age=14400")
                    .addHeader("Location", "/")
            }
        }
        listOf(authentik, termix, moonlight).forEach { it.start() }
    }

    @After
    fun tearDown() {
        listOf(authentik, termix, moonlight).forEach { it.shutdown() }
    }

    private fun newManager(): Triple<AuthentikPasskeyAuthManager, EncryptedCookieJar, TermixSessionStore> {
        val store = InMemoryCryptoStore()
        val jar = EncryptedCookieJar(store)
        val session = TermixSessionStore(store)
        val loopback = InetAddress.getByName("127.0.0.1")
        val http = OkHttpClient.Builder()
            .cookieJar(jar)
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    if (hostname.endsWith("example.com")) listOf(loopback) else Dns.SYSTEM.lookup(hostname)
            })
            .addInterceptor(TermixAuthInterceptor(session))
            .build()
        val requester = PasskeyAssertionRequester { req ->
            requestedChallenge = req
            Result.success("""{"id":"cred","rawId":"cred","type":"public-key","response":{"signature":"sig"}}""")
        }
        return Triple(AuthentikPasskeyAuthManager(http, jar, session, requester), jar, session)
    }

    @Test
    fun fullPasskeyToTermixFlow() = runTest {
        val (manager, jar, session) = newManager()
        manager.updateConfig(cfg())

        val result = manager.signInWithPasskey()
        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)

        // Authentik flow executor + WebAuthn challenge handed to requester
        // The flow page is loaded first for the authentik_csrf cookie, then the executor.
        assertTrue(authLog.first().startsWith("GET /if/flow/passkey-only-authentication/"))
        assertTrue(authLog.any { it.startsWith("GET /api/v3/flows/executor/passkey-only-authentication/") })
        assertTrue(authLog.any { it.startsWith("POST /api/v3/flows/executor/passkey-only-authentication/") })
        assertTrue(requestedChallenge!!.contains("\"challenge\":\"abc\""))
        assertNotNull(jar.findCookie("authentik.example.com", "authentik_session"))

        // separate proxy cookies per host
        assertEquals("tp", jar.findCookie("termix.example.com", "authentik_proxy_")?.value)
        assertEquals("mp", jar.findCookie("moonlight.example.com", "authentik_proxy_")?.value)
        assertTrue(moonlightLog.first().startsWith("GET /outpost.goauthentik.io/start"))

        // OIDC + JWT + data-status with Bearer
        assertTrue(termixLog.any { it.startsWith("GET /users/auth/oidc/start?returnTo=termix-mobile") })
        assertEquals(jwt, session.jwt())
        assertTrue(termixLog.any { it == "GET /users/data-status auth=Bearer $jwt" })

        val state = manager.sessionState.value
        assertEquals(AuthPhase.AUTHENTICATED, state.phase)
        assertTrue(state.isDekUnlocked)
        assertEquals("user", state.username)
        assertEquals(jwt, manager.getTermixJwt())
    }

    @Test
    fun outpostRefreshIsSkippedWhileFreshUnlessForced() = runTest {
        val (manager, _, _) = newManager()
        manager.updateConfig(cfg())
        manager.signInWithPasskey()
        val before = moonlightLog.size
        assertTrue(manager.refreshOutpostCookies(force = false).isSuccess)
        assertEquals(before, moonlightLog.size)
        assertTrue(manager.refreshOutpostCookies(force = true).isSuccess)
        assertTrue(moonlightLog.size > before)
    }

    @Test
    fun dataExpiredSilentlyReauthenticates() = runTest {
        val (manager, _, _) = newManager()
        manager.updateConfig(cfg())
        manager.signInWithPasskey()
        dataStatusCode = 401
        dataStatusBody = """{"code":"DATA_EXPIRED"}"""
        assertTrue(manager.checkDataStatus().isFailure)
        dataStatusCode = 200
        dataStatusBody = """{"unlocked":true}"""
        val r = manager.handleDataExpired()
        assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
        assertTrue(manager.sessionState.value.isDekUnlocked)
        assertEquals(1, authLog.count { it.startsWith("POST") }) // no second passkey prompt
    }

    @Test
    fun dataExpiredStaysFlaggedWhenStillExpired() = runTest {
        val (manager, _, _) = newManager()
        manager.updateConfig(cfg())
        dataStatusCode = 401
        dataStatusBody = """{"code":"DATA_EXPIRED"}"""
        manager.signInWithPasskey()
        assertEquals(AuthPhase.DEK_EXPIRED, manager.sessionState.value.phase)
        assertFalse(manager.sessionState.value.isDekUnlocked)
    }

    @Test
    fun onOidcCallbackUriStoresJwt() = runTest {
        val (manager, _, session) = newManager()
        manager.updateConfig(cfg())
        val state = manager.newPendingState()
        val r = manager.onOidcCallbackUri("termix-mobile://oidc-callback?token=$jwt&helix_state=$state")
        assertTrue(r.isSuccess)
        assertEquals(jwt, session.jwt())
        // nonce is one-shot
        assertTrue(manager.onOidcCallbackUri("termix-mobile://oidc-callback?token=$jwt&helix_state=$state").isFailure)
        val state2 = manager.newPendingState()
        assertTrue(manager.onOidcCallbackUri("termix-mobile://oidc-callback?error=denied&helix_state=$state2").isFailure)
    }

    @Test
    fun unsolicitedOidcCallbackIsRejected() = runTest {
        val (manager, _, session) = newManager()
        manager.updateConfig(cfg())
        // no pending nonce
        assertTrue(manager.onOidcCallbackUri("termix-mobile://oidc-callback?token=$jwt").isFailure)
        // pending nonce but wrong / missing state
        manager.newPendingState()
        assertTrue(manager.onOidcCallbackUri("termix-mobile://oidc-callback?token=$jwt&helix_state=wrong").isFailure)
        manager.newPendingState()
        assertTrue(manager.onOidcCallbackUri("termix-mobile://oidc-callback?token=$jwt").isFailure)
        assertEquals(null, session.jwt())
    }

    @Test
    fun parallelDataExpiredCallsShareOneRecovery() = runTest {
        val (manager, _, _) = newManager()
        manager.updateConfig(cfg())
        manager.signInWithPasskey()
        val before = termixLog.count { it.startsWith("GET /users/auth/oidc/start") }
        val results = List(5) { async { manager.handleDataExpired() } }.map { it.await() }
        assertTrue(results.all { it.isSuccess })
        assertEquals(1, termixLog.count { it.startsWith("GET /users/auth/oidc/start") } - before)
        // within 5 s of success: cached, no rerun
        assertTrue(manager.handleDataExpired().isSuccess)
        assertEquals(1, termixLog.count { it.startsWith("GET /users/auth/oidc/start") } - before)
    }

    @Test
    fun bestEffortMonitoringAuthAcquiresCookiesAndToleratesOffLan403() = runTest {
        val prom = MockWebServer()
        val grafana = MockWebServer()
        var monitoringAllowed = true
        prom.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!monitoringAllowed) return MockResponse().setResponseCode(403)
                return MockResponse().setResponseCode(302)
                    .addHeader("Set-Cookie", "authentik_proxy_p1=pp; Path=/; Max-Age=14400")
                    .addHeader("Location", "/")
            }
        }
        grafana.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!monitoringAllowed) return MockResponse().setResponseCode(403)
                return if (request.path?.startsWith("/login/generic_oauth") == true) {
                    MockResponse().setResponseCode(302)
                        .addHeader("Set-Cookie", "grafana_session=gsess; Path=/; HttpOnly")
                        .addHeader("Location", "/")
                } else {
                    MockResponse().setBody("ok")
                }
            }
        }
        prom.start()
        grafana.start()
        try {
            val (manager, jar, _) = newManager()
            val monCfg = cfg(
                prometheusBaseUrl = "http://prometheus.example.com:${prom.port}",
                grafanaBaseUrl = "http://grafana.example.com:${grafana.port}",
            )
            assertTrue(manager.signInWithPasskey(monCfg).isSuccess)
            assertNotNull(jar.findCookie("prometheus.example.com", "authentik_proxy_"))
            assertNotNull(jar.findCookie("grafana.example.com", "grafana_session"))

            // Off-LAN 403 must not fail sign-in or outpost refresh
            monitoringAllowed = false
            jar.clear()
            assertTrue(manager.signInWithPasskey(monCfg).isSuccess)
            assertEquals(AuthPhase.AUTHENTICATED, manager.sessionState.value.phase)
        } finally {
            prom.shutdown()
            grafana.shutdown()
        }
    }
}
