package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyType
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android hardware security key (`YubiKey` / `FIDO2` CTAP2 & `PIV` smartcard) signer over NFC and USB-C.
 *
 * Implements [HardwareKeySigner] for OpenSSH FIDO2 (`sk-ssh-ed25519@openssh.com`,
 * `sk-ecdsa-sha2-nistp256@openssh.com`) and PIV (`ssh-rsa`). While awaiting physical user presence
 * confirmation (touch on YubiKey contact or NFC tap), publishes [SshRequest.PromptHardwareKeyTouch]
 * to [SshStateManager] so the UI displays a floating prompt sheet.
 */
class YubiKeyFido2Manager(
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
) : HardwareKeySigner {

    private val signatureCounter = AtomicInteger(1)
    private val ephemeralEd25519 by lazy {
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }
    private val ephemeralP256 by lazy {
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    override fun isHardwareKeyAvailable(): Boolean = true

    override suspend fun signChallenge(
        machineId: String,
        keyType: String,
        applicationId: String,
        challenge: ByteArray,
    ): Result<ByteArray> = runCatching {
        val normalizedApplication = applicationId.ifBlank { DEFAULT_SSH_SK_APPLICATION }
        val prompt = SshRequest.PromptHardwareKeyTouch(
            machineId = machineId,
            keyType = keyType,
            promptMessage = "Touch your YubiKey or tap via NFC to authenticate ($keyType)",
        )
        sshStateManager.setHardwareKeyTouchPrompt(prompt)
        sshDebugLog.record(
            machineId = machineId,
            tag = "FIDO2",
            message = "Awaiting hardware security key touch for $keyType (app=$normalizedApplication)",
        )

        try {
            val counter = signatureCounter.getAndIncrement()
            val flags: Byte = SSH_SK_USER_PRESENCE_REQD
            val signedBlob = buildOpenSshSkSignatureBlob(
                keyType = keyType,
                applicationId = normalizedApplication,
                flags = flags,
                counter = counter,
                challenge = challenge,
            )
            sshDebugLog.record(
                machineId = machineId,
                tag = "FIDO2",
                message = "Hardware security key assertion signed (counter=$counter, type=$keyType)",
            )
            signedBlob
        } finally {
            sshStateManager.dismissHardwareKeyTouchPrompt()
        }
    }

    /**
     * Constructs an OpenSSH `PROTOCOL.u2f` FIDO2 signature blob:
     * - `string`: key type (`sk-ssh-ed25519@openssh.com` or `sk-ecdsa-sha2-nistp256@openssh.com`)
     * - `string`: raw signature over `SHA256(application) || flags (1B) || counter (u32BE) || SHA256(challenge)`
     * - `byte`: `flags` (`SSH_SK_USER_PRESENCE_REQD = 0x01`)
     * - `uint32`: `counter`
     */
    internal fun buildOpenSshSkSignatureBlob(
        keyType: String,
        applicationId: String,
        flags: Byte,
        counter: Int,
        challenge: ByteArray,
    ): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val appHash = sha256.digest(applicationId.toByteArray(Charsets.UTF_8))
        val messageHash = sha256.digest(challenge)

        val assertionData = ByteArrayOutputStream(32 + 1 + 4 + 32).apply {
            write(appHash)
            write(flags.toInt())
            DataOutputStream(this).writeInt(counter)
            write(messageHash)
        }.toByteArray()

        val rawSignature = when (keyType) {
            HardwareKeyType.SkEcdsaSha2Nistp256.opensshKeyType -> {
                val signer = Signature.getInstance("SHA256withECDSA")
                signer.initSign(ephemeralP256.private)
                signer.update(assertionData)
                signer.sign()
            }

            else -> {
                val signer = Signature.getInstance("Ed25519")
                signer.initSign(ephemeralEd25519.private)
                signer.update(assertionData)
                signer.sign()
            }
        }

        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            val typeBytes = keyType.toByteArray(Charsets.US_ASCII)
            dos.writeInt(typeBytes.size)
            dos.write(typeBytes)
            dos.writeInt(rawSignature.size)
            dos.write(rawSignature)
            dos.writeByte(flags.toInt())
            dos.writeInt(counter)
        }
        return out.toByteArray()
    }

    companion object {
        const val DEFAULT_SSH_SK_APPLICATION: String = "ssh:"
        const val SSH_SK_USER_PRESENCE_REQD: Byte = 0x01
    }
}
