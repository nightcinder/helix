package com.nightcinder.helix.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveMetricsParserTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun parsesPluginSdkLiveSample() {
        val body = """
            {"cpu":33.5,"cores":[30,37],"memTotalKb":1000,"memUsedKb":620,"swapTotalKb":0,"swapUsedKb":0,
             "load":[4.39,3.1,2.0],"net":[{"name":"ens34","rxBps":1000,"txBps":500},{"name":"docker0","rxBps":24,"txBps":null}],
             "count":2,"running":1,"procs":[{"pid":7,"name":"java","cpu":12.0,"memKb":100,"state":"S","ppid":1}],
             "uptime":3600,"netTotals":{"rx":9,"tx":8}}
        """.trimIndent()
        val m = parseLiveHostMetrics(json, body)
        assertEquals(33.5, m.cpuPercent!!, 0.01)
        assertEquals(2, m.cpuCores)
        assertEquals(62.0, m.memoryPercent!!, 0.01)
        assertEquals(1024_000L, m.memoryTotalBytes)
        assertEquals(1024L, m.networkRxBytesPerSec)
        assertEquals(500L, m.networkTxBytesPerSec)
        assertEquals(listOf(4.39, 3.1, 2.0), m.loadAverage)
        assertEquals("java", m.topProcesses.single().name)
        assertEquals(10.0, m.topProcesses.single().memoryPercent!!, 0.01)
        assertEquals(3600L, m.uptimeSeconds)
        assertNull(m.diskPercent)
    }

    @Test
    fun unwrapsSampleEnvelopeAndKeepsFlatShapes() {
        val wrapped = parseLiveHostMetrics(json, """{"sample":{"cpu":5,"memTotalKb":200,"memUsedKb":50,"load":[1,1,1]}}""")
        assertEquals(25.0, wrapped.memoryPercent!!, 0.01)

        val flat = parseLiveHostMetrics(json, """{"hostId":42,"cpu":25.5,"memoryPercent":60.0}""")
        assertEquals(42L, flat.hostId)
        assertEquals(60.0, flat.memoryPercent!!, 0.01)
    }
}
