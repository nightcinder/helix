package com.nightcinder.helix.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Encrypted key/value storage used for cookies, Termix JWT and Authentik session metadata. */
interface CryptoStore {
    suspend fun getString(key: String): String?
    suspend fun putString(key: String, value: String)
    suspend fun remove(key: String)
    suspend fun clear()
}

/** JVM/test fallback; never persists anything. */
class InMemoryCryptoStore : CryptoStore {
    private val map = ConcurrentHashMap<String, String>()
    override suspend fun getString(key: String): String? = map[key]
    override suspend fun putString(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
    override suspend fun clear() = map.clear()
}

private val Context.helixSecureDataStore by preferencesDataStore(name = "helix_secure_store")

/**
 * Android Keystore-backed master key (AndroidKeysetManager) + Tink AES-256-GCM AEAD encrypting values
 * stored in Preferences DataStore. The preference key name is bound as associated data.
 */
class TinkCryptoStore(context: Context) : CryptoStore {
    private val appContext = context.applicationContext
    private val dataStore get() = appContext.helixSecureDataStore

    private val aead: Aead by lazy {
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override suspend fun getString(key: String): String? {
        val raw = dataStore.data.first()[stringPreferencesKey(key)] ?: return null
        return runCatching {
            String(aead.decrypt(Base64.getDecoder().decode(raw), key.toByteArray()), Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun putString(key: String, value: String) {
        val enc = Base64.getEncoder().encodeToString(aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray()))
        dataStore.edit { it[stringPreferencesKey(key)] = enc }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        const val KEYSET_NAME = "helix_auth_keyset"
        const val KEYSET_PREFS = "helix_auth_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://helix_auth_master_key"
    }
}
