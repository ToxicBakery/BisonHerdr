package com.toxicbakery.app.bison.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.logging.Arbor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Exported SSH public key metadata in OpenSSH `authorized_keys` and RFC 4253 binary wire format.
 */
data class ExportedSshPublicKey(
    val keyAlias: String,
    val algorithm: KeyAlgorithm,
    val wireBlob: ByteArray,
    val openSshAuthorizedKeyLine: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportedSshPublicKey) return false
        return keyAlias == other.keyAlias &&
            algorithm == other.algorithm &&
            wireBlob.contentEquals(other.wireBlob) &&
            openSshAuthorizedKeyLine == other.openSshAuthorizedKeyLine
    }

    override fun hashCode(): Int {
        var result = keyAlias.hashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + wireBlob.contentHashCode()
        result = 31 * result + openSshAuthorizedKeyLine.hashCode()
        return result
    }
}

/**
 * Manages on-device SSH asymmetric keys (`Ed25519`, `RSA-4096`, `ECDSA-P256`) backed by Android's
 * hardware-backed `AndroidKeyStore`, with automatic JCA fallback for local JVM unit tests.
 */
class AndroidKeystoreKeyManager {

    private val fallbackKeyPairs = ConcurrentHashMap<String, KeyPair>()
    private val exportedPublicKeys = ConcurrentHashMap<String, ExportedSshPublicKey>()

    /**
     * Generates or loads an existing key pair for [keyAlias] using [algorithm] and returns its
     * OpenSSH `authorized_keys` representation.
     */
    fun getOrCreatePublicKey(
        keyAlias: String,
        algorithm: KeyAlgorithm = KeyAlgorithm.Ed25519,
        requireBiometric: Boolean = false,
        comment: String = "bison-tablet",
    ): ExportedSshPublicKey {
        val cacheKey = "$keyAlias:${algorithm.name}:$comment"
        return exportedPublicKeys.computeIfAbsent(cacheKey) {
            val publicKey = loadExistingPublicKey(keyAlias)
                ?: generateKeyPair(keyAlias, algorithm, requireBiometric).public
            exportPublicKey(
                keyAlias = keyAlias,
                algorithm = algorithm,
                publicKey = publicKey,
                comment = comment,
            )
        }
    }

    /**
     * Signs an SSH `SSH_MSG_USERAUTH_REQUEST` challenge payload using the private key for [keyAlias]
     * and returns the RFC 4253 / RFC 8332 / RFC 5656 / RFC 8709 formatted SSH signature blob.
     */
    fun signSshChallenge(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        challenge: ByteArray,
        sshAlgorithmOverride: String? = null,
    ): ByteArray {
        val privateKey = loadPrivateKey(keyAlias)
            ?: generateKeyPair(keyAlias, algorithm, requireBiometric = false).private
        val resolvedSshAlg = sshAlgorithmOverride?.takeIf { it.isNotBlank() } ?: algorithm.sshKeyType
        val rawSignature = when (algorithm) {
            KeyAlgorithm.Ed25519 -> {
                val sig = Signature.getInstance("Ed25519")
                sig.initSign(privateKey)
                sig.update(challenge)
                sig.sign()
            }

            KeyAlgorithm.Rsa4096 -> {
                val jcaDigestAlg = when (resolvedSshAlg) {
                    "rsa-sha2-256", "ssh-rsa-sha256@ssh.com" -> "SHA256withRSA"
                    "ssh-rsa" -> "SHA1withRSA"
                    else -> "SHA512withRSA"
                }
                val sig = Signature.getInstance(jcaDigestAlg)
                sig.initSign(privateKey)
                sig.update(challenge)
                sig.sign()
            }

            KeyAlgorithm.EcdsaP256 -> {
                val sig = Signature.getInstance("SHA256withECDSA")
                sig.initSign(privateKey)
                sig.update(challenge)
                derEcdsaToSshMpintPair(sig.sign())
            }
        }
        return encodeSshSignatureBlob(resolvedSshAlg, rawSignature)
    }

