package com.nightcinder.helix.stream.nv

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate

/**
 * Runs the real client [PairingManager] against an in-memory implementation of the host half of the
 * GameStream pairing protocol (no sockets).
 */
class PairingManagerTest {

    private class FakeHost(
        private val pin: String,
        private val hash: PairingHash,
        private val tamperSignature: Boolean = false,
        private val alreadyInProgress: Boolean = false,
    ) : PairingTransport {
        val serverIdentity = ClientIdentity.generate()
        val commands = mutableListOf<String>()
        var unpaired = false
        var clientPaired = false
        var longPollFlags = mutableListOf<Boolean>()

        private var aesKey = ByteArray(0)
        private var clientCert: X509Certificate? = null
        private val serverSecret = NvCrypto.randomBytes(16)
        private val serverChallenge = NvCrypto.randomBytes(16)
        private var clientChallengeHash = ByteArray(0)

        private fun xml(inner: String) = """<?xml version="1.0" encoding="utf-8"?><root status_code="200">$inner</root>"""

        override suspend fun pair(args: String, longPoll: Boolean): String {
            val q = args.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val verb = q.keys.first { it in setOf("phrase", "clientchallenge", "serverchallengeresp", "clientpairingsecret") }
            commands += if (verb == "phrase") q.getValue("phrase") else verb
            longPollFlags += longPoll
            return when {
                q["phrase"] == "getservercert" -> {
                    if (alreadyInProgress) return xml("<paired>1</paired><plaincert></plaincert>")
                    val salt = NvCrypto.hexToBytes(q.getValue("salt"))
                    aesKey = NvCrypto.deriveAesKey(hash, salt, pin)
                    clientCert = ClientIdentity.parseCertificate(NvCrypto.hexToBytes(q.getValue("clientcert")))
                    xml("<paired>1</paired><plaincert>${NvCrypto.bytesToHex(serverIdentity.certificatePem)}</plaincert>")
                }
                "clientchallenge" in q -> {
                    val challenge = NvCrypto.aesEcbDecrypt(NvCrypto.hexToBytes(q.getValue("clientchallenge")), aesKey).copyOf(16)
                    val resp = hash.hash(NvCrypto.concat(challenge, serverIdentity.certificate.signature, serverSecret))
                    val enc = NvCrypto.aesEcbEncrypt(NvCrypto.concat(resp, serverChallenge), aesKey)
                    xml("<paired>1</paired><challengeresponse>${NvCrypto.bytesToHex(enc)}</challengeresponse>")
                }
                "serverchallengeresp" in q -> {
                    clientChallengeHash = NvCrypto.aesEcbDecrypt(NvCrypto.hexToBytes(q.getValue("serverchallengeresp")), aesKey)
                        .copyOf(hash.length)
                    var sig = NvCrypto.sign(serverSecret, serverIdentity.privateKey)
                    if (tamperSignature) sig = sig.also { it[0] = (it[0].toInt() xor 0x01).toByte() }
                    xml("<paired>1</paired><pairingsecret>${NvCrypto.bytesToHex(NvCrypto.concat(serverSecret, sig))}</pairingsecret>")
                }
                "clientpairingsecret" in q -> {
                    val data = NvCrypto.hexToBytes(q.getValue("clientpairingsecret"))
                    val clientSecret = data.copyOfRange(0, 16)
                    val sig = data.copyOfRange(16, data.size)
                    val cc = clientCert!!
                    val sigOk = NvCrypto.verify(clientSecret, sig, cc.publicKey)
                    val hashOk = hash.hash(NvCrypto.concat(serverChallenge, cc.signature, clientSecret))
                        .contentEquals(clientChallengeHash)
                    clientPaired = sigOk && hashOk
                    xml("<paired>${if (clientPaired) 1 else 0}</paired>")
                }
                else -> error("unexpected $args")
            }
        }

        override suspend fun pairChallengeHttps(serverCert: X509Certificate): String {
            commands += "pairchallenge(https)"
            assertTrue(serverCert.encoded.contentEquals(serverIdentity.certificate.encoded))
            return xml("<paired>1</paired>")
        }

        override suspend fun unpair() {
            unpaired = true
        }
    }

    private val client = ClientIdentity.generate()

    @Test
    fun correctPin_pairsAndPinsServerCertificate() = runTest {
        val host = FakeHost("4821", PairingHash.SHA256)
        val result = PairingManager(client).pair(7, "4821", host)

        assertTrue(result is PairResult.Paired)
        assertTrue((result as PairResult.Paired).serverCert.encoded.contentEquals(host.serverIdentity.certificate.encoded))
        assertTrue(host.clientPaired)
        assertFalse(host.unpaired)
        assertEquals(
            listOf("getservercert", "clientchallenge", "serverchallengeresp", "clientpairingsecret", "pairchallenge(https)"),
            host.commands,
        )
        // Only getservercert may long-poll (it waits for the owner to type the PIN).
        assertEquals(listOf(true, false, false, false), host.longPollFlags)
    }

    @Test
    fun legacySha1Server_pairsWithSha1() = runTest {
        val host = FakeHost("0007", PairingHash.SHA1)
        val result = PairingManager(client).pair(6, "0007", host)
        assertTrue(result is PairResult.Paired)
        assertTrue(host.clientPaired)
    }

    @Test
    fun wrongPin_returnsPinWrongAndUnpairs() = runTest {
        val host = FakeHost("1111", PairingHash.SHA256)
        val result = PairingManager(client).pair(7, "9999", host)

        assertEquals(PairResult.PinWrong, result)
        assertTrue(host.unpaired)
        assertFalse(host.clientPaired)
        assertFalse(host.commands.contains("clientpairingsecret"))
    }

    @Test
    fun badServerSignature_isRejectedAsPossibleMitm() = runTest {
        val host = FakeHost("1234", PairingHash.SHA256, tamperSignature = true)
        val result = PairingManager(client).pair(7, "1234", host)

        assertTrue(result is PairResult.Failed)
        assertTrue((result as PairResult.Failed).reason.contains("signature", ignoreCase = true))
        assertTrue(host.unpaired)
        assertFalse(host.clientPaired)
    }

    @Test
    fun hostWithPairingAlreadyRunning_reportsAlreadyInProgress() = runTest {
        val host = FakeHost("1234", PairingHash.SHA256, alreadyInProgress = true)
        val result = PairingManager(client).pair(7, "1234", host)

        assertEquals(PairResult.AlreadyInProgress, result)
        assertTrue(host.unpaired)
    }
}
