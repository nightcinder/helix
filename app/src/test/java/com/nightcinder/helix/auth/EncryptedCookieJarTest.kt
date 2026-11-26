package com.nightcinder.helix.auth

import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedCookieJarTest {
    private val termix = "http://termix.example.com/".toHttpUrl()
    private val moonlight = "http://moonlight.example.com/".toHttpUrl()

    private fun cookie(url: okhttp3.HttpUrl, header: String) = Cookie.parse(url, header)!!

    @Test
    fun perHostIsolation() {
        val jar = EncryptedCookieJar(InMemoryCryptoStore())
        jar.saveFromResponse(termix, listOf(cookie(termix, "authentik_proxy_aaa=T; Path=/; Max-Age=3600")))
        jar.saveFromResponse(moonlight, listOf(cookie(moonlight, "authentik_proxy_aaa=M; Path=/; Max-Age=3600")))
        assertEquals(listOf("T"), jar.loadForRequest(termix).map { it.value })
        assertEquals(listOf("M"), jar.loadForRequest(moonlight).map { it.value })
        assertTrue(jar.loadForRequest("http://authentik.example.com/".toHttpUrl()).isEmpty())
        assertEquals("T", jar.findCookie("termix.example.com", "authentik_proxy_")?.value)
    }

    @Test
    fun expiryDropsCookies() {
        var offset = 0L
        val jar = EncryptedCookieJar(InMemoryCryptoStore()) { System.currentTimeMillis() + offset }
        jar.saveFromResponse(termix, listOf(cookie(termix, "authentik_proxy_x=1; Path=/; Max-Age=100")))
        assertEquals(1, jar.loadForRequest(termix).size)
        offset = 200_000
        assertTrue(jar.loadForRequest(termix).isEmpty())
    }

    @Test
    fun secureCookieNotSentOverHttpAndPathRespected() {
        val jar = EncryptedCookieJar(InMemoryCryptoStore())
        jar.saveFromResponse(termix, listOf(cookie(termix, "s=1; Path=/; Secure; HttpOnly; Max-Age=100")))
        jar.saveFromResponse(termix, listOf(cookie(termix, "p=1; Path=/api; Max-Age=100")))
        assertTrue(jar.loadForRequest(termix).isEmpty())
        assertEquals(listOf("p"), jar.loadForRequest("http://termix.example.com/api/x".toHttpUrl()).map { it.name })
        assertEquals(2, jar.cookiesForHost("termix.example.com").size)
    }

    @Test
    fun persistenceRoundtrip() = runTest {
        val store = InMemoryCryptoStore()
        val a = EncryptedCookieJar(store)
        a.saveFromResponse(termix, listOf(cookie(termix, "authentik_proxy_z=abc; Path=/; HttpOnly; Max-Age=3600")))
        a.flush()
        val b = EncryptedCookieJar(store)
        b.awaitLoaded()
        val loaded = b.loadForRequest(termix)
        assertEquals("abc", loaded.single().value)
        assertTrue(loaded.single().hostOnly)
        assertTrue(loaded.single().httpOnly)
        b.clear()
        b.flush()
        val c = EncryptedCookieJar(store)
        c.awaitLoaded()
        assertNull(c.findCookie("termix.example.com", "authentik_proxy_"))
    }

    @Test
    fun sessionCookieExpiresAfterMaxAge() {
        var offset = 0L
        val jar = EncryptedCookieJar(InMemoryCryptoStore(), sessionCookieMaxAgeMs = 1_000L) { System.currentTimeMillis() + offset }
        jar.saveFromResponse(termix, listOf(cookie(termix, "sess=1; Path=/")))
        assertEquals(1, jar.loadForRequest(termix).size)
        offset = 5_000L
        assertTrue(jar.loadForRequest(termix).isEmpty())
    }
}
