package com.nightcinder.helix.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OutpostReauthInterceptorTest {

    private lateinit var server: MockWebServer
    private var refreshes = 0
    private var refreshResult = true
    private var now = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val sharedClient: OkHttpClient by lazy { client() }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            OutpostReauthInterceptor(
                protectedHosts = { setOf(server.hostName) },
                authentikHost = { "auth.example.com" },
                refreshOutposts = { refreshes++; refreshResult },
                clock = { now },
            ),
        )
        .build()

    private fun bounce() {
        // The forward-auth proxy redirects to the outpost start page, which serves Authentik's HTML.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/outpost.goauthentik.io/start?rd=x"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>Authentik</html>"))
    }

    private fun get(path: String = "/api/hosts"): Pair<Int, String> =
        sharedClient.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.code to it.body!!.string() }

    @Test
    fun bounceRefreshesOutpostsAndRetriesOnce() {
        bounce()
        server.enqueue(MockResponse().setBody("""{"hosts":[]}"""))

        assertEquals(200 to """{"hosts":[]}""", get())
        assertEquals(1, refreshes)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun normalResponseDoesNotRefresh() {
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals(200 to "ok", get())
        assertEquals(0, refreshes)
    }

    @Test
    fun outpostPathItselfIsNeverIntercepted() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://auth.example.com/flow"))
        val client = client().newBuilder().followRedirects(false).build()
        client.newCall(Request.Builder().url(server.url("/outpost.goauthentik.io/start")).build()).execute().close()
        assertEquals(0, refreshes)
    }

    @Test
    fun unfollowedRedirectToAuthentikCountsAsBounce() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://auth.example.com/if/flow/x/"))
        server.enqueue(MockResponse().setBody("ok"))
        val client = client().newBuilder().followRedirects(false).build()
        val code = client.newCall(Request.Builder().url(server.url("/api/hosts")).build()).execute().use { it.code }
        assertEquals(200, code)
        assertEquals(1, refreshes)
    }

    @Test
    fun concurrentBouncesShareOneRefreshAndBothRecover() {
        // Stateful server: bounces until the refresh has run, then serves the API.
        val refreshed = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/outpost.goauthentik.io/") -> MockResponse().setBody("<html>Authentik</html>")
                !refreshed.get() -> MockResponse().setResponseCode(302).setHeader("Location", "/outpost.goauthentik.io/start?rd=x")
                else -> MockResponse().setBody("ok")
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(
                OutpostReauthInterceptor(
                    protectedHosts = { setOf(server.hostName) },
                    authentikHost = { "auth.example.com" },
                    refreshOutposts = {
                        synchronized(this) { refreshes++ }
                        Thread.sleep(300) // the second call bounces while this refresh is still running
                        refreshed.set(true)
                        true
                    },
                    clock = { now },
                ),
            )
            .build()
        val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val threads = List(2) {
            Thread {
                client.newCall(Request.Builder().url(server.url("/api/hosts")).build()).execute()
                    .use { results.add(it.body!!.string()) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        assertEquals(listOf("ok", "ok"), results.toList())
        assertEquals(1, refreshes)
    }

    @Test
    fun refreshResultIsReusedWithinTheWindow() {
        bounce()
        server.enqueue(MockResponse().setBody("one"))
        bounce()
        server.enqueue(MockResponse().setBody("two"))

        assertEquals(200 to "one", get())
        now += 5_000
        assertEquals(200 to "two", get())
        assertEquals(1, refreshes)

        now += OutpostReauthInterceptor.REUSE_WINDOW_MS
        bounce()
        server.enqueue(MockResponse().setBody("three"))
        assertEquals(200 to "three", get())
        assertEquals(2, refreshes)
    }
}