    /**
     * Deletes a key entry by [keyAlias] from AndroidKeyStore (and the in-memory fallback store).
     */
    fun deleteKey(keyAlias: String) {
        fallbackKeyPairs.remove(keyAlias)
        val prefix = "$keyAlias:"
        exportedPublicKeys.keys.removeIf { it.startsWith(prefix) }
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
            ks.load(null)
            if (ks.containsAlias(keyAlias)) {
                ks.deleteEntry(keyAlias)
            }
        }.onFailure { error ->
            log.d(error) { "Deleting key $keyAlias from AndroidKeyStore skipped" }
        }
    }

    private fun loadExistingPublicKey(keyAlias: String): PublicKey? {
        fallbackKeyPairs[keyAlias]?.public?.let { return it }
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
            ks.load(null)
            ks.getCertificate(keyAlias)?.publicKey
        }.getOrNull()
    }

    private fun loadPrivateKey(keyAlias: String): PrivateKey? {
        fallbackKeyPairs[keyAlias]?.private?.let { return it }
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
            ks.load(null)
            (ks.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry)?.privateKey
        }.getOrNull()
    }

    private fun generateKeyPair(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        requireBiometric: Boolean,
    ): KeyPair {
        val keystoreAttempt = runCatching {
            generateInAndroidKeystore(keyAlias, algorithm, requireBiometric)
        }
        if (keystoreAttempt.isSuccess) {
            return keystoreAttempt.getOrThrow()
        }
        log.d { "AndroidKeyStore unavailable for $keyAlias (${algorithm.name}); using JCA key pair" }
        return fallbackKeyPairs.computeIfAbsent(keyAlias) {
            generateStandardJcaKeyPair(algorithm)
        }
    }

    private fun generateInAndroidKeystore(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        requireBiometric: Boolean,
    ): KeyPair {
        val purposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        return when (algorithm) {
            KeyAlgorithm.Ed25519 -> {
                val generator = KeyPairGenerator.getInstance("Ed25519", ANDROID_KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(keyAlias, purposes)
                    .setUserAuthenticationRequired(requireBiometric)
                    .build()
                generator.initialize(spec)
                generator.generateKeyPair()
            }

            KeyAlgorithm.Rsa4096 -> {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(keyAlias, purposes)
                    .setKeySize(4096)
                    .setDigests(KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setUserAuthenticationRequired(requireBiometric)
                    .build()
                generator.initialize(spec)
                generator.generateKeyPair()
            }

            KeyAlgorithm.EcdsaP256 -> {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(keyAlias, purposes)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(requireBiometric)
                    .build()
                generator.initialize(spec)
                generator.generateKeyPair()
            }
        }
    }

    private fun generateStandardJcaKeyPair(algorithm: KeyAlgorithm): KeyPair =
        when (algorithm) {
            KeyAlgorithm.Ed25519 -> {
                val generator = KeyPairGenerator.getInstance("Ed25519")
                generator.generateKeyPair()
            }

            KeyAlgorithm.Rsa4096 -> {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(RSAKeyGenParameterSpec(4096, RSAKeyGenParameterSpec.F4))
                generator.generateKeyPair()
            }

            KeyAlgorithm.EcdsaP256 -> {
                val generator = KeyPairGenerator.getInstance("EC")
                generator.initialize(ECGenParameterSpec("secp256r1"))
                generator.generateKeyPair()
            }
        }

    private fun exportPublicKey(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        publicKey: PublicKey,
        comment: String,
    ): ExportedSshPublicKey {
        val wireType = when (algorithm) {
            KeyAlgorithm.Ed25519 -> "ssh-ed25519"
            KeyAlgorithm.Rsa4096 -> "ssh-rsa"
            KeyAlgorithm.EcdsaP256 -> "ecdsa-sha2-nistp256"
        }
        val wireBlob = when (algorithm) {
            KeyAlgorithm.Ed25519 -> encodeEd25519PublicKeyBlob(publicKey)
            KeyAlgorithm.Rsa4096 -> encodeRsaPublicKeyBlob(publicKey as RSAPublicKey)
            KeyAlgorithm.EcdsaP256 -> encodeEcdsaP256PublicKeyBlob(publicKey as ECPublicKey)
        }
        val base64Blob = Base64.getEncoder().encodeToString(wireBlob)
        val authorizedLine = "$wireType $base64Blob $comment"
        return ExportedSshPublicKey(
            keyAlias = keyAlias,
            algorithm = algorithm,
            wireBlob = wireBlob,
            openSshAuthorizedKeyLine = authorizedLine,
        )
    }

    private fun encodeEd25519PublicKeyBlob(publicKey: PublicKey): ByteArray {
        val x509Bytes = publicKey.encoded
        // Ed25519 SubjectPublicKeyInfo ends with the 32-byte raw public key
        val raw32 = if (x509Bytes.size >= 32) {
            x509Bytes.copyOfRange(x509Bytes.size - 32, x509Bytes.size)
        } else {
            x509Bytes
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            writeSshString(dos, "ssh-ed25519".toByteArray(Charsets.US_ASCII))
            writeSshString(dos, raw32)
        }
        return out.toByteArray()
    }

    private fun encodeRsaPublicKeyBlob(publicKey: RSAPublicKey): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            writeSshString(dos, "ssh-rsa".toByteArray(Charsets.US_ASCII))
            writeSshMpint(dos, publicKey.publicExponent)
            writeSshMpint(dos, publicKey.modulus)
        }
        return out.toByteArray()
    }

    private fun encodeEcdsaP256PublicKeyBlob(publicKey: ECPublicKey): ByteArray {
        val point = publicKey.w
        val xBytes = toFixedUnsigned(point.affineX, 32)
        val yBytes = toFixedUnsigned(point.affineY, 32)
        val uncompressedPoint = ByteArray(65).apply {
            this[0] = 0x04
            System.arraycopy(xBytes, 0, this, 1, 32)
            System.arraycopy(yBytes, 0, this, 33, 32)
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            writeSshString(dos, "ecdsa-sha2-nistp256".toByteArray(Charsets.US_ASCII))
            writeSshString(dos, "nistp256".toByteArray(Charsets.US_ASCII))
            writeSshString(dos, uncompressedPoint)
        }
        return out.toByteArray()
    }

    private fun encodeSshSignatureBlob(sshAlgorithmName: String, rawSignature: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            writeSshString(dos, sshAlgorithmName.toByteArray(Charsets.US_ASCII))
            writeSshString(dos, rawSignature)
        }
        return out.toByteArray()
    }

    /**
     * Converts an ASN.1 DER-encoded ECDSA signature `SEQUENCE { INTEGER r, INTEGER s }` into
     * RFC 5656 SSH `mpint r || mpint s` format.
     */
    internal fun derEcdsaToSshMpintPair(derSignature: ByteArray): ByteArray {
        if (derSignature.size < 8 || derSignature[0] != 0x30.toByte()) {
            return derSignature
        }
        var offset = 2
        if ((derSignature[1].toInt() and 0x80) != 0) {
            offset += (derSignature[1].toInt() and 0x7F)
        }
        if (offset >= derSignature.size || derSignature[offset] != 0x02.toByte()) {
            return derSignature
        }
        val rLen = derSignature[offset + 1].toInt() and 0xFF
        val rBytes = derSignature.copyOfRange(offset + 2, offset + 2 + rLen)
        val sOffset = offset + 2 + rLen
        if (sOffset + 2 > derSignature.size || derSignature[sOffset] != 0x02.toByte()) {
            return derSignature
        }
        val sLen = derSignature[sOffset + 1].toInt() and 0xFF
        val sBytes = derSignature.copyOfRange(sOffset + 2, sOffset + 2 + sLen)

        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            writeSshMpint(dos, BigInteger(1, rBytes))
            writeSshMpint(dos, BigInteger(1, sBytes))
        }
        return out.toByteArray()
    }

    private fun writeSshString(dos: DataOutputStream, bytes: ByteArray) {
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun writeSshMpint(dos: DataOutputStream, value: BigInteger) {
        val bytes = value.toByteArray()
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun toFixedUnsigned(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == length -> raw
            raw.size > length -> raw.copyOfRange(raw.size - length, raw.size)
            else -> ByteArray(length).apply {
                System.arraycopy(raw, 0, this, length - raw.size, raw.size)
            }
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private val log = Arbor.tag("AndroidKeystoreKeyManager")
    }
}
