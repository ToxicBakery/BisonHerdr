package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.FeatureFlags
import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.SshAuthMethod

/**
 * Makes and uses FIDO2 credentials on a hardware security key for the OpenSSH `sk` key types
 * (`sk-ssh-ed25519@openssh.com` and `sk-ecdsa-sha2-nistp256@openssh.com`) over USB-C and NFC.
 *
 * While a call waits for the key and for the touch of the user, it puts a [SshRequest.PromptHardwareKeyTouch] in
 * [SshStateManager.hardwareKeyTouchPrompt]. The UI shows the touch sheet for it. When the user closes the sheet, the
 * call stops with [HardwareKeyException.Reason.Cancelled]. The call removes the prompt before it returns.
 *
 * The platform layer supplies the implementation. The app uses it only when [FeatureFlags.hardwareSecurityKeys] is
 * true. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds FIDO2 security keys.
 */
interface HardwareKeySigner {

    /**
     * Makes a new non-discoverable credential of [keyType] for the relying party [applicationId] on the key, and
     * returns the enrolled [SshAuthMethod.HardwareSecurityKey]. [transport] limits the key connections that the
     * call accepts. The key does not keep a copy of the credential, so the returned value is the only copy of the
     * credential ID. Throws a [HardwareKeyException] when the enrollment fails.
     */
    suspend fun enroll(
        machineId: String,
        keyType: HardwareKeyType,
        transport: HardwareKeyTransport,
        applicationId: String,
    ): SshAuthMethod.HardwareSecurityKey

    /**
     * Signs [data] with the credential of [key] and returns the OpenSSH `PROTOCOL.u2f` signature blob:
     * `string` key type, `string` signature, `byte` flags, and `uint32` counter. Throws a [HardwareKeyException]
     * when the key cannot sign.
     */
    suspend fun sign(
        machineId: String,
        key: SshAuthMethod.HardwareSecurityKey,
        data: ByteArray,
    ): ByteArray
}

/**
 * A [HardwareKeySigner] call failed. [reason] tells the kind, and the message tells the user what to do.
 *
 * It is a [RuntimeException], because JSch lets a [RuntimeException] from an `Identity` go through
 * `Session.connect`. JSch changes a checked exception to a general "Auth fail" error.
 */
class HardwareKeyException(
    val reason: Reason,
    detail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(messageFor(reason, detail), cause) {

    /** The kinds of security key failures. */
    enum class Reason {
        /** No activity was in the foreground, so the app could not find a key or ask for a touch. */
        NoUi,

        /** No key came over USB or NFC before the time limit. */
        NoKey,

        /** The user closed the touch sheet, or the key cancelled the request. */
        Cancelled,

        /** The user did not touch the key before the time limit of the key. */
        NotTouched,

        /** The key does not have the credential of the machine. */
        WrongKey,

        /** The key needs its PIN or a user verification. This version cannot send a PIN. */
        PinRequired,

        /** The machine has no enrolled credential. */
        NotEnrolled,

        /** The key type is not a FIDO2 `sk` type, or the key does not support the algorithm. */
        NotSupported,

        /** A different error of the key or of its connection. */
        Failed,
    }

    companion object {
        /** Returns the text that the debug log and the connect error show for [reason]. */
        fun messageFor(reason: Reason, detail: String? = null): String {
            val text = when (reason) {
                Reason.NoUi ->
                    "The app is in the background, so it cannot use the security key. Open the app and connect again."
                Reason.NoKey ->
                    "No security key came before the time limit. Connect the key to the USB-C port, or hold the " +
                        "key against the device."
                Reason.Cancelled -> "The security key request was cancelled."
                Reason.NotTouched -> "The user did not touch the security key before the time limit."
                Reason.WrongKey ->
                    "This security key does not have the credential of this machine. Use the key that you enrolled."
                Reason.PinRequired -> "The security key needs its PIN. This version cannot send a PIN."
                Reason.NotEnrolled ->
                    "This machine has no enrolled security key. Open the machine settings and enroll the key."
                Reason.NotSupported -> "The security key or the key type is not supported."
                Reason.Failed -> "The security key request failed."
            }
            return if (detail.isNullOrBlank()) text else "$text ($detail)"
        }
    }
}
