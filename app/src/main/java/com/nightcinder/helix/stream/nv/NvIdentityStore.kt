package com.nightcinder.helix.stream.nv

import com.nightcinder.helix.auth.CryptoStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Persists the client's GameStream identity (key, cert, unique id) and the pinned server
 * certificate per host through the Tink-backed [CryptoStore].
 */
class NvIdentityStore(private val store: CryptoStore) {
    private val lock = Mutex()

    @Volatile
    private var cached: ClientIdentity? = null

    suspend fun loadOrCreateIdentity(): ClientIdentity = lock.withLock {
        cached?.let { return it }
        val keyB64 = store.getString(KEY_PRIVATE)
        val certB64 = store.getString(KEY_CERT)
        val loaded = if (keyB64 != null && certB64 != null) {
            runCatching {
                ClientIdentity.fromEncoded(Base64.getDecoder().decode(keyB64), Base64.getDecoder().decode(certB64))
            }.getOrNull()
        } else {
            null
        }
        val identity = loaded ?: ClientIdentity.generate().also {
            store.putString(KEY_PRIVATE, Base64.getEncoder().encodeToString(it.privateKeyPkcs8()))
            store.putString(KEY_CERT, Base64.getEncoder().encodeToString(it.certificateDer()))
            // A new identity invalidates every earlier pairing.
            store.remove(KEY_UNIQUE_ID)
        }
        cached = identity
        identity
    }

    /** Fixed per install: 16 random hex characters. */
    suspend fun uniqueId(): String = lock.withLock {
        store.getString(KEY_UNIQUE_ID) ?: NvCrypto.bytesToHex(NvCrypto.randomBytes(8)).also {
            store.putString(KEY_UNIQUE_ID, it)
        }
    }

    suspend fun pinnedServerCert(host: String): X509Certificate? {
        val b64 = store.getString(serverCertKey(host)) ?: return null
        return runCatching { ClientIdentity.parseCertificate(Base64.getDecoder().decode(b64)) }.getOrNull()
    }

    suspend fun savePinnedServerCert(host: String, cert: X509Certificate) {
        store.putString(serverCertKey(host), Base64.getEncoder().encodeToString(cert.encoded))
    }

    suspend fun clearPairing(host: String) {
        store.remove(serverCertKey(host))
    }

    private fun serverCertKey(host: String) = "$KEY_SERVER_CERT_PREFIX$host"

    private companion object {
        const val KEY_PRIVATE = "nv.client.key"
        const val KEY_CERT = "nv.client.cert"
        const val KEY_UNIQUE_ID = "nv.client.uniqueid"
        const val KEY_SERVER_CERT_PREFIX = "nv.server.cert."
    }
}
