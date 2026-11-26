package com.nightcinder.helix

import android.app.Application
import com.google.crypto.tink.aead.AeadConfig
import java.security.GeneralSecurityException

/**
 * Lightweight dependency registry hook so [HelixApp] remains decoupled from concrete DI wiring.
 */
interface ContainerProvider {
    fun <T : Any> getOrCreateContainer(factory: (Application) -> T): T
}

/**
 * Application entry point for Project Helix.
 *
 * Registers Tink AEAD primitives eagerly while keeping DI container instantiation lazy and decoupled
 * from `AppContainer`.
 */
class HelixApp : Application(), ContainerProvider {

    @Volatile
    private var containerInstance: Any? = null

    override fun onCreate() {
        super.onCreate()
        initializeCryptoPrimitives()
    }

    override fun <T : Any> getOrCreateContainer(factory: (Application) -> T): T {
        val existing = containerInstance
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return existing as T
        }
        return synchronized(this) {
            val cached = containerInstance
            if (cached != null) {
                @Suppress("UNCHECKED_CAST")
                cached as T
            } else {
                factory(this).also { containerInstance = it }
            }
        }
    }

    private fun initializeCryptoPrimitives() {
        try {
            AeadConfig.register()
        } catch (_: GeneralSecurityException) {
            // Defer surfacing provider issues until TinkCryptoStore opens the Android Keystore keyset.
        }
    }
}
