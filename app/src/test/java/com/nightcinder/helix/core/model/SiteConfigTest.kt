package com.nightcinder.helix.core.model

import com.nightcinder.helix.ui.panels.NetworkLocationClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAliasTableTest {

    @Test
    fun parsesHostsAliasesAndOrder() {
        val t = HostAliasTable.parse(" Server = docker-host, 10.0.0.10 ;desktop=sunshine;; nas= ")
        assertEquals(listOf("server", "desktop", "nas"), t.canonicalHosts.toList())
        assertEquals(listOf("docker-host", "10.0.0.10"), t.aliasesByHost["server"])
        assertEquals(listOf("sunshine"), t.aliasesByHost["desktop"])
        assertEquals(emptyList<String>(), t.aliasesByHost["nas"])
        assertEquals("server", t.fallbackHost)
    }

    @Test
    fun mergesRepeatedHostsDropsSelfAliasAndDuplicates() {
        val t = HostAliasTable.parse("a=x,a,x;a=y")
        assertEquals(listOf("x", "y"), t.aliasesByHost["a"])
    }

    @Test
    fun blankSpecIsEmpty() {
        val t = HostAliasTable.parse("")
        assertTrue(t.canonicalHosts.isEmpty())
        assertNull(t.fallbackHost)
        assertEquals(HostAliasTable.EMPTY, t)
    }
}

class StreamConfigWsUrlTest {

    @Test
    fun httpsBecomesWssWithStreamPath() {
        assertEquals(
            "wss://m.example.com/api/host/stream/web_socket",
            StreamConfig.wsUrlFor("https://m.example.com"),
        )
    }

    @Test
    fun httpBecomesWsAndTrailingSlashIsDropped() {
        assertEquals(
            "ws://m.example.com:8080/api/host/stream/web_socket",
            StreamConfig.wsUrlFor(" HTTP://m.example.com:8080/ "),
        )
    }

    @Test
    fun blankInBlankOut() {
        assertEquals("", StreamConfig.wsUrlFor(""))
        assertEquals("", StreamConfig.wsUrlFor("   "))
    }
}

class NetworkLocationHostOfTest {

    @Test
    fun extractsHostFromBaseUrl() {
        assertEquals("termix.example.com", NetworkLocationClassifier.hostOf("https://termix.example.com:8443/app"))
        assertEquals("termix.example.com", NetworkLocationClassifier.hostOf(" https://termix.example.com/ "))
        assertEquals("", NetworkLocationClassifier.hostOf(""))
    }
}
