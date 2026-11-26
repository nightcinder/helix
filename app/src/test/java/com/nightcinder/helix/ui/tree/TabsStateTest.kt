package com.nightcinder.helix.ui.tree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TabsStateTest {

    private val files = contentTab(TabKind.FILES, hostId = 1, hostName = "core")
    private val metrics = contentTab(TabKind.METRICS, hostId = 1, hostName = "core")
    private val logs = contentTab(TabKind.LOGS, hostId = 1, hostName = "core", containerId = "abc", containerName = "web")

    @Test
    fun openAddsAndActivates() {
        val s = TabsState().open(files).open(metrics)
        assertEquals(listOf(files.id, metrics.id), s.tabs.map { it.id })
        assertEquals(metrics.id, s.activeId)
    }

    @Test
    fun reopeningSameNodeViewSelectsExistingTab() {
        val s = TabsState().open(files).open(metrics).open(files)
        assertEquals(2, s.tabs.size)
        assertEquals(files.id, s.activeId)
    }

    @Test
    fun terminalTabsAreNotDeduplicated() {
        val a = contentTab(TabKind.TERMINAL, 1, "core", terminalTabId = "t1")
        val b = contentTab(TabKind.TERMINAL, 1, "core", terminalTabId = "t2")
        assertEquals(2, TabsState().open(a).open(b).tabs.size)
    }

    @Test
    fun closingActiveTabPicksRightNeighbourThenLeft() {
        val s = TabsState().open(files).open(metrics).open(logs).select(metrics.id)
        assertEquals(logs.id, s.close(metrics.id).activeId)
        assertEquals(metrics.id, s.select(logs.id).close(logs.id).activeId)
    }

    @Test
    fun closingInactiveTabKeepsActive() {
        val s = TabsState().open(files).open(metrics).open(logs)
        val closed = s.close(files.id)
        assertEquals(logs.id, closed.activeId)
        assertEquals(2, closed.tabs.size)
    }

    @Test
    fun closingLastTabClearsActive() {
        val s = TabsState().open(files).close(files.id)
        assertNull(s.activeId)
        assertNull(s.active)
    }

    @Test
    fun unknownIdsAreNoOps() {
        val s = TabsState().open(files)
        assertSame(s, s.select("nope"))
        assertSame(s, s.close("nope"))
    }

    @Test
    fun titlesNameTheSubject() {
        assertEquals("Files · core", files.title)
        assertEquals("Logs · web", logs.title)
        assertEquals("Alerts", contentTab(TabKind.ALERTS).title)
        assertEquals("alerts", contentTab(TabKind.ALERTS).id)
    }
}
