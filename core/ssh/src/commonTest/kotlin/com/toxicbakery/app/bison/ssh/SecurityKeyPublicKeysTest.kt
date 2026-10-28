package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.SshAuthMethod
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Tests the OpenSSH public key of an enrolled security key ([SecurityKeyPublicKeys]) and the touch prompt state. */
class SecurityKeyPublicKeysTest {

    private val ed25519Key = ByteArray(SecurityKeyPublicKeys.ED25519_PUBLIC_KEY_LENGTH) { (it + 1).toByte() }
    private val p256Point = ByteArray(SecurityKeyPublicKeys.P256_POINT_LENGTH) { if (it == 0) 0x04 else it.toByte() }

    @Test
    fun ed25519BlobHasTheTypeTheKeyAndTheApplication() {
        val key = enrolled(HardwareKeyType.SkSshEd25519, ed25519Key, applicationId = "ssh:")

        val expected = sshString("sk-ssh-ed25519@openssh.com") + sshString(ed25519Key) + sshString("ssh:")
        assertContentEquals(expected, SecurityKeyPublicKeys.blob(key))
        assertEquals(
            "sk-ssh-ed25519@openssh.com ${Base64.encode(expected)} ${SecurityKeyPublicKeys.DEFAULT_COMMENT}",
            SecurityKeyPublicKeys.authorizedKeyLine(key),
        )
    }

    @Test
    fun p256BlobHasTheTypeTheCurveThePointAndTheApplication() {
        val key = enrolled(HardwareKeyType.SkEcdsaSha2Nistp256, p256Point, applicationId = "ssh:bison")

        val expected = sshString("sk-ecdsa-sha2-nistp256@openssh.com") + sshString("nistp256") +
            sshString(p256Point) + sshString("ssh:bison")
        assertContentEquals(expected, SecurityKeyPublicKeys.blob(key))
        assertEquals(
            "sk-ecdsa-sha2-nistp256@openssh.com ${Base64.encode(expected)} laptop",
            SecurityKeyPublicKeys.authorizedKeyLine(key, comment = "laptop"),
        )
    }

    @Test
    fun aKeyWithNoPublicKeyIsNotEnrolled() {
        val error = assertFailsWith<HardwareKeyException> {
            SecurityKeyPublicKeys.blob(SshAuthMethod.HardwareSecurityKey())
        }

        assertEquals(HardwareKeyException.Reason.NotEnrolled, error.reason)
        assertEquals(HardwareKeyException.messageFor(HardwareKeyException.Reason.NotEnrolled), error.message)
    }

    @Test
    fun aPivKeyAndIncorrectKeyLengthsAreRefused() {
        val piv = assertFailsWith<HardwareKeyException> {
            SecurityKeyPublicKeys.blob(enrolled(HardwareKeyType.PivSmartCard, ed25519Key))
        }
        assertEquals(HardwareKeyException.Reason.NotSupported, piv.reason)

        assertFailsWith<IllegalArgumentException> {
            SecurityKeyPublicKeys.blob(enrolled(HardwareKeyType.SkSshEd25519, p256Point))
        }
        assertFailsWith<IllegalArgumentException> {
            SecurityKeyPublicKeys.blob(enrolled(HardwareKeyType.SkEcdsaSha2Nistp256, ed25519Key))
        }
        // A compressed point (prefix 0x02) is not an uncompressed point.
        val compressed = p256Point.copyOf().also { it[0] = 0x02 }
        assertFailsWith<IllegalArgumentException> {
            SecurityKeyPublicKeys.blob(enrolled(HardwareKeyType.SkEcdsaSha2Nistp256, compressed))
        }
    }

    @Test
    fun aCancelOfTheMachineRemovesOnlyItsTouchPrompt() {
        val stateManager = SshStateManager()
        val prompt = SshRequest.PromptHardwareKeyTouch("workbox", "sk-ssh-ed25519@openssh.com", "Touch the key")

        stateManager.setHardwareKeyTouchPrompt(prompt)
        stateManager.cancelUserAuth("gpu-rig")
        assertSame(prompt, stateManager.hardwareKeyTouchPrompt.value)

        // An equal prompt that is a different instance does not remove the open prompt.
        stateManager.dismissHardwareKeyTouchPrompt(prompt.copy())
        assertSame(prompt, stateManager.hardwareKeyTouchPrompt.value)

        stateManager.cancelUserAuth("workbox")
        assertNull(stateManager.hardwareKeyTouchPrompt.value)

        stateManager.setHardwareKeyTouchPrompt(prompt)
        stateManager.dismissHardwareKeyTouchPrompt(prompt)
        assertNull(stateManager.hardwareKeyTouchPrompt.value)

        stateManager.setHardwareKeyTouchPrompt(prompt)
        stateManager.cancelAllUserAuth()
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
    }

    private fun enrolled(
        keyType: HardwareKeyType,
        publicKey: ByteArray,
        applicationId: String = "ssh:",
    ): SshAuthMethod.HardwareSecurityKey = SshAuthMethod.HardwareSecurityKey(
        keyType = keyType,
        applicationId = applicationId,
        credentialId = Base64.encode(byteArrayOf(1, 2, 3)),
        publicKey = Base64.encode(publicKey),
    )

    private fun sshString(value: String): ByteArray = sshString(value.encodeToByteArray())

    private fun sshString(value: ByteArray): ByteArray {
        val size = value.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            value
    }
}
