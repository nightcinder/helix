package com.nightcinder.helix.spotify

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 PKCE helpers (S256 only). */
object Pkce {
    private const val VERIFIER_BYTES = 64
    private const val STATE_BYTES = 24

    fun newVerifier(random: SecureRandom = SecureRandom()): String = randomToken(VERIFIER_BYTES, random)

    fun newState(random: SecureRandom = SecureRandom()): String = randomToken(STATE_BYTES, random)

    fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun randomToken(size: Int, random: SecureRandom): String {
        val bytes = ByteArray(size).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
