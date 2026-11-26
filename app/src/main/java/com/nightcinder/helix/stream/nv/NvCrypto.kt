package com.nightcinder.helix.stream.nv

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Hash used by the GameStream pairing protocol (SHA-256 for server major version >= 7, else SHA-1). */
enum class PairingHash(val algorithm: String, val length: Int) {
    SHA1("SHA-1", 20),
    SHA256("SHA-256", 32);

    fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(data)

    companion object {
        fun forServerMajorVersion(major: Int): PairingHash = if (major >= 7) SHA256 else SHA1
    }
}

/** Pure crypto/encoding helpers for the pairing protocol (JVM-testable, no Android dependencies). */
object NvCrypto {
    private val secureRandom = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

    /** Random 4-digit PIN (leading zeros allowed). */
    fun generatePin(): String = String.format("%04d", secureRandom.nextInt(10_000))

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() shr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val s = hex.trim()
        require(s.length % 2 == 0) { "Odd-length hex string" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    /** salt || ASCII(pin). */
    fun saltPin(salt: ByteArray, pin: String): ByteArray = concat(salt, pin.toByteArray(Charsets.UTF_8))

    /** AES-128 key = first 16 bytes of hash(salt || pin). */
    fun deriveAesKey(hash: PairingHash, salt: ByteArray, pin: String): ByteArray =
        hash.hash(saltPin(salt, pin)).copyOf(16)

    /** AES-128-ECB, no padding; input is zero-padded up to a whole number of 16-byte blocks. */
    fun aesEcbEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val padded = if (data.size % 16 == 0) data else data.copyOf(((data.size / 16) + 1) * 16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(padded)
    }

    fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "Ciphertext is not block aligned" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    fun sign(data: ByteArray, key: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(key)
        sig.update(data)
        return sig.sign()
    }

    fun verify(data: ByteArray, signature: ByteArray, key: PublicKey): Boolean = try {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(key)
        sig.update(data)
        sig.verify(signature)
    } catch (_: Exception) {
        false
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
