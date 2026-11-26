package com.nightcinder.helix.auth

import com.nightcinder.helix.core.model.AuthentikConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.CountDownLatch

@Serializable
internal data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val persistent: Boolean,
    val hostOnly: Boolean,
    val secure: Boolean,
    val httpOnly: Boolean,
    // Epoch ms when a non-persistent (session) cookie was stored; 0 = unknown (legacy JSON).
    val savedAt: Long = 0L,
)

/**
 * CookieJar persisted through [CryptoStore]. Cookies are identified by (name, domain, path) so the separate
 * `authentik_proxy_*` cookies of `termix.*` and `moonlight.*` (host-only) stay isolated.
 *
 * The store is loaded once on a background thread; the synchronous [CookieJar] API only waits for that load
 * (on OkHttp threads). Writes are debounced and performed asynchronously.
 */
class EncryptedCookieJar(
    private val store: CryptoStore,
    private val writeDebounceMs: Long = 250L,
    private val sessionCookieMaxAgeMs: Long = AuthentikConfig.DEFAULT_PROXY_REFRESH_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<StoredCookie>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadedLatch = CountDownLatch(1)
    private var clearedBeforeLoad = false
    private var writeJob: Job? = null

    init {
        scope.launch {
            try {
                val raw = runCatching { store.getString(STORE_KEY) }.getOrNull()
                val loaded = raw?.let {
                    runCatching { json.decodeFromString(ListSerializer(StoredCookie.serializer()), it) }.getOrNull()
                }.orEmpty()
                synchronized(lock) {
                    if (!clearedBeforeLoad) {
                        val now = clock()
                        // Legacy session cookies have no timestamp: start their clock now.
                        cookies.addAll(0, loaded.map { if (!it.persistent && it.savedAt == 0L) it.copy(savedAt = now) else it })
                    }
                }
            } finally {
                loadedLatch.countDown()
            }
        }
    }

    /** Suspends until the initial load from the store has finished. */
    suspend fun awaitLoaded() {
        if (loadedLatch.count == 0L) return
        withContext(Dispatchers.IO) { loadedLatch.await() }
    }

    /** Suspends until the pending debounced write (if any) has hit the store. */
    suspend fun flush() {
        synchronized(lock) { writeJob }?.join()
    }

    // Only blocks until the first load completes; called from OkHttp/IO threads, never while holding [lock].
    private fun ensureLoaded() {
        if (loadedLatch.count != 0L) loadedLatch.await()
    }

    private fun schedulePersist(remove: Boolean = false) {
        // caller holds [lock]
        writeJob?.cancel()
        writeJob = scope.launch {
            if (remove) {
                store.remove(STORE_KEY)
            } else {
                delay(writeDebounceMs)
                val raw = synchronized(lock) {
                    json.encodeToString(ListSerializer(StoredCookie.serializer()), cookies.toList())
                }
                store.putString(STORE_KEY, raw)
            }
        }
    }

    private fun StoredCookie.isExpired(now: Long) =
        if (persistent) expiresAt <= now
        else savedAt > 0L && now - savedAt > sessionCookieMaxAgeMs

    private fun StoredCookie.toCookie(): Cookie = Cookie.Builder()
        .name(name).value(value).path(path)
        .apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            if (persistent) expiresAt(expiresAt)
            if (secure) secure()
            if (httpOnly) httpOnly()
        }.build()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        ensureLoaded()
        synchronized(lock) {
            val now = clock()
            for (c in cookies) {
                this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                if (c.persistent && c.expiresAt <= now) continue
                this.cookies.add(
                    StoredCookie(
                        c.name, c.value, c.domain, c.path, c.expiresAt, c.persistent, c.hostOnly, c.secure, c.httpOnly,
                        savedAt = if (c.persistent) 0L else now,
                    ),
                )
            }
            this.cookies.removeAll { it.isExpired(now) }
            schedulePersist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoaded()
        return synchronized(lock) {
            val now = clock()
            cookies.filter { !it.isExpired(now) }.map { it.toCookie() }.filter { it.matches(url) }
        }
    }

    /** All non-expired cookies whose domain applies to [host]. */
    fun cookiesForHost(host: String): List<Cookie> {
        ensureLoaded()
        return synchronized(lock) {
            val now = clock()
            cookies.filter { !it.isExpired(now) }
                .filter { if (it.hostOnly) it.domain == host else host == it.domain || host.endsWith("." + it.domain) }
                .map { it.toCookie() }
        }
    }

    fun allCookies(): List<Cookie> {
        ensureLoaded()
        return synchronized(lock) {
            val now = clock()
            cookies.filter { !it.isExpired(now) }.map { it.toCookie() }
        }
    }

    fun findCookie(host: String, namePrefix: String): Cookie? =
        cookiesForHost(host).firstOrNull { it.name.startsWith(namePrefix) }

    fun clear() = synchronized(lock) {
        clearedBeforeLoad = true
        cookies.clear()
        schedulePersist(remove = true)
    }

    companion object {
        private const val STORE_KEY = "cookies_v1"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
