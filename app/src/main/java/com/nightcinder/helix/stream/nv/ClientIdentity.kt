package com.nightcinder.helix.stream.nv

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/** The client's long-lived GameStream identity: RSA-2048 key + self-signed X.509 certificate. */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    /** PEM bytes sent to the host as `clientcert` (hex encoded by the caller). */
    val certificatePem: ByteArray get() = toPem(certificate)

    fun privateKeyPkcs8(): ByteArray = privateKey.encoded

    fun certificateDer(): ByteArray = certificate.encoded

    companion object {
        const val SUBJECT = "CN=NVIDIA GameStream Client"
        private const val VALIDITY_YEARS = 20L

        fun generate(now: Long = System.currentTimeMillis()): ClientIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name(SUBJECT)
            val notBefore = Date(now - 24L * 3600_000L)
            val notAfter = Date(now + VALIDITY_YEARS * 365L * 24L * 3600_000L)
            val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
            val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, keyPair.public)
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return ClientIdentity(keyPair.private, cert)
        }

        fun fromEncoded(pkcs8: ByteArray, certDer: ByteArray): ClientIdentity {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            return ClientIdentity(key, parseCertificate(certDer))
        }

        fun parseCertificate(bytes: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate

        fun toPem(cert: X509Certificate): ByteArray {
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
            return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n".toByteArray(Charsets.US_ASCII)
        }
    }
}
