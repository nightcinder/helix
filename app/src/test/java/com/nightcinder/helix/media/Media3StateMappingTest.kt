package com.nightcinder.helix.media

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Media3StateMappingTest {
    @Test
    fun mapsPlayerStatesToPlaybackStates() {
        assertEquals(PlaybackState.Loading, mapPlayerState(Player.STATE_BUFFERING, true))
        assertEquals(PlaybackState.Playing, mapPlayerState(Player.STATE_READY, true))
        assertEquals(PlaybackState.Paused, mapPlayerState(Player.STATE_READY, false))
        assertEquals(PlaybackState.Ended, mapPlayerState(Player.STATE_ENDED, true))
        assertNull(mapPlayerState(Player.STATE_IDLE, true))
    }
}
