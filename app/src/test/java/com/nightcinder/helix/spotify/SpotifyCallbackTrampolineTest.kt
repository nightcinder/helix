package com.nightcinder.helix.spotify

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyCallbackTrampolineTest {
    @Test
    fun forwardsUriToSinkAndBringsMainActivityForwardWithClearTopSingleTop() {
        val received = mutableListOf<String>()
        var flags = -1
        SpotifyCallbackTrampoline.handle("com.nightcinder.helix://spotify-callback?code=c&state=s", { received += it }) { flags = it }
        assertEquals(listOf("com.nightcinder.helix://spotify-callback?code=c&state=s"), received)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, flags)
    }

    @Test
    fun missingDataStillForwardsWithoutCallingSink() {
        var called = false
        var flags = -1
        SpotifyCallbackTrampoline.handle(null, { called = true }) { flags = it }
        assertTrue(!called)
        assertEquals(SpotifyCallbackTrampoline.FORWARD_FLAGS, flags)
    }
}
