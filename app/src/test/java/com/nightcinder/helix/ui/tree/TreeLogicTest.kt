package com.nightcinder.helix.ui.tree

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.HostHealthItem
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.TerminalTabState
import com.nightcinder.helix.core.model.TerminalTarget
import com.nightcinder.helix.core.model.TermixHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeLogicTest {
    init {
        TestSite.apply()
    }


    private val core = TermixHost(id = 1, name = "core", ip = "10.0.0.1", tags = listOf("core"), os = "Debian")
    private val desktop = TermixHost(id = 2, name = "desktop", ip = "10.0.0.2")
    private val stack = TreeNode.Stack(1, "core", "media", running = 1, total = 2)
    private val container = TreeNode.Container(
        1, "core",
        DockerContainer(id = "c1", name = "web", image = "img", state = "running", stackName = "media"),
    )

    private fun term(id: String, host: Long = 1, name: String = "core") =
        TerminalTabState(id = id, title = name, target = TerminalTarget.SshHost(hostId = host, hostName = name))

    // --- stack actions / confirmation -----------------------------------------------------------

    @Test
    fun stackOffersUpAndStopWithConfirmation() {
        val actions = actionsFor(stack)
        assertTrue(NodeAction.UP in actions)
        assertTrue(NodeAction.STOP in actions)
        assertTrue(needsConfirmation(stack, NodeAction.STOP))
        assertTrue(needsConfirmation(container, NodeAction.STOP))
        assertFalse(needsConfirmation(stack, NodeAction.UP))
        assertFalse(needsConfirmation(stack, NodeAction.RESTART))
        assertEquals("Down (stop stack)", actionLabel(stack, NodeAction.STOP))
        assertTrue(confirmationMessage(stack).contains("compose down"))
    }

    @Test
    fun streamOfferedOnEveryHostAndDesktopRecommended() {
        assertTrue(NodeAction.STREAM in actionsFor(TreeNode.Host(core, HostStatus.ONLINE)))
        assertEquals("Stream desktop", actionLabel(TreeNode.Host(core, HostStatus.ONLINE), NodeAction.STREAM))
        assertEquals(
            "Stream desktop (recommended)",
            actionLabel(TreeNode.Host(desktop, HostStatus.ONLINE), NodeAction.STREAM),
        )
    }

    // --- host filter ----------------------------------------------------------------------------

    @Test
    fun filterMatchesNameAddressTagsAndOs() {
        val hosts = listOf(core, desktop)
        fun keys(q: String) = flattenTree(hosts, emptyMap(), emptyMap(), emptySet(), query = q).map { it.node.key }
        assertEquals(listOf("h:1", "h:2"), keys(""))
        assertEquals(listOf("h:1"), keys("COR"))
        assertEquals(listOf("h:2"), keys("10.0.0.2"))
        assertEquals(listOf("h:1"), keys("core"))
        assertEquals(listOf("h:1"), keys("debian"))
        assertEquals(emptyList<String>(), keys("nothing"))
    }

    @Test
    fun filterMatchesLoadedContainerNames() {
        val c = DockerContainer(id = "c9", name = "plex", image = "i", state = "running")
        val rows = flattenTree(
            listOf(core, desktop), emptyMap(), mapOf(2L to listOf(c)), emptySet(), query = "plex",
        )
        assertEquals(listOf("h:2"), rows.map { it.node.key })
        assertTrue(hostMatches(desktop, "plex", emptyList<DockerStack>(), listOf(c)))
    }

    // --- terminal tab sync ----------------------------------------------------------------------

    @Test
    fun externalTerminalsAppearAsTabsWithoutStealingSelection() {
        val start = TabsState().open(contentTab(TabKind.METRICS, 1, "core"))
        val (tabs, seen) = syncTerminalTabs(start, listOf(term("a"), term("b")), emptySet())
        assertEquals(listOf("metrics:1:-", "term:a", "term:b"), tabs.tabs.map { it.id })
        assertEquals("metrics:1:-", tabs.activeId)
        assertEquals(setOf("a", "b"), seen)
    }

    @Test
    fun firstExternalTerminalBecomesActiveWhenNothingIsOpen() {
        val (tabs, _) = syncTerminalTabs(TabsState(), listOf(term("a")), emptySet())
        assertEquals("term:a", tabs.activeId)
    }

    @Test
    fun terminalClosedElsewhereDropsItsTabButUnseenOnesSurvive() {
        val opened = TabsState()
            .open(contentTab(TabKind.TERMINAL, 1, "core", terminalTabId = "a"))
            .open(contentTab(TabKind.TERMINAL, 1, "core", terminalTabId = "fresh"))
        // "a" was seen before and is now gone; "fresh" was just opened in the tree and not listed yet.
        val (tabs, _) = syncTerminalTabs(opened, emptyList(), seen = setOf("a"))
        assertEquals(listOf("term:fresh"), tabs.tabs.map { it.id })
    }

    @Test
    fun syncIsIdempotentAndDockerConsoleGetsContainerTitle() {
        val docker = TerminalTabState(
            id = "d",
            title = "web@core",
            target = TerminalTarget.DockerConsole(hostId = 1, hostName = "core", containerId = "c1", containerName = "web"),
        )
        val (once, seen) = syncTerminalTabs(TabsState(), listOf(docker), emptySet())
        val (twice, _) = syncTerminalTabs(once, listOf(docker), seen)
        assertEquals(once, twice)
        assertEquals("Terminal · web", once.tabs.single().title)
    }

    @Test
    fun snippetsIsGlobalSingleTab() {
        assertTrue(TabKind.SNIPPETS.isGlobal)
        assertEquals("snippets", contentTab(TabKind.SNIPPETS).id)
    }

    // --- banner events --------------------------------------------------------------------------

    private data class Result(val text: String)

    @Test
    fun identicalRepeatedResultGetsNewEventId() {
        val tracker = BannerTracker<Result>()
        val first = Result("ok")
        val e1 = tracker.observe(first)!!
        assertSame(e1, tracker.observe(first))
        // Equal but distinct object: a new run of the same snippet.
        val e2 = tracker.observe(Result("ok"))!!
        assertNotEquals(e1.id, e2.id)
        assertEquals(e1.value, e2.value)
    }

    @Test
    fun clearedResultResetsTracker() {
        val tracker = BannerTracker<Result>()
        val e1 = tracker.observe(Result("x"))!!
        assertNull(tracker.observe(null))
        assertNotEquals(e1.id, tracker.observe(Result("x"))!!.id)
    }

    // --- error dedupe ---------------------------------------------------------------------------

    @Test
    fun wrappedAndRepeatedErrorsAreShownOnce() {
        assertEquals("boom", mergeMessage(null, "boom"))
        assertEquals("boom", mergeMessage("boom", "boom"))
        // generic repo error first, contextual wrapper second: contextual wins
        assertEquals("restart web failed: boom", mergeMessage("boom", "restart web failed: boom"))
        // contextual first, generic second: contextual kept
        assertEquals("restart web failed: boom", mergeMessage("restart web failed: boom", "boom"))
        // unrelated message replaces
        assertEquals("other", mergeMessage("boom", "other"))
    }

    // --- host status resolution (grey status dots fix) ------------------------------------------

    @Test
    fun resolveHostStatus_ignoresUnknownHealthItemAndUsesMonitoringReachability() {
        val desktopMerged = TermixHost(id = 2L, name = "desktop", ip = "192.0.2.20", status = HostStatus.ONLINE)
        val r440Unknown = TermixHost(id = 3L, name = "server1", ip = "192.0.2.30", status = HostStatus.UNKNOWN)
        val workstationUnknown = TermixHost(id = 4L, name = "workstation", ip = "192.0.2.40", status = HostStatus.UNKNOWN)

        // Termix health summary returns an UNKNOWN item (level = null, healthy = false) for desktop & server1
        val healthWithUnknowns = HealthSummary(
            hosts = listOf(
                HostHealthItem(hostId = 1L, hostName = "core", healthy = false, status = "warn"),
                HostHealthItem(hostId = 2L, hostName = "desktop", healthy = false, status = null),
                HostHealthItem(hostId = 3L, hostName = "server1", healthy = false, status = null),
            ),
        )
        val hostUpByKey = mapOf(
            "core" to true,
            "desktop" to true,
            "server1" to true,
            "workstation" to false,
        )

        // Authoritative DEGRADED ("warn") from Termix health wins for core
        assertEquals(
            HostStatus.DEGRADED,
            resolveHostStatus(core.copy(status = HostStatus.ONLINE), healthWithUnknowns, hostUpByKey),
        )
        // UNKNOWN health item does NOT mask desktop's merged ONLINE status
        assertEquals(HostStatus.ONLINE, resolveHostStatus(desktopMerged, healthWithUnknowns, hostUpByKey))
        // UNKNOWN host status falls back to hostUpByKey for server1 (ONLINE) and workstation (OFFLINE)
        assertEquals(HostStatus.ONLINE, resolveHostStatus(r440Unknown, healthWithUnknowns, hostUpByKey))
        assertEquals(HostStatus.OFFLINE, resolveHostStatus(workstationUnknown, healthWithUnknowns, hostUpByKey))
    }
}
