package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SignatureEdDSA
import com.jcraft.jsch.XDH
import com.toxicbakery.app.bison.model.KeyAlgorithm
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Registers the `ssh-ed25519` signature and the `X25519` key agreement implementations with [JSch].
 *
 * `jsch-2.28.8` ships `jce.SignatureEd25519` and `jce.XDH` inside `META-INF/versions/15` and
 * `META-INF/versions/11` using Java 15 `EdECPublicKeySpec` / `XECPublicKeySpec` classes that are not
 * exposed in the Android SDK.
 *
 * - `ssh-ed25519` uses the BouncyCastle lightweight API ([AndroidJschSignatureEd25519]). Android 12 to 15
 *   have no software Ed25519 `KeyFactory`. There, `KeyFactory.getInstance("Ed25519")` returns the
 *   `AndroidKeyStore` provider, which cannot import a server host key.
 * - `X25519` uses RFC 8410 ASN.1 DER `X509EncodedKeySpec` wrappers with the platform `Conscrypt` /
 *   `AndroidOpenSSL` provider ([AndroidJschXdh]).
 */
internal object JschAndroidCrypto {
    private val ED25519_CLASS_NAME: String = AndroidJschSignatureEd25519::class.java.name
    private val XDH_CLASS_NAME: String = AndroidJschXdh::class.java.name

    fun installGlobalDefaults() {
        JSch.setConfig("ssh-ed25519", ED25519_CLASS_NAME)
        JSch.setConfig("ssh-ed25519-cert-v01@openssh.com", ED25519_CLASS_NAME)
        JSch.setConfig("xdh", XDH_CLASS_NAME)
    }

    fun configureSession(session: Session) {
        installGlobalDefaults()
        session.setConfig("ssh-ed25519", ED25519_CLASS_NAME)
        session.setConfig("ssh-ed25519-cert-v01@openssh.com", ED25519_CLASS_NAME)
        session.setConfig("xdh", XDH_CLASS_NAME)
    }
}

/**
 * `ssh-ed25519` implementation of JSch's [SignatureEdDSA] with the BouncyCastle lightweight
 * [Ed25519Signer]. It does not use a JCA provider, so it works the same on all API levels.
 */
class AndroidJschSignatureEd25519 : SignatureEdDSA {
    private var signer: Ed25519Signer? = null

    private fun signer(): Ed25519Signer =
        signer ?: throw IllegalStateException("init() was not called")

    override fun init() {
        signer = Ed25519Signer()
    }

    override fun setPubKey(y_arr: ByteArray) {
        if (y_arr.size != ED25519_KEY_LEN) {
            throw InvalidKeyException("Ed25519 public key must be $ED25519_KEY_LEN bytes (got ${y_arr.size})")
        }
        signer().init(false, Ed25519PublicKeyParameters(y_arr, 0))
    }

    override fun setPrvKey(bytes: ByteArray) {
        if (bytes.size != ED25519_KEY_LEN) {
            throw InvalidKeyException("Ed25519 private seed must be $ED25519_KEY_LEN bytes (got ${bytes.size})")
        }
        signer().init(true, Ed25519PrivateKeyParameters(bytes, 0))
    }

    override fun sign(): ByteArray = signer().generateSignature()

    override fun update(foo: ByteArray) {
        signer().update(foo, 0, foo.size)
    }

    override fun verify(sig: ByteArray): Boolean =
        signer().verifySignature(unwrapSshEd25519SignatureBlob(sig))

    companion object {
        private const val ED25519_KEY_LEN = 32
        private const val ED25519_SIG_LEN = 64
        private const val SSH_ED25519_ALG = "ssh-ed25519"

        internal fun unwrapSshEd25519SignatureBlob(sig: ByteArray): ByteArray {
            if (sig.size <= ED25519_SIG_LEN || sig.size < 8) {
                return sig
            }
            val algLen = ByteBuffer.wrap(sig, 0, 4).int
            if (algLen <= 0 || 4 + algLen + 4 > sig.size) {
                return sig
            }
            val alg = String(sig, 4, algLen, Charsets.US_ASCII)
            if (alg != SSH_ED25519_ALG) {
                return sig
            }
            val rawLen = ByteBuffer.wrap(sig, 4 + algLen, 4).int
            val rawOffset = 4 + algLen + 4
            if (rawLen <= 0 || rawOffset + rawLen > sig.size) {
                return sig
            }
            return sig.copyOfRange(rawOffset, rawOffset + rawLen)
        }
    }
}

