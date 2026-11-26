package com.nightcinder.helix.ui.layout

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.cockpitLayoutDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cockpit_layout_prefs",
)

enum class CockpitLayoutMode {
    PANELS,
    TREE,
}

interface CockpitLayoutStore {
    suspend fun read(): CockpitLayoutMode
    suspend fun write(mode: CockpitLayoutMode)
}

class DataStoreCockpitLayoutStore(
    private val context: Context,
) : CockpitLayoutStore {
    override suspend fun read(): CockpitLayoutMode {
        val prefs = runCatching {
            context.cockpitLayoutDataStore.data
                .catch { emit(emptyPreferences()) }
                .first()
        }.getOrNull() ?: return CockpitLayoutMode.PANELS
        return parseCockpitLayoutMode(prefs[KEY_LAYOUT_MODE])
    }

    override suspend fun write(mode: CockpitLayoutMode) {
        runCatching {
            context.cockpitLayoutDataStore.edit { mutable ->
                mutable[KEY_LAYOUT_MODE] = mode.name
            }
        }
    }

    companion object {
        val KEY_LAYOUT_MODE = stringPreferencesKey("cockpit_layout_mode")

        fun parseCockpitLayoutMode(raw: String?): CockpitLayoutMode =
            CockpitLayoutMode.entries.firstOrNull { it.name == raw } ?: CockpitLayoutMode.PANELS
    }
}

/**
 * Coordinates the runtime [CockpitLayoutMode] preference across [MainActivity],
 * [DetachedPanelActivity], and [AuthScreen] without tearing down active sessions.
 */
class CockpitLayoutManager(
    private val store: CockpitLayoutStore,
    private val scope: CoroutineScope,
) {
    private val _mode = MutableStateFlow(CockpitLayoutMode.PANELS)
    val mode: StateFlow<CockpitLayoutMode> = _mode.asStateFlow()

    private val lock = Any()
    private var hasUserChanged = false

    init {
        scope.launch {
            val loaded = runCatching { store.read() }.getOrDefault(CockpitLayoutMode.PANELS)
            synchronized(lock) {
                if (!hasUserChanged) {
                    _mode.value = loaded
                }
            }
        }
    }

    fun setMode(mode: CockpitLayoutMode) {
        synchronized(lock) {
            hasUserChanged = true
            _mode.value = mode
        }
        scope.launch {
            runCatching { store.write(mode) }
        }
    }
}

/** Provides the active [CockpitLayoutManager] to nested Auth/Session screens across all layouts. */
val LocalCockpitLayoutManager = staticCompositionLocalOf<CockpitLayoutManager?> { null }
