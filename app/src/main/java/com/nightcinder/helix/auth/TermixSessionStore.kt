package com.nightcinder.helix.auth

import com.nightcinder.helix.core.model.AuthTokens
import com.nightcinder.helix.core.model.AuthentikConfig
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/** Persists the Termix JWT and auth config via [CryptoStore], with a synchronous in-memory cache for interceptors. */
class TermixSessionStore(private val store: CryptoStore) {
    private val tokensRef = AtomicReference<AuthTokens?>(null)
    private val configRef = AtomicReference(AuthentikConfig())
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load() {
        store.getString(KEY_TOKENS)?.let { raw ->
            runCatching { json.decodeFromString(AuthTokens.serializer(), raw) }.onSuccess { tokensRef.set(it) }
        }
        store.getString(KEY_CONFIG)?.let { raw ->
            runCatching { json.decodeFromString(AuthentikConfig.serializer(), raw) }.onSuccess { configRef.set(it) }
        }
    }

    fun tokens(): AuthTokens? = tokensRef.get()
    fun config(): AuthentikConfig = configRef.get()

    /** Never returns static `tmx_` API keys. */
    fun jwt(): String? = tokens()?.takeIf { !it.isExpired() && !it.termixJwt.startsWith("tmx_") }?.termixJwt

    suspend fun saveTokens(tokens: AuthTokens) {
        tokensRef.set(tokens)
        store.putString(KEY_TOKENS, json.encodeToString(AuthTokens.serializer(), tokens))
    }

    suspend fun clearTokens() {
        tokensRef.set(null)
        store.remove(KEY_TOKENS)
    }

    suspend fun saveConfig(config: AuthentikConfig) {
        configRef.set(config)
        store.putString(KEY_CONFIG, json.encodeToString(AuthentikConfig.serializer(), config))
    }

    suspend fun getMeta(key: String): String? = store.getString("meta_$key")
    suspend fun putMeta(key: String, value: String) = store.putString("meta_$key", value)
    suspend fun removeMeta(key: String) = store.remove("meta_$key")

    private companion object {
        const val KEY_TOKENS = "termix_tokens_v1"
        const val KEY_CONFIG = "auth_config_v1"
    }
}