/**
 * JCA `X25519` implementation of JSch's [XDH] key agreement (`curve25519-sha256` / `curve25519-sha256@libssh.org`)
 * using RFC 8410 SubjectPublicKeyInfo DER encoding.
 */
class AndroidJschXdh : XDH {
    private lateinit var publicKeyBytes: ByteArray
    private lateinit var keyAgreement: KeyAgreement
    private var keyLen: Int = 0

    override fun init(name: String, keylen: Int) {
        if (name != "X25519" || keylen != X25519_KEY_LEN) {
            throw NoSuchAlgorithmException("Unsupported XDH curve $name (keylen=$keylen)")
        }
        keyLen = keylen
        val kpg = KeyPairGenerator.getInstance("X25519")
        val keyPair = kpg.generateKeyPair()
        val encoded = keyPair.public.encoded
        if (encoded == null || encoded.size < keylen) {
            throw InvalidKeyException("Unexpected X25519 SubjectPublicKeyInfo length: ${encoded?.size}")
        }
        publicKeyBytes = encoded.copyOfRange(encoded.size - keylen, encoded.size)
        keyAgreement = KeyAgreement.getInstance("X25519")
        keyAgreement.init(keyPair.private)
    }

    override fun getQ(): ByteArray = publicKeyBytes

    override fun getSecret(Q: ByteArray): ByteArray {
        if (Q.size != keyLen) {
            throw InvalidKeyException("X25519 peer public key must be $keyLen bytes (got ${Q.size})")
        }
        val der = ByteArray(X25519_X509_PREFIX.size + keyLen)
        System.arraycopy(X25519_X509_PREFIX, 0, der, 0, X25519_X509_PREFIX.size)
        System.arraycopy(Q, 0, der, X25519_X509_PREFIX.size, keyLen)
        val peerPublicKey = KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(der))
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    override fun validate(u: ByteArray): Boolean = u.size == keyLen

    companion object {
        private const val X25519_KEY_LEN = 32

        // ASN.1 DER SubjectPublicKeyInfo prefix for OID 1.3.101.110 (id-X25519):
        // 30 2a 30 05 06 03 2b 65 6e 03 21 00
        private val X25519_X509_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
        )
    }
}

/**
 * Bridges [AndroidKeystoreKeyManager] hardware-backed / JCA keys (`Ed25519`, `RSA-4096`, `ECDSA-P256`)
 * into JSch's [Identity] interface for `publickey` authentication.
 *
 * The identity does not make a key. When [keyAlias] has no key, [getPublicKeyBlob] and [getSignature]
 * throw a [MissingKeyException]. JSch lets this [RuntimeException] go through `JSch.addIdentity` and
 * `Session.connect`, so the login error names the key that does not exist.
 *
 * When the key needs a user authentication and is locked, [getSignature] throws a
 * [UserAuthRequiredException]. JSch lets it go through `Session.connect` in the same way.
 */
internal class AndroidKeystoreJschIdentity(
    private val keystoreKeyManager: AndroidKeystoreKeyManager,
    private val keyAlias: String,
    private val algorithm: KeyAlgorithm,
) : Identity {

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun getPublicKeyBlob(): ByteArray =
        keystoreKeyManager.getExistingPublicKey(
            keyAlias = keyAlias,
            algorithm = algorithm,
        ).wireBlob

    override fun getSignature(data: ByteArray?): ByteArray? =
        getSignature(data, null)

    override fun getSignature(data: ByteArray?, alg: String?): ByteArray? {
        if (data == null) return null
        return keystoreKeyManager.signSshChallenge(
            keyAlias = keyAlias,
            algorithm = algorithm,
            challenge = data,
            sshAlgorithmOverride = alg,
        )
    }

    @Deprecated("Deprecated in JSch Identity")
    override fun decrypt(): Boolean = true

    override fun getAlgName(): String =
        when (algorithm) {
            KeyAlgorithm.Ed25519 -> "ssh-ed25519"
            KeyAlgorithm.Rsa4096 -> "ssh-rsa"
            KeyAlgorithm.EcdsaP256 -> "ecdsa-sha2-nistp256"
        }

    override fun getName(): String = "android-keystore:$keyAlias"

    override fun isEncrypted(): Boolean = false

    override fun clear() {
        // Hardware-backed AndroidKeyStore private keys remain in secure storage
    }
}
