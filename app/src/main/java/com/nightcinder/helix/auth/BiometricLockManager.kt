package com.nightcinder.helix.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.biometricLockDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "biometric_lock_prefs",
)

data class BiometricLockPrefs(
    val enabled: Boolean = true,
    val timeoutMinutes: Int = 5,
)

data class BiometricLockState(
    val enabled: Boolean = true,
    val timeoutMinutes: Int = 5,
    val isLocked: Boolean = false,
    val lastBackgroundEpochMs: Long? = null,
    val lastError: String? = null,
)

interface BiometricLockStore {
    suspend fun read(): BiometricLockPrefs
    suspend fun write(prefs: BiometricLockPrefs)
}

class DataStoreBiometricLockStore(
    private val context: Context,
) : BiometricLockStore {
    override suspend fun read(): BiometricLockPrefs {
        val prefs = runCatching {
            context.biometricLockDataStore.data
                .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
                .first()
        }.getOrNull() ?: return BiometricLockPrefs()
        return BiometricLockPrefs(
            enabled = prefs[KEY_ENABLED] ?: true,
            timeoutMinutes = (prefs[KEY_TIMEOUT_MINUTES] ?: 5).coerceAtLeast(0),
        )
    }

    override suspend fun write(prefs: BiometricLockPrefs) {
        runCatching {
            context.biometricLockDataStore.edit { mutable ->
                mutable[KEY_ENABLED] = prefs.enabled
                mutable[KEY_TIMEOUT_MINUTES] = prefs.timeoutMinutes.coerceAtLeast(0)
            }
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        val KEY_TIMEOUT_MINUTES = intPreferencesKey("biometric_lock_timeout_minutes")
    }
}

/**
 * Coordinates app-level biometric / device-credential re-lock across [MainActivity] and
 * [DetachedPanelActivity]. Tracks foreground activity count so switching between multi-window
 * XR panels never triggers a background lock while any Helix window remains visible.
 */
class BiometricLockManager(
    private val store: BiometricLockStore,
    private val scope: CoroutineScope,
    private val deviceSupportsAuth: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(BiometricLockState())
    val state: StateFlow<BiometricLockState> = _state.asStateFlow()

    private val lock = Any()
    private var startedActivities = 0
    private var promptInFlight = false
    private var hasUserChangedEnabled = false
    private var hasUserChangedTimeout = false

    init {
        scope.launch {
            val loaded = store.read()
            synchronized(lock) {
                val cur = _state.value
                val resolvedEnabled = if (hasUserChangedEnabled) cur.enabled else loaded.enabled
                val resolvedTimeout = if (hasUserChangedTimeout) cur.timeoutMinutes else loaded.timeoutMinutes
                _state.value = cur.copy(
                    enabled = resolvedEnabled,
                    timeoutMinutes = resolvedTimeout,
                    isLocked = cur.isLocked && resolvedEnabled,
                )
            }
        }
    }

    fun onActivityStarted(isAuthenticated: Boolean) {
        synchronized(lock) {
            startedActivities += 1
            val current = _state.value
            if (!isAuthenticated || !current.enabled || !deviceSupportsAuth()) {
                if (current.isLocked && (!isAuthenticated || !current.enabled)) {
                    _state.value = current.copy(isLocked = false, lastBackgroundEpochMs = null, lastError = null)
                }
                return
            }
            if (current.isLocked || promptInFlight) {
                return
            }
            val bgTime = current.lastBackgroundEpochMs ?: return
            val elapsedMs = (clock() - bgTime).coerceAtLeast(0L)
            val thresholdMs = current.timeoutMinutes.toLong().coerceAtLeast(0L) * 60_000L
            if (elapsedMs >= thresholdMs) {
                _state.value = current.copy(isLocked = true, lastError = null)
            } else {
                _state.value = current.copy(lastBackgroundEpochMs = null)
            }
        }
    }

    fun onActivityStopped(isAuthenticated: Boolean) {
        synchronized(lock) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities > 0) return
            val current = _state.value
            if (isAuthenticated && current.enabled && !current.isLocked && !promptInFlight) {
                _state.value = current.copy(lastBackgroundEpochMs = clock())
            }
        }
    }

    fun lockNow(isAuthenticated: Boolean) {
        synchronized(lock) {
            if (isAuthenticated && deviceSupportsAuth()) {
                _state.update { it.copy(isLocked = true, lastError = null) }
            }
        }
    }

    fun setPromptInFlight(inFlight: Boolean) {
        synchronized(lock) {
            promptInFlight = inFlight
        }
    }

    fun onAuthenticated() {
        synchronized(lock) {
            promptInFlight = false
            _state.update {
                it.copy(
                    isLocked = false,
                    lastBackgroundEpochMs = null,
                    lastError = null,
                )
            }
        }
    }

    fun onAuthError(message: String) {
        synchronized(lock) {
            promptInFlight = false
            _state.update {
                it.copy(lastError = message.takeIf { msg -> msg.isNotBlank() })
            }
        }
    }

    fun onSignOut() {
        synchronized(lock) {
            promptInFlight = false
            _state.update {
                it.copy(
                    isLocked = false,
                    lastBackgroundEpochMs = null,
                    lastError = null,
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val snapshotToPersist: BiometricLockPrefs
        synchronized(lock) {
            hasUserChangedEnabled = true
            _state.update {
                it.copy(
                    enabled = enabled,
                    isLocked = if (!enabled) false else it.isLocked,
                    lastBackgroundEpochMs = if (!enabled) null else it.lastBackgroundEpochMs,
                    lastError = if (!enabled) null else it.lastError,
                )
            }
            snapshotToPersist = BiometricLockPrefs(
                enabled = enabled,
                timeoutMinutes = _state.value.timeoutMinutes,
            )
        }
        scope.launch { store.write(snapshotToPersist) }
    }

    fun setTimeoutMinutes(minutes: Int) {
        val normalized = minutes.coerceAtLeast(0)
        val snapshotToPersist: BiometricLockPrefs
        synchronized(lock) {
            hasUserChangedTimeout = true
            _state.update { it.copy(timeoutMinutes = normalized) }
            snapshotToPersist = BiometricLockPrefs(
                enabled = _state.value.enabled,
                timeoutMinutes = normalized,
            )
        }
        scope.launch { store.write(snapshotToPersist) }
    }
}

private const val ALLOWED_AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

fun canDeviceAuthenticate(context: Context): Boolean = runCatching {
    BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}.getOrDefault(false)

fun launchBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                // System prompt keeps retry open; surface subtle hint without dismissing lock.
                onError("Authentication not recognized. Try again.")
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Project Helix")
        .setSubtitle("Authenticate to access homelab sessions")
        .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
        .build()
    runCatching { prompt.authenticate(info) }
        .onFailure { onError(it.message ?: "Unable to open biometric prompt") }
}
