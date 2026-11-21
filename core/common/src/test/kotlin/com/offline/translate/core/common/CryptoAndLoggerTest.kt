package com.offline.translate.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class CryptoAndLoggerTest {

    @Test
    fun `sha256Hex computes deterministic RFC 6234 digest and verifies file`() {
        val payload = "offline-language-pack-v1".toByteArray()
        val digest = CryptoVerifier.sha256Hex(payload)
        assertEquals(64, digest.length)

        val temp = File.createTempFile("pack_test", ".tpk")
        try {
            temp.writeBytes(payload)
            assertTrue(CryptoVerifier.verifySha256(temp, digest))
            assertFalse(CryptoVerifier.verifySha256(temp, "0000000000000000000000000000000000000000000000000000000000000000"))
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `verifyDetachedSignature validates ECDSA signature over manifest bytes`() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        val kp = kpg.generateKeyPair()
        val manifestBytes = """{"packId":"ru_en","version":"1.0.0"}""".toByteArray()

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(kp.private)
        signer.update(manifestBytes)
        val sigBase64 = Base64.getEncoder().encodeToString(signer.sign())
        val pubKeyBase64 = Base64.getEncoder().encodeToString(kp.public.encoded)

        assertTrue(CryptoVerifier.verifyDetachedSignature(manifestBytes, sigBase64, pubKeyBase64))
        val tampered = """{"packId":"ru_en","version":"9.9.9"}""".toByteArray()
        assertFalse(CryptoVerifier.verifyDetachedSignature(tampered, sigBase64, pubKeyBase64))
    }

    @Test
    fun `SafeLogger redacts emails and long numeric sequences`() {
        val raw = "User user@example.com serial 9876543210 initialized"
        val sanitized = SafeLogger.sanitize(raw)
        assertFalse(sanitized.contains("user@example.com"))
        assertFalse(sanitized.contains("9876543210"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }
}
