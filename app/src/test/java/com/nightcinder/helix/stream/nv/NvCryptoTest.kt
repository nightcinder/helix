package com.nightcinder.helix.stream.nv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class NvCryptoTest {

    @Test
    fun hex_roundTripAndUppercase() {
        val bytes = byteArrayOf(0x00, 0x1F, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("001F7F80FF", NvCrypto.bytesToHex(bytes))
        assertArrayEquals(bytes, NvCrypto.hexToBytes("001f7f80ff"))
        assertArrayEquals(bytes, NvCrypto.hexToBytes(NvCrypto.bytesToHex(bytes)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun hex_rejectsOddLength() {
        NvCrypto.hexToBytes("ABC")
    }

    @Test
    fun sha256_matchesKnownVector() {
        // FIPS 180-2 test vector for "abc".
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            NvCrypto.bytesToHex(PairingHash.SHA256.hash("abc".toByteArray())),
        )
        assertEquals(32, PairingHash.SHA256.length)
        assertEquals(20, PairingHash.SHA1.length)
    }

    @Test
    fun hashAlgorithm_followsServerMajorVersion() {
        assertEquals(PairingHash.SHA256, PairingHash.forServerMajorVersion(7))
        assertEquals(PairingHash.SHA256, PairingHash.forServerMajorVersion(8))
        assertEquals(PairingHash.SHA1, PairingHash.forServerMajorVersion(6))
    }

    @Test
    fun aesEcb_matchesFips197Vector() {
        val key = NvCrypto.hexToBytes("000102030405060708090A0B0C0D0E0F")
        val plain = NvCrypto.hexToBytes("00112233445566778899AABBCCDDEEFF")
        val cipher = NvCrypto.aesEcbEncrypt(plain, key)
        assertEquals("69C4E0D86A7B0430D8CDB78070B4C55A", NvCrypto.bytesToHex(cipher))
        assertArrayEquals(plain, NvCrypto.aesEcbDecrypt(cipher, key))
    }

    @Test
    fun aesEcb_zeroPadsPartialBlocks() {
        val key = ByteArray(16) { it.toByte() }
        val enc = NvCrypto.aesEcbEncrypt(ByteArray(20) { 1 }, key)
        assertEquals(32, enc.size)
        val dec = NvCrypto.aesEcbDecrypt(enc, key)
        assertArrayEquals(ByteArray(20) { 1 }, dec.copyOf(20))
        assertTrue(dec.copyOfRange(20, 32).all { it == 0.toByte() })
    }

    @Test
    fun deriveAesKey_isFirst16BytesOfSaltPinHash() {
        val salt = ByteArray(16) { (it * 3).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(salt + "1234".toByteArray()).copyOf(16)
        assertArrayEquals(expected, NvCrypto.deriveAesKey(PairingHash.SHA256, salt, "1234"))
        assertEquals(16, NvCrypto.deriveAesKey(PairingHash.SHA1, salt, "1234").size)
    }

    @Test
    fun pin_isFourDigits() {
        repeat(200) {
            val pin = NvCrypto.generatePin()
            assertEquals(4, pin.length)
            assertTrue(pin.all { it in '0'..'9' })
        }
    }

    @Test
    fun signAndVerify_acceptsGenuineRejectsTampered() {
        val id = ClientIdentity.generate()
        val data = "secret".toByteArray()
        val sig = NvCrypto.sign(data, id.privateKey)
        assertEquals(256, sig.size)
        assertTrue(NvCrypto.verify(data, sig, id.certificate.publicKey))
        assertFalse(NvCrypto.verify("secreT".toByteArray(), sig, id.certificate.publicKey))
        val other = ClientIdentity.generate()
        assertFalse(NvCrypto.verify(data, sig, other.certificate.publicKey))
    }

    @Test
    fun clientIdentity_generatesRsa2048SelfSignedCertAndSurvivesEncoding() {
        val id = ClientIdentity.generate()
        assertEquals("CN=NVIDIA GameStream Client", id.certificate.subjectX500Principal.name)
        assertEquals(id.certificate.subjectX500Principal, id.certificate.issuerX500Principal)
        assertTrue(id.certificate.sigAlgName.equals("SHA256withRSA", ignoreCase = true))
        assertEquals(2048, (id.certificate.publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength())
        id.certificate.verify(id.certificate.publicKey)
        id.certificate.checkValidity()
        // Long validity: still valid in 15 years.
        id.certificate.checkValidity(java.util.Date(System.currentTimeMillis() + 15L * 365 * 24 * 3600 * 1000))

        val restored = ClientIdentity.fromEncoded(id.privateKeyPkcs8(), id.certificateDer())
        assertArrayEquals(id.certificateDer(), restored.certificateDer())
        val sig = NvCrypto.sign("x".toByteArray(), restored.privateKey)
        assertTrue(NvCrypto.verify("x".toByteArray(), sig, id.certificate.publicKey))

        val pem = String(id.certificatePem, Charsets.US_ASCII)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"))
        assertNotNull(ClientIdentity.parseCertificate(id.certificatePem))
    }
}
