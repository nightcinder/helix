package com.nightcinder.helix.ui.tree

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.TermixHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeModelTest {
    init {
        TestSite.apply()
    }


    private val core = TermixHost(id = 1, name = "core", ip = "10.0.0.1")
    private val desktop = TermixHost(id = 2, name = "desktop", ip = "10.0.0.2")
    private val workstation = TermixHost(id = 3, name = "workstation", ip = "10.0.0.3", macAddress = "aa:bb")

    private fun c(id: String, name: String, stack: String?, state: String = "running") =
        DockerContainer(id = id, name = name, image = "img", state = state, stackName = stack)

    private val web = c("c1", "web", "media")
    private val db = c("c2", "db", "media")
    private val loose = c("c3", "loose", null, state = "exited")

    private val stacks = mapOf(1L to listOf(DockerStack(name = "media", serviceCount = 2, runningCount = 2, containers = listOf(web))))
    private val containers = mapOf(1L to listOf(web, db, loose))

    @Test
    fun collapsedHostsShowOnlyHostRows() {
        val rows = flattenTree(listOf(core, desktop), stacks, containers, emptySet())
        assertEquals(listOf("h:1", "h:2"), rows.map { it.node.key })
        assertTrue(rows.all { it.depth == 0 && it.expandable })
    }

    @Test
    fun expandedHostListsStacksThenStandaloneContainers() {
        val rows = flattenTree(listOf(core), stacks, containers, setOf("h:1"))
        assertEquals(listOf("h:1", "s:1:media", "c:1:c3"), rows.map { it.node.key })
        assertEquals(listOf(0, 1, 1), rows.map { it.depth })
    }

    @Test
    fun expandedStackMergesListedAndReportedContainersWithoutDuplicates() {
        val rows = flattenTree(listOf(core), stacks, containers, setOf("h:1", "s:1:media"))
        assertEquals(listOf("h:1", "s:1:media", "c:1:c1", "c:1:c2", "c:1:c3"), rows.map { it.node.key })
        val stack = rows[1].node as TreeNode.Stack
        assertEquals(2, stack.running)
        assertEquals(2, stack.total)
        assertEquals(2, rows[2].depth)
    }

    @Test
    fun containerWithUnknownStackFallsBackToHostLevel() {
        val orphan = c("c9", "orphan", "gone")
        val rows = flattenTree(listOf(core), emptyMap(), mapOf(1L to listOf(orphan)), setOf("h:1"))
        assertEquals(listOf("h:1", "c:1:c9"), rows.map { it.node.key })
    }

    @Test
    fun hostStatusIsResolvedByCallback() {
        val rows = flattenTree(listOf(core), emptyMap(), emptyMap(), emptySet()) { HostStatus.OFFLINE }
        assertEquals(HostStatus.OFFLINE, (rows.single().node as TreeNode.Host).status)
    }

    @Test
    fun hostActionsDependOnHost() {
        val desktopActions = actionsFor(TreeNode.Host(desktop, HostStatus.ONLINE))
        assertTrue(NodeAction.STREAM in desktopActions)
        assertFalse(NodeAction.WAKE in desktopActions)

        val coreActions = actionsFor(TreeNode.Host(core, HostStatus.ONLINE))
        // Stream is offered on every host; desktop is only highlighted.
        assertTrue(NodeAction.STREAM in coreActions)

        assertTrue(NodeAction.WAKE in actionsFor(TreeNode.Host(workstation, HostStatus.ONLINE)))
        assertTrue(NodeAction.WAKE in actionsFor(TreeNode.Host(core, HostStatus.SLEEPING)))
    }

    @Test
    fun containerActionsDependOnRunState() {
        val running = actionsFor(TreeNode.Container(1, "core", web))
        assertTrue(NodeAction.TERMINAL in running && NodeAction.RESTART in running && NodeAction.STOP in running)
        assertFalse(NodeAction.START in running)

        val stopped = actionsFor(TreeNode.Container(1, "core", loose))
        assertEquals(listOf(NodeAction.LOGS, NodeAction.START), stopped)
    }
}
