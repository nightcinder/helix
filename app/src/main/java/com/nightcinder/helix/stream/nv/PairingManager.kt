// SPDX-License-Identifier: GPL-3.0-only
// Portions derived from moonlight-android (https://github.com/moonlight-stream/moonlight-android),
// Copyright (C) the Moonlight project contributors, licensed under the GNU General Public License v3.0.

package com.nightcinder.helix.stream.nv

import java.io.IOException
import java.security.cert.X509Certificate

/** Wire access used by [PairingManager]; implemented by [NvHttp] and by in-memory fakes in tests. */
interface PairingTransport {
    /** `GET /pair?...&<args>` over plain HTTP. [longPoll] disables the read timeout (PIN entry wait). */
    suspend fun pair(args: String, longPoll: Boolean): String

    /** Final `phrase=pairchallenge` request over HTTPS using the client cert and the freshly pinned server cert. */
    suspend fun pairChallengeHttps(serverCert: X509Certificate): String

    suspend fun unpair()
}

sealed class PairResult {
    data class Paired(val serverCert: X509Certificate) : PairResult()
    object PinWrong : PairResult()
    object AlreadyInProgress : PairResult()
    data class Failed(val reason: String) : PairResult()
}

/**
 * Port of moonlight-android's PairingManager for GFE/Sunshine gen 7+ servers
 * (SHA-256 hashing, AES-128-ECB, key = hash(salt || pin)[0:16]).
 */
class PairingManager(private val identity: ClientIdentity) {

    suspend fun pair(serverMajorVersion: Int, pin: String, transport: PairingTransport): PairResult {
        val hash = PairingHash.forServerMajorVersion(serverMajorVersion)
        return try {
            doPair(hash, pin, transport)
        } catch (e: PairingProtocolException) {
            transport.unpair()
            PairResult.Failed(e.message ?: "Pairing failed")
        } catch (e: IOException) {
            // Network/server failure mid-protocol (cancellation is not an IOException and propagates).
            runCatching { transport.unpair() }
            PairResult.Failed(e.message ?: "Network error during pairing")
        }
    }

    private suspend fun doPair(hash: PairingHash, pin: String, t: PairingTransport): PairResult {
        val salt = NvCrypto.randomBytes(16)
        val aesKey = NvCrypto.deriveAesKey(hash, salt, pin)

        // 1. Send salt + our cert. The host answers only after the owner typed the PIN (long poll).
        val certResp = t.pair(
            "phrase=getservercert&salt=${NvCrypto.bytesToHex(salt)}&clientcert=${NvCrypto.bytesToHex(identity.certificatePem)}",
            longPoll = true,
        )
        val certDoc = NvHttp.parseResponse(certResp)
        if (NvHttp.xmlString(certDoc, "paired") != "1") throw PairingProtocolException("Host rejected the pairing request")
        val plainCert = NvHttp.xmlString(certDoc, "plaincert")
        if (plainCert.isNullOrEmpty()) {
            // Another pairing session is already running on the host.
            t.unpair()
            return PairResult.AlreadyInProgress
        }
        val serverCert = try {
            ClientIdentity.parseCertificate(NvCrypto.hexToBytes(plainCert))
        } catch (e: Exception) {
            throw PairingProtocolException("Host sent an unreadable certificate")
        }

        // 2. Client challenge.
        val randomChallenge = NvCrypto.randomBytes(16)
        val challengeResp = requirePaired(
            t.pair("clientchallenge=${NvCrypto.bytesToHex(NvCrypto.aesEcbEncrypt(randomChallenge, aesKey))}", false),
            "client challenge",
        )
        val encServerResp = requireHex(challengeResp, "challengeresponse")
        val decServerResp = NvCrypto.aesEcbDecrypt(encServerResp, aesKey)
        if (decServerResp.size < hash.length + 16) throw PairingProtocolException("Short challenge response")
        val serverResponse = decServerResp.copyOfRange(0, hash.length)
        val serverChallenge = decServerResp.copyOfRange(hash.length, hash.length + 16)

        // 3. Server challenge response: hash(serverChallenge || ourCertSignature || clientSecret).
        val clientSecret = NvCrypto.randomBytes(16)
        val challengeRespHash = hash.hash(NvCrypto.concat(serverChallenge, identity.certificate.signature, clientSecret))
        val secretResp = requirePaired(
            t.pair("serverchallengeresp=${NvCrypto.bytesToHex(NvCrypto.aesEcbEncrypt(challengeRespHash, aesKey))}", false),
            "server challenge response",
        )
        val serverSecretResp = requireHex(secretResp, "pairingsecret")
        if (serverSecretResp.size <= 16) throw PairingProtocolException("Short pairing secret")
        val serverSecret = serverSecretResp.copyOfRange(0, 16)
        val serverSignature = serverSecretResp.copyOfRange(16, serverSecretResp.size)

        // 4. Authenticity: the server signed its secret with the key of the cert it just gave us.
        if (!NvCrypto.verify(serverSecret, serverSignature, serverCert.publicKey)) {
            throw PairingProtocolException("Server signature check failed (possible man-in-the-middle)")
        }

        // 5. PIN check: the server must have hashed our challenge with the same PIN-derived key.
        val expected = hash.hash(NvCrypto.concat(randomChallenge, serverCert.signature, serverSecret))
        if (!expected.contentEquals(serverResponse)) {
            t.unpair()
            return PairResult.PinWrong
        }

        // 6. Send our signed secret.
        val clientPairingSecret = NvCrypto.concat(clientSecret, NvCrypto.sign(clientSecret, identity.privateKey))
        requirePaired(t.pair("clientpairingsecret=${NvCrypto.bytesToHex(clientPairingSecret)}", false), "client pairing secret")

        // 7. Confirmation over HTTPS (required for the host to list us as paired).
        requirePaired(t.pairChallengeHttps(serverCert), "pair challenge")
        return PairResult.Paired(serverCert)
    }

    private fun requirePaired(xml: String, step: String): org.w3c.dom.Document {
        val doc = NvHttp.parseResponse(xml)
        if (NvHttp.xmlString(doc, "paired") != "1") throw PairingProtocolException("Host rejected $step")
        return doc
    }

    private fun requireHex(doc: org.w3c.dom.Document, tag: String): ByteArray {
        val v = NvHttp.xmlString(doc, tag) ?: throw PairingProtocolException("Host response missing $tag")
        return try {
            NvCrypto.hexToBytes(v)
        } catch (e: IllegalArgumentException) {
            throw PairingProtocolException("Host response has invalid $tag")
        }
    }

    private class PairingProtocolException(message: String) : IOException(message)
}
