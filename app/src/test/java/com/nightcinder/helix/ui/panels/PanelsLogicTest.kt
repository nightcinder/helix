package com.nightcinder.helix.ui.panels

import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.HostHealthItem
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.TermixHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkLocationClassifierTest {

    private fun classifierFor(vararg addresses: String) = NetworkLocationClassifier(
        host = "termix.example.com",
        resolver = { addresses.toList() },
        dispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun lanAddressIsLan() = runBlocking {
        assertEquals(NetworkLocation.LAN, classifierFor("10.0.0.50").check())
    }

    @Test
    fun publicAddressIsWan() = runBlocking {
        assertEquals(NetworkLocation.WAN, classifierFor("203.0.113.9").check())
    }

    @Test
    fun anyLanAddressAmongManyWins() {
        val c = classifierFor()
        assertEquals(NetworkLocation.LAN, c.classify(listOf("203.0.113.9", "192.168.1.7")))
    }

    @Test
    fun emptyResultIsWan() = runBlocking {
        assertEquals(NetworkLocation.WAN, classifierFor().check())
    }

    @Test
    fun resolverFailureIsWan() = runBlocking {
        val c = NetworkLocationClassifier(
            host = "termix.example.com",
            resolver = { throw java.net.UnknownHostException("offline") },
            dispatcher = Dispatchers.Unconfined,
        )
        assertEquals(NetworkLocation.WAN, c.check())
    }

    @Test
    fun unconfiguredHostIsWanWithoutLookup() = runBlocking {
        var asked = false
        val c = NetworkLocationClassifier(
            host = "",
            resolver = { asked = true; listOf("10.0.0.1") },
            dispatcher = Dispatchers.Unconfined,
        )
        assertEquals(NetworkLocation.WAN, c.check())
        assertFalse(asked)
    }

    @Test
    fun resolvesConfiguredHost() = runBlocking {
        var asked: String? = null
        val c = NetworkLocationClassifier(
            host = "termix.example.com",
            resolver = { asked = it; listOf("192.0.2.1") },
            dispatcher = Dispatchers.Unconfined,
        )
        c.check()
        assertEquals("termix.example.com", asked)
    }

    @Test
    fun onlyPrivateRangesCount() {
        assertTrue(NetworkLocationClassifier.isLanAddress("10.0.0.0"))
        assertTrue(NetworkLocationClassifier.isLanAddress("10.255.255.255"))
        assertTrue(NetworkLocationClassifier.isLanAddress("172.16.0.1"))
        assertTrue(NetworkLocationClassifier.isLanAddress("172.31.255.1"))
        assertTrue(NetworkLocationClassifier.isLanAddress("192.168.1.5"))
        assertFalse(NetworkLocationClassifier.isLanAddress("172.15.0.1"))
        assertFalse(NetworkLocationClassifier.isLanAddress("172.32.0.1"))
        assertFalse(NetworkLocationClassifier.isLanAddress("192.0.2.5"))
        assertFalse(NetworkLocationClassifier.isLanAddress("100.64.0.1"))
        assertFalse(NetworkLocationClassifier.isLanAddress("10.0.0.256"))
        assertFalse(NetworkLocationClassifier.isLanAddress("10.0.0"))
        assertFalse(NetworkLocationClassifier.isLanAddress("fe80::1"))
        assertFalse(NetworkLocationClassifier.isLanAddress(""))
    }
}

class PanelNavStateTest {

    @Test
    fun defaultsToStreamAndWorkspaceTab() {
        val s = PanelNavState()
        assertEquals(CockpitArea.STREAM, s.area)
        assertEquals(RightTab.WORKSPACE, s.rightTab)
    }

    @Test
    fun selectingRightTabSwitchesAreaToWorkspace() {
        val s = PanelNavState().selectRightTab(RightTab.METRICS)
        assertEquals(RightTab.METRICS, s.rightTab)
        assertEquals(CockpitArea.WORKSPACE, s.area)
    }

    @Test
    fun showTerminalKeepsTabSelections() {
        val s = PanelNavState().selectRightTab(RightTab.ALERTS).showTerminal()
        assertEquals(CockpitArea.TERMINAL, s.area)
        assertEquals(RightTab.ALERTS, s.rightTab)
    }

    @Test
    fun splitAreaIsTerminalAndWorkspaceOnly() {
        assertFalse(PanelNavState(area = CockpitArea.STREAM).isSplitArea)
        assertTrue(PanelNavState(area = CockpitArea.TERMINAL).isSplitArea)
        assertTrue(PanelNavState(area = CockpitArea.WORKSPACE).isSplitArea)
    }

    @Test
    fun saveableRoundTrips() {
        val s = PanelNavState(CockpitArea.WORKSPACE, RightTab.METRICS, LeftTab.SNIPPETS)
        assertEquals(s, PanelNavState.fromSaveable(s.toSaveable()))
        assertNull(PanelNavState.fromSaveable(listOf(9, 0, 0)))
        assertNull(PanelNavState.fromSaveable(listOf(0, 0, 9)))
        assertNull(PanelNavState.fromSaveable(listOf(0)))
        assertNull(PanelNavState.fromSaveable(listOf(0, 0, 0, 0)))
    }

    @Test
    fun selectingLeftTabBringsTerminalToFront() {
        val s = PanelNavState().selectLeftTab(LeftTab.SNIPPETS)
        assertEquals(LeftTab.SNIPPETS, s.leftTab)
        assertEquals(CockpitArea.TERMINAL, s.area)
    }

    @Test
    fun showTerminalReturnsToTerminalTab() {
        val s = PanelNavState().selectLeftTab(LeftTab.SNIPPETS).showTerminal()
        assertEquals(LeftTab.TERMINAL, s.leftTab)
    }

    @Test
    fun navStateSurvivesSaveRestoreAcrossSpaceSwitch() {
        // Hoisted state is saved/restored as one unit when the host swaps Home Space and Full Space.
        val s = PanelNavState().selectRightTab(RightTab.METRICS).selectArea(CockpitArea.STREAM)
        assertEquals(s, PanelNavState.fromSaveable(s.toSaveable()))
    }

    @Test
    fun detachablePanelsAreTerminalWorkspaceStream() {
        assertEquals(listOf("terminal", "workspace", "stream"), PanelNavState.DETACHABLE_PANELS.map { it.first })
    }

    @Test
    fun workspaceFollowsDashboardHostSelection() {
        assertEquals(7L, PanelNavState.workspaceHostToSync(dashboardSelectedId = 7L, workspaceSelectedId = 3L))
        assertEquals(7L, PanelNavState.workspaceHostToSync(dashboardSelectedId = 7L, workspaceSelectedId = null))
        assertNull(PanelNavState.workspaceHostToSync(dashboardSelectedId = 7L, workspaceSelectedId = 7L))
        assertNull(PanelNavState.workspaceHostToSync(dashboardSelectedId = null, workspaceSelectedId = 3L))
    }

    @Test
    fun snippetsRunOnActiveTabHost() {
        assertEquals(5L, PanelNavState.snippetTargetHostId(activeTabHostId = 5L, selectedHostId = 9L))
        assertEquals(9L, PanelNavState.snippetTargetHostId(activeTabHostId = null, selectedHostId = 9L))
        assertNull(PanelNavState.snippetTargetHostId(activeTabHostId = null, selectedHostId = null))
    }

    @Test
    fun panelIdMapsToArea() {
        assertEquals(CockpitArea.TERMINAL, PanelNavState.areaForPanelId("terminal"))
        assertEquals(CockpitArea.WORKSPACE, PanelNavState.areaForPanelId("workspace"))
        assertEquals(CockpitArea.STREAM, PanelNavState.areaForPanelId(null))
    }

    @Test
    fun showAlertsSwitchesToWorkspaceAlertsAndAlertTapShowsTerminal() {
        val initial = PanelNavState(area = CockpitArea.STREAM, leftTab = LeftTab.SNIPPETS)
        val onAlerts = initial.showAlerts()
        assertEquals(CockpitArea.WORKSPACE, onAlerts.area)
        assertEquals(RightTab.ALERTS, onAlerts.rightTab)

        val afterAlertTap = onAlerts.showTerminal()
        assertEquals(CockpitArea.TERMINAL, afterAlertTap.area)
        assertEquals(LeftTab.TERMINAL, afterAlertTap.leftTab)
        assertEquals(RightTab.ALERTS, afterAlertTap.rightTab)
    }

    @Test
    fun resolveDisplayedHostStatusIgnoresUnknownHealthSummaryItems() {
        val desktop = TermixHost(id = 2L, name = "desktop", status = HostStatus.ONLINE)
        val workstation = TermixHost(id = 4L, name = "workstation", status = HostStatus.OFFLINE)
        val core = TermixHost(id = 1L, name = "core", status = HostStatus.ONLINE)

        val summary = HealthSummary(
            hosts = listOf(
                // Termix health check absent/unknown for Windows/WOL hosts must NOT revert status to grey UNKNOWN
                HostHealthItem(hostId = 2L, status = null, healthy = false),
                HostHealthItem(hostId = 4L, status = "unknown", healthy = false),
                // Explicit degraded check on core still surfaces DEGRADED
                HostHealthItem(hostId = 1L, status = "warn", healthy = false),
            ),
        )

        assertEquals(HostStatus.ONLINE, resolveDisplayedHostStatus(desktop, summary))
        assertEquals(HostStatus.OFFLINE, resolveDisplayedHostStatus(workstation, summary))
        assertEquals(HostStatus.DEGRADED, resolveDisplayedHostStatus(core, summary))
        assertEquals(HostStatus.ONLINE, resolveDisplayedHostStatus(desktop, null))
    }
}
