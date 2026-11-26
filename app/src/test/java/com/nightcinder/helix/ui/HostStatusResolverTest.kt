package com.nightcinder.helix.ui

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.model.HealthSummary
import com.nightcinder.helix.core.model.HostHealthItem
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.TermixHost
import org.junit.Assert.assertEquals
import org.junit.Test

class HostStatusResolverTest {

    init {
        TestSite.apply()
    }

    private fun host(id: Long, name: String, status: HostStatus) =
        TermixHost(id = id, name = name, ip = "192.0.2.$id", status = status)

    private val upByKey = mapOf(
        "core" to true,
        "desktop" to true,
        "server1" to true,
        "workstation" to false,
    )

    @Test
    fun nonUnknownHealthStatusWins() {
        val core = host(1, "core", HostStatus.ONLINE)
        val health = HealthSummary(hosts = listOf(HostHealthItem(hostId = 1L, hostName = "core", healthy = false, status = "warn")))

        assertEquals(HostStatus.DEGRADED, resolveHostStatus(core, health, upByKey))
        assertEquals(HostStatus.DEGRADED, resolveHostStatus(core, health))
    }

    @Test
    fun unknownHealthItemDoesNotMaskHostStatus() {
        val desktop = host(2, "desktop", HostStatus.ONLINE)
        val workstation = host(4, "workstation", HostStatus.OFFLINE)
        val health = HealthSummary(
            hosts = listOf(
                HostHealthItem(hostId = 2L, status = null, healthy = false),
                HostHealthItem(hostId = 4L, status = "unknown", healthy = false),
            ),
        )

        assertEquals(HostStatus.ONLINE, resolveHostStatus(desktop, health))
        assertEquals(HostStatus.OFFLINE, resolveHostStatus(workstation, health))
        assertEquals(HostStatus.ONLINE, resolveHostStatus(desktop, health, upByKey))
    }

    @Test
    fun unknownHostUsesReachability() {
        val server1 = host(3, "server1", HostStatus.UNKNOWN)
        val workstation = host(4, "workstation", HostStatus.UNKNOWN)
        val health = HealthSummary(hosts = listOf(HostHealthItem(hostId = 3L, status = null, healthy = false)))

        assertEquals(HostStatus.ONLINE, resolveHostStatus(server1, health, upByKey))
        assertEquals(HostStatus.OFFLINE, resolveHostStatus(workstation, health, upByKey))
    }

    @Test
    fun offlineHostThatIsUpBecomesOnline() {
        val server1 = host(3, "server1", HostStatus.OFFLINE)

        assertEquals(HostStatus.ONLINE, resolveHostStatus(server1, null, upByKey))
    }

    @Test
    fun onlineHostReportedDownKeepsHostStatus() {
        val workstation = host(4, "workstation", HostStatus.ONLINE)

        assertEquals(HostStatus.ONLINE, resolveHostStatus(workstation, null, upByKey))
    }

    @Test
    fun noDataFallsBackToHostStatus() {
        val unknown = host(5, "mystery", HostStatus.UNKNOWN)
        val sleeping = host(6, "sleepy", HostStatus.SLEEPING)

        assertEquals(HostStatus.UNKNOWN, resolveHostStatus(unknown, null))
        assertEquals(HostStatus.UNKNOWN, resolveHostStatus(unknown, HealthSummary(), upByKey))
        assertEquals(HostStatus.SLEEPING, resolveHostStatus(sleeping, null, emptyMap()))
    }
}
