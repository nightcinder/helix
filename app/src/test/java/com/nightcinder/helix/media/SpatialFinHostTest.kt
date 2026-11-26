package com.nightcinder.helix.media

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialFinHostTest {
    @Test
    fun open_whenNotInstalled_reportsNotInstalled() {
        assertEquals(SpatialFinState.NotInstalled, SpatialFinHost { false }.open())
    }

    @Test
    fun open_whenInstalled_isReady() {
        assertTrue(SpatialFinHost { true }.open() is SpatialFinState.Ready)
    }

    @Test
    fun guardedEmbed_securityException_becomesRejectedNotCrash() {
        val host = SpatialFinHost { true }
        val state = host.guardedEmbed(Intent()) { throw SecurityException("untrusted embedding") }
        assertTrue(state is SpatialFinState.Rejected)
        assertTrue((state as SpatialFinState.Rejected).reason.contains("knownActivityEmbeddingCerts"))
    }

    @Test
    fun guardedEmbed_success_staysReady() {
        val intent = Intent()
        var launched = 0
        val state = SpatialFinHost { true }.guardedEmbed(intent) { launched++ }
        assertEquals(1, launched)
        assertEquals(SpatialFinState.Ready(intent), state)
    }

    @Test
    fun targetsSpatialFinMainActivityExplicitly() {
        assertEquals("dev.spatialfin", SpatialFinHost.PACKAGE)
        assertEquals("dev.spatialfin.unified.UnifiedMainActivity", SpatialFinHost.MAIN_ACTIVITY)
    }
}
