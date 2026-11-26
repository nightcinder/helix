package com.nightcinder.helix.auth

import com.nightcinder.helix.core.model.AuthTokens
import com.nightcinder.helix.core.model.AuthentikConfig
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TermixAuthInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var session: TermixSessionStore
    private var expired = 0

    @Before
    fun setUp() = runTest {
        server = MockWebServer().also { it.start() }
        session = TermixSessionStore(InMemoryCryptoStore())
        session.saveConfig(AuthentikConfig(termixBaseUrl = "http://${server.hostName}:${server.port}"))
        session.saveTokens(AuthTokens(termixJwt = "jwt-123"))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(host: String = server.hostName) = OkHttpClient.Builder()
        .addInterceptor(TermixAuthInterceptor(session, { host }, { expired++ }))
        .build()

    @Test
    fun getHasBearerButNoElectronHeader() {
        server.enqueue(MockResponse().setBody("{}"))
        client().newCall(Request.Builder().url(server.url("/host/db/host")).build()).execute().close()
        val r = server.takeRequest()
        assertEquals("Bearer jwt-123", r.getHeader("Authorization"))
        assertNull(r.getHeader("X-Electron-App"))
    }

    @Test
    fun mutatingMethodsSendElectronHeader() {
        for (m in listOf("POST", "PUT", "DELETE", "PATCH")) {
            server.enqueue(MockResponse().setBody("{}"))
            client().newCall(Request.Builder().url(server.url("/x")).method(m, "{}".toRequestBody()).build()).execute().close()
            val r = server.takeRequest()
            assertEquals(m, r.method)
            assertEquals("true", r.getHeader("X-Electron-App"))
            assertEquals("Bearer jwt-123", r.getHeader("Authorization"))
        }
    }

    @Test
    fun otherHostsUntouched() {
        server.enqueue(MockResponse())
        client(host = "termix.example.com").newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun staticApiKeysAreNeverSent() = runTest {
        session.saveTokens(AuthTokens(termixJwt = "tmx_static_key"))
        server.enqueue(MockResponse())
        client().newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun dataExpiredTriggersListenerAndKeepsBody() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"x","code":"DATA_EXPIRED"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"nope"}"""))
        val c = client()
        val resp = c.newCall(Request.Builder().url(server.url("/users/data-status")).build()).execute()
        assertEquals(401, resp.code)
        assertTrue(resp.body!!.string().contains("DATA_EXPIRED"))
        assertEquals(1, expired)
        c.newCall(Request.Builder().url(server.url("/y")).build()).execute().close()
        assertEquals(1, expired)
    }
}
