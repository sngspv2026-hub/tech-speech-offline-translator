package com.offline.translate.core.common

import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Cryptographic verifier for Language Pack archives and manifests (SHA-256 + RSA/Ed25519 signature verification).
 */
object CryptoVerifier {

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String {
        file.inputStream().buffered().use { stream ->
            return sha256Hex(stream)
        }
    }

    fun sha256Hex(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read = inputStream.read(buffer)
        while (read > 0) {
            digest.update(buffer, 0, read)
            read = inputStream.read(buffer)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(file: File, expectedHex: String): Boolean {
        if (!file.exists() || expectedHex.isBlank()) return false
        return sha256Hex(file).equals(expectedHex.trim(), ignoreCase = true)
    }

    /**
     * Verifies a detached signature (Base64-encoded) over manifest/payload bytes using an X.509 public key.
     * Supports SHA256withECDSA / SHA256withRSA / Ed25519 depending on JVM/Android provider.
     */
    fun verifyDetachedSignature(
        payloadBytes: ByteArray,
        signatureBase64: String,
        publicKeyBase64: String,
        algorithm: String = "SHA256withECDSA",
        keyAlgorithm: String = "EC"
    ): Boolean {
        if (signatureBase64.isBlank() || publicKeyBase64.isBlank()) return false
        return runCatching {
            val keyBytes = Base64.getDecoder().decode(publicKeyBase64.trim())
            val sigBytes = Base64.getDecoder().decode(signatureBase64.trim())
            val keySpec = X509EncodedKeySpec(keyBytes)
            val publicKey = KeyFactory.getInstance(keyAlgorithm).generatePublic(keySpec)
            val verifier = Signature.getInstance(algorithm)
            verifier.initVerify(publicKey)
            verifier.update(payloadBytes)
            verifier.verify(sigBytes)
        }.getOrDefault(false)
    }
}
