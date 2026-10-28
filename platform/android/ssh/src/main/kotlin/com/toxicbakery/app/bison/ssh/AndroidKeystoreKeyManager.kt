package com.toxicbakery.app.bison.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.KeystoreKeySupport
import com.toxicbakery.logging.Arbor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.Key
import java.security.KeyFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/** Makes a key pair for an alias in AndroidKeyStore. */
internal fun interface KeystoreKeyGenerator {
    fun generate(keyAlias: String, algorithm: KeyAlgorithm, requireBiometric: Boolean): KeyPair
}

/**
 * Manages on-device SSH asymmetric keys (`Ed25519`, `RSA-4096`, `ECDSA-P256`) backed by Android's
 * hardware-backed `AndroidKeyStore`.
 *
 * When AndroidKeyStore does not make a key, the manager throws a [KeyCreationException] that names the
 * cause. When [allowSoftwareKeys] is true, the manager makes a software JCA key in memory instead. Only JVM
 * tests set it, because the JVM has no AndroidKeyStore. A device must not use a software key.
 */
class AndroidKeystoreKeyManager internal constructor(
    private val allowSoftwareKeys: Boolean,
    /** Makes a key in AndroidKeyStore. A JVM test can give a generator that fails in a known way. */
    private val keystoreKeyGenerator: KeystoreKeyGenerator = androidKeystoreGenerator,
    /** The key types that AndroidKeyStore supports on this device. A JVM test can give a different list. */
    private val keySupport: KeystoreKeySupport = AndroidKeystoreKeySupport,
) : SshPublicKeyProvider {

    /** Makes a manager that uses AndroidKeyStore. Set [allowSoftwareKeys] to true only in JVM tests. */
    constructor(allowSoftwareKeys: Boolean = false) : this(
        allowSoftwareKeys = allowSoftwareKeys,
        keystoreKeyGenerator = androidKeystoreGenerator,
        keySupport = AndroidKeystoreKeySupport,
    )

    private val fallbackKeyPairs = ConcurrentHashMap<String, KeyPair>()
    private val exportedPublicKeys = ConcurrentHashMap<String, ExportedSshPublicKey>()

    // Serializes key creation and replacement, so that one alias never gets two new keys.
    private val keyLock = Any()

    /**
     * Generates or loads an existing key pair for [keyAlias] using [algorithm] and returns its
     * OpenSSH `authorized_keys` representation. This is the only function that makes a key.
     *
     * Throws a [KeyCreationException] when AndroidKeyStore does not make the key.
     */
    fun getOrCreatePublicKey(
        keyAlias: String,
        algorithm: KeyAlgorithm = KeyAlgorithm.Ed25519,
        requireBiometric: Boolean = false,
        comment: String = DEFAULT_KEY_COMMENT,
    ): ExportedSshPublicKey =
        exportedPublicKey(keyAlias, algorithm, comment) {
            generateKeyPair(keyAlias, algorithm, requireBiometric).public
        }

    /**
     * Returns the public key of the stored key for [keyAlias]. It does not make a key.
     *
     * Throws a [MissingKeyException] when [keyAlias] has no key.
     */
    fun getExistingPublicKey(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        comment: String = DEFAULT_KEY_COMMENT,
    ): ExportedSshPublicKey =
        exportedPublicKey(keyAlias, algorithm, comment) { throw MissingKeyException(keyAlias) }

    /**
     * Returns the exported public key of the stored key for [keyAlias]. When [keyAlias] has no usable key,
     * [missingKey] gives the public key or throws.
     */
    private inline fun exportedPublicKey(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        comment: String,
        missingKey: () -> PublicKey,
    ): ExportedSshPublicKey {
        val cacheKey = "$keyAlias:${algorithm.name}:$comment"
        exportedPublicKeys[cacheKey]?.let { return it }
        return synchronized(keyLock) {
            exportedPublicKeys[cacheKey]?.let { return@synchronized it }
            val publicKey = usableStoredKey(keyAlias, algorithm, ::loadExistingPublicKey) ?: missingKey()
            val exported = exportPublicKey(
                keyAlias = keyAlias,
                algorithm = algorithm,
                publicKey = publicKey,
                comment = comment,
            )
            exportedPublicKeys[cacheKey] = exported
            exported
        }
    }

    override suspend fun authorizedKeyLine(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        requireBiometric: Boolean,
    ): String = withContext(Dispatchers.IO) {
        getOrCreatePublicKey(
            keyAlias = keyAlias,
            algorithm = algorithm,
            requireBiometric = requireBiometric,
        ).openSshAuthorizedKeyLine
    }

    /**
     * Signs an SSH `SSH_MSG_USERAUTH_REQUEST` challenge payload using the private key for [keyAlias]
     * and returns the RFC 4253 / RFC 8332 / RFC 5656 / RFC 8709 formatted SSH signature blob.
     *
     * It does not make a key, because the host does not know a new key. Throws a [MissingKeyException]
     * when [keyAlias] has no key. Use [getOrCreatePublicKey] to make the key.
     */
    fun signSshChallenge(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        challenge: ByteArray,
        sshAlgorithmOverride: String? = null,
    ): ByteArray {
        val privateKey = loadPrivateKey(keyAlias)?.takeIf { isKeyOfAlgorithm(it, algorithm) }
            ?: synchronized(keyLock) { usableStoredKey(keyAlias, algorithm, ::loadPrivateKey) }
            ?: throw MissingKeyException(keyAlias)
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

    /** Returns true when a key exists for [keyAlias]. It does not make a key. */
    fun hasKey(keyAlias: String): Boolean = storedKeyExists(keyAlias)

    /**
     * Returns the stored key for [keyAlias] from [load], or null when the caller must create a key.
     *
     * - Earlier versions created a P-256 EC key with no digests for [KeyAlgorithm.Ed25519], which
     *   cannot sign as Ed25519. Such a key never worked for SSH, so it is deleted, and the result is
     *   null. A new key does not remove a working login.
     * - Any other stored key that is not of [algorithm] causes an [IllegalStateException]. It can be
     *   the working key of a different machine that uses the same alias, so it is not replaced.
     * - A stored key that does not load causes an [IllegalStateException]. The key is not replaced,
     *   because a new key would stop the logins that use the stored key.
     *
     * Call it while [keyLock] is held.
     */
    private fun <K : Key> usableStoredKey(keyAlias: String, algorithm: KeyAlgorithm, load: (String) -> K?): K? {
        val stored = load(keyAlias)
        if (stored != null) {
            if (isKeyOfAlgorithm(stored, algorithm)) return stored
            check(algorithm == KeyAlgorithm.Ed25519 && isLegacyBrokenEd25519Key(keyAlias)) {
                "The key alias $keyAlias holds a ${stored.algorithm} key, not a ${algorithm.name} key. " +
                    "Use a different alias for the ${algorithm.name} key."
            }
            log.w {
                "Replacing key $keyAlias: it is a P-256 key from an earlier version that cannot sign as Ed25519. " +
                    "Add the new public key to authorized_keys."
            }
            deleteKey(keyAlias)
            return null
        }
        check(!storedKeyExists(keyAlias)) { "The key $keyAlias exists in AndroidKeyStore but cannot be loaded" }
        return null
    }

    /**
     * True for the AndroidKeyStore key that earlier versions made for [KeyAlgorithm.Ed25519]: an EC
     * key that allows no digests. An [KeyAlgorithm.EcdsaP256] key from this class allows SHA-256.
     */
    private fun isLegacyBrokenEd25519Key(keyAlias: String): Boolean {
        val privateKey = loadPrivateKey(keyAlias) ?: return false
        if (!privateKey.algorithm.equals(KeyProperties.KEY_ALGORITHM_EC, ignoreCase = true)) return false
        return runCatching {
            KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE_PROVIDER)
                .getKeySpec(privateKey, KeyInfo::class.java)
                .digests
                .isEmpty()
        }.getOrDefault(false)
    }

    private fun storedKeyExists(keyAlias: String): Boolean =
        fallbackKeyPairs.containsKey(keyAlias) || runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }.containsAlias(keyAlias)
        }.getOrDefault(false)

    private fun isKeyOfAlgorithm(key: Key, algorithm: KeyAlgorithm): Boolean {
        val keyAlgorithm = key.algorithm.orEmpty()
        return when (algorithm) {
            KeyAlgorithm.Ed25519 -> keyAlgorithm.equals("EdDSA", ignoreCase = true) ||
                keyAlgorithm.equals("Ed25519", ignoreCase = true) ||
                keyAlgorithm == ED25519_OID
            KeyAlgorithm.Rsa4096 -> keyAlgorithm.equals(KeyProperties.KEY_ALGORITHM_RSA, ignoreCase = true)
            KeyAlgorithm.EcdsaP256 -> keyAlgorithm.equals(KeyProperties.KEY_ALGORITHM_EC, ignoreCase = true)
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
            // getKey() works for all key types. getEntry() fails for Ed25519 keys on some API levels.
            ks.getKey(keyAlias, null) as? PrivateKey
        }.getOrNull()
    }

    /**
     * Makes a key pair for [keyAlias] in AndroidKeyStore. When AndroidKeyStore fails, it throws a
     * [KeyCreationException], or it makes a software key if [allowSoftwareKeys] is true.
     */
    private fun generateKeyPair(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        requireBiometric: Boolean,
    ): KeyPair {
        val keystoreError = try {
            return keystoreKeyGenerator.generate(keyAlias, algorithm, requireBiometric)
        } catch (error: Exception) {
            error
        }
        if (!allowSoftwareKeys) {
            val failure = keyCreationFailure(algorithm, requireBiometric, keystoreError)
            throw KeyCreationException(failure, keyCreationMessage(keyAlias, failure), keystoreError)
        }
        log.d { "AndroidKeyStore unavailable for $keyAlias (${algorithm.name}); using JCA key pair" }
        return fallbackKeyPairs.computeIfAbsent(keyAlias) {
            runCatching { generateStandardJcaKeyPair(algorithm) }.getOrElse { jcaError ->
                throw UnsupportedOperationException(unsupportedAlgorithmMessage(algorithm), keystoreError)
                    .apply { addSuppressed(jcaError) }
            }
        }
    }

    /**
     * Returns the cause of the AndroidKeyStore [error]. AndroidKeyStore gives an [IllegalStateException]
     * when a key needs user authentication and the device has no secure screen lock or no biometric.
     */
    private fun keyCreationFailure(
        algorithm: KeyAlgorithm,
        requireBiometric: Boolean,
        error: Exception,
    ): KeyCreationFailure =
        when {
            !keySupport.isSupported(algorithm) -> KeyCreationFailure.AlgorithmNotSupported(algorithm)
            requireBiometric && generateSequence<Throwable>(error) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .any { it is IllegalStateException } -> KeyCreationFailure.NoSecureLockScreen
            else -> KeyCreationFailure.Other(error.message ?: error::class.java.name)
        }

    private fun keyCreationMessage(keyAlias: String, failure: KeyCreationFailure): String =
        when (failure) {
            KeyCreationFailure.NoSecureLockScreen ->
                "AndroidKeyStore did not make the key $keyAlias. The key needs user authentication, " +
                    "but the device has no secure screen lock or no biometric."
            is KeyCreationFailure.AlgorithmNotSupported -> unsupportedAlgorithmMessage(failure.algorithm)
            is KeyCreationFailure.Other -> "AndroidKeyStore did not make the key $keyAlias: ${failure.detail}"
        }

    private fun unsupportedAlgorithmMessage(algorithm: KeyAlgorithm): String =
        if (algorithm == KeyAlgorithm.Ed25519) {
            "Ed25519 SSH keys need Android 13 or later. Edit the machine and select ECDSA P-256."
        } else {
            "${algorithm.sshKeyType} SSH keys are not available on this device."
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
        private const val ED25519_CURVE = "ed25519"
        private const val ED25519_OID = "1.3.101.112"
        private const val DEFAULT_KEY_COMMENT = "bison-tablet"

        /** The maximum number of causes that [keyCreationFailure] examines. */
        private const val MAX_CAUSE_DEPTH = 16
        private val log = Arbor.tag("AndroidKeystoreKeyManager")

        /** The [KeystoreKeyGenerator] of a device. */
        private val androidKeystoreGenerator = KeystoreKeyGenerator(::generateInAndroidKeystore)

        private fun generateInAndroidKeystore(
            keyAlias: String,
            algorithm: KeyAlgorithm,
            requireBiometric: Boolean,
        ): KeyPair {
            val purposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            return when (algorithm) {
                KeyAlgorithm.Ed25519 -> {
                    // AndroidKeyStore (API 33+) makes Ed25519 keys with the EC generator and the "ed25519"
                    // curve. Ed25519 hashes the message itself, so the key must allow DIGEST_NONE.
                    val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE_PROVIDER)
                    val spec = KeyGenParameterSpec.Builder(keyAlias, purposes)
                        .setAlgorithmParameterSpec(ECGenParameterSpec(ED25519_CURVE))
                        .setDigests(KeyProperties.DIGEST_NONE)
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
    }
}
