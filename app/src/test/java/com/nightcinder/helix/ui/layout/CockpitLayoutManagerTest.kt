package com.nightcinder.helix.ui.layout

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitLayoutManagerTest {

    private class FakeCockpitLayoutStore(
        private var stored: CockpitLayoutMode = CockpitLayoutMode.PANELS,
        private val readGate: CompletableDeferred<Unit>? = null,
    ) : CockpitLayoutStore {
        var writes = mutableListOf<CockpitLayoutMode>()

        override suspend fun read(): CockpitLayoutMode {
            readGate?.await()
            return stored
        }

        override suspend fun write(mode: CockpitLayoutMode) {
            stored = mode
            writes += mode
        }
    }

    @Test
    fun defaultModeIsPanelsAndCorruptValuesFallBackToPanels() {
        assertEquals(CockpitLayoutMode.PANELS, DataStoreCockpitLayoutStore.parseCockpitLayoutMode(null))
        assertEquals(CockpitLayoutMode.PANELS, DataStoreCockpitLayoutStore.parseCockpitLayoutMode(""))
        assertEquals(CockpitLayoutMode.PANELS, DataStoreCockpitLayoutStore.parseCockpitLayoutMode("CORRUPT_MODE"))
        assertEquals(CockpitLayoutMode.TREE, DataStoreCockpitLayoutStore.parseCockpitLayoutMode("TREE"))
        assertEquals(CockpitLayoutMode.PANELS, DataStoreCockpitLayoutStore.parseCockpitLayoutMode("PANELS"))
    }

    @Test
    fun roundTripPersistsPanelsAndTree() = runTest {
        val store = FakeCockpitLayoutStore(stored = CockpitLayoutMode.TREE)
        val manager = CockpitLayoutManager(store, TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertEquals(CockpitLayoutMode.TREE, manager.mode.value)

        manager.setMode(CockpitLayoutMode.PANELS)
        advanceUntilIdle()
        assertEquals(CockpitLayoutMode.PANELS, manager.mode.value)
        assertEquals(listOf(CockpitLayoutMode.PANELS), store.writes)

        manager.setMode(CockpitLayoutMode.TREE)
        advanceUntilIdle()
        assertEquals(CockpitLayoutMode.TREE, manager.mode.value)
        assertEquals(listOf(CockpitLayoutMode.PANELS, CockpitLayoutMode.TREE), store.writes)
    }

    @Test
    fun explicitUserWriteBeforeInitialDiskReadWinsRace() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = FakeCockpitLayoutStore(stored = CockpitLayoutMode.PANELS, readGate = gate)
        val manager = CockpitLayoutManager(store, TestScope(StandardTestDispatcher(testScheduler)))

        // User switches to TREE before initial disk read finishes
        manager.setMode(CockpitLayoutMode.TREE)
        assertEquals(CockpitLayoutMode.TREE, manager.mode.value)

        // Initial disk read finishes returning PANELS; must NOT overwrite user's TREE choice
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(CockpitLayoutMode.TREE, manager.mode.value)
    }
}
