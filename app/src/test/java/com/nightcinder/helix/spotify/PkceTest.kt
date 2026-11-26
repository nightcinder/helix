package com.nightcinder.helix.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun challengeMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierAndStateAreUrlSafeUniqueAndInRange() {
        val a = Pkce.newVerifier()
        val b = Pkce.newVerifier()
        assertNotEquals(a, b)
        assertTrue(a.length in 43..128)
        assertTrue(Regex("[A-Za-z0-9_-]+").matches(a))
        assertNotEquals(Pkce.newState(), Pkce.newState())
    }
}
