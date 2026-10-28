package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.webauthn.AuthenticatorData
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The [HardwareKeySigner] for a YubiKey over USB-C and NFC, with
 * [yubikit-android](https://github.com/Yubico/yubikit-android) `Ctap2Session`. The USB discovery of the app accepts
 * only YubiKeys. Other FIDO2 keys can work over NFC, but nobody tested them.
 *
 * - [enroll] runs `makeCredential` for the relying party `ssh:` (or the application of the machine). The credential
 *   is not discoverable. The app sends no PIN. A key with a FIDO2 PIN can refuse the request with
 *   `CTAP2_ERR_PIN_REQUIRED` ([HardwareKeyException.Reason.PinRequired]), for example a YubiKey 5 with firmware
 *   before 5.7.
 * - [sign] runs `getAssertions(application, SHA256(data), allowList = [credentialId])`. The authenticator data gives
 *   the flags and the counter of the OpenSSH signature blob ([SecurityKeyWireFormat]). A login needs only the touch,
 *   not the PIN.
 *
 * Calls run one at a time. A call waits for its turn, and [SshStateManager.cancelUserAuth] for its machine (for
 * example a disconnect) stops the wait. A call needs a visible activity ([SecurityKeyHub.hasHost]). It puts a
 * [SshRequest.PromptHardwareKeyTouch] in [SshStateManager], waits for a key from [SecurityKeyHub] for [keyWaitTimeout]
 * at most, and then waits for the touch. The key has its own time limit for the touch. When the user closes the
 * touch sheet, the call cancels the request on the key and stops with [HardwareKeyException.Reason.Cancelled].
 */
class YubiKeyFido2Signer(
    private val hub: SecurityKeyHub,
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
    private val keyWaitTimeout: Duration = KEY_WAIT_TIMEOUT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
) : HardwareKeySigner {

    private val operationLock = Mutex()

    override suspend fun enroll(
        machineId: String,
        keyType: HardwareKeyType,
        transport: HardwareKeyTransport,
        applicationId: String,
    ): SshAuthMethod.HardwareSecurityKey {
        val algorithm = SecurityKeyWireFormat.coseAlgorithmOf(keyType)
        val application = applicationId.ifBlank { SshAuthMethod.HardwareSecurityKey.DEFAULT_APPLICATION_ID }
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = "Enroll a ${keyType.opensshKeyType} credential for $application",
        )
        val credential = runWithKey(machineId, keyType, transport, ENROLL_PROMPT) { session, state ->
            // The server does not check the attestation, so the challenge is only random data.
            val challenge = ByteArray(CHALLENGE_LENGTH).also(random::nextBytes)
            session.makeCredential(
                SecurityKeyWireFormat.sha256(challenge),
                mapOf("id" to application),
                mapOf("id" to ByteArray(USER_ID_LENGTH), "name" to USER_NAME, "displayName" to USER_NAME),
                listOf(mapOf("type" to PUBLIC_KEY_TYPE, "alg" to algorithm)),
                null,
                null,
                null,
                null,
                null,
                null,
                state,
            )
        }
        val authenticatorData = try {
            AuthenticatorData.parseFrom(ByteBuffer.wrap(credential.authenticatorData))
        } catch (error: RuntimeException) {
            throw HardwareKeyException(HardwareKeyException.Reason.Failed, error.message, error)
        }
        val attested = authenticatorData.attestedCredentialData
            ?: throw HardwareKeyException(HardwareKeyException.Reason.Failed, "The key sent no credential data")
        val publicKey = SecurityKeyWireFormat.publicKeyFromCose(keyType, attested.cosePublicKey)
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = "The security key made a ${keyType.opensshKeyType} credential for $application",
        )
        return SshAuthMethod.HardwareSecurityKey(
            keyType = keyType,
            transport = transport,
            applicationId = application,
            credentialId = Base64.encode(attested.credentialId),
            publicKey = Base64.encode(publicKey),
            residentKey = false,
        )
    }

    override suspend fun sign(
        machineId: String,
        key: SshAuthMethod.HardwareSecurityKey,
        data: ByteArray,
    ): ByteArray {
        if (!key.keyType.isFido2) {
            throw HardwareKeyException(HardwareKeyException.Reason.NotSupported, key.keyType.opensshKeyType)
        }
        if (!key.isEnrolled) throw HardwareKeyException(HardwareKeyException.Reason.NotEnrolled)
        val credentialId = Base64.decode(requireNotNull(key.credentialId))
        val application = key.applicationId.ifBlank { SshAuthMethod.HardwareSecurityKey.DEFAULT_APPLICATION_ID }
        val assertion = runWithKey(machineId, key.keyType, key.transport, SIGN_PROMPT) { session, state ->
            session.getAssertions(
                application,
                SecurityKeyWireFormat.sha256(data),
                listOf(mapOf("type" to PUBLIC_KEY_TYPE, "id" to credentialId)),
                null,
                null,
                null,
                null,
                state,
            ).first()
        }
        val blob = SecurityKeyWireFormat.signatureBlob(
            keyType = key.keyType,
            applicationId = application,
            authenticatorData = assertion.authenticatorData,
            signature = assertion.signature,
        )
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = "The security key signed the ${key.keyType.opensshKeyType} login",
        )
        return blob
    }

    /**
     * Shows the touch prompt, waits for a key, and runs [operation] on a CTAP2 session of the key. Converts each error
     * to a [HardwareKeyException].
     */
    private suspend fun <T> runWithKey(
        machineId: String,
        keyType: HardwareKeyType,
        transport: HardwareKeyTransport,
        message: String,
        operation: (Ctap2Session, CommandState) -> T,
    ): T {
        val owner = Any()
        try {
            lockOrCancel(machineId, owner)
        } catch (error: HardwareKeyException) {
            throw recorded(machineId, error)
        }
        try {
            return runWithLock(machineId, keyType, transport, message, operation)
        } finally {
            operationLock.unlock(owner)
        }
    }

    /**
     * Waits for the turn of a call for [machineId], and takes [operationLock] for [owner]. A cancel of [machineId] in
     * [SshStateManager.hardwareKeyCancels] during the wait stops the call with [HardwareKeyException.Reason.Cancelled].
     * Thus a call for a machine that the user disconnected does not show a prompt later.
     */
    private suspend fun lockOrCancel(machineId: String, owner: Any) {
        try {
            coroutineScope {
                // The collector starts at once, so that it gets each cancel during the wait.
                val cancelWatch = launch(start = CoroutineStart.UNDISPATCHED) {
                    sshStateManager.hardwareKeyCancels.first { it == null || it == machineId }
                    throw HardwareKeyException(HardwareKeyException.Reason.Cancelled)
                }
                operationLock.lock(owner)
                cancelWatch.cancel()
            }
        } catch (error: Throwable) {
            if (operationLock.holdsLock(owner)) operationLock.unlock(owner)
            throw error
        }
    }

    /** The part of [runWithKey] that runs while the call has [operationLock]. */
    private suspend fun <T> runWithLock(
        machineId: String,
        keyType: HardwareKeyType,
        transport: HardwareKeyTransport,
        message: String,
        operation: (Ctap2Session, CommandState) -> T,
    ): T {
        if (!hub.hasHost) throw recorded(machineId, HardwareKeyException(HardwareKeyException.Reason.NoUi))
        val prompt = SshRequest.PromptHardwareKeyTouch(
            machineId = machineId,
            keyType = keyType.opensshKeyType,
            promptMessage = message,
        )
        val commandState = CommandState()
        sshStateManager.setHardwareKeyTouchPrompt(prompt)
        return try {
            hub.withDiscovery {
                coroutineScope {
                    // The user closed the touch sheet, or a disconnect removed the prompt.
                    val closeWatch = launch {
                        try {
                            sshStateManager.hardwareKeyTouchPrompt.first { it !== prompt }
                        } finally {
                            // Also after a cancel of the caller: stop the request on the key.
                            commandState.cancel()
                        }
                        throw HardwareKeyException(HardwareKeyException.Reason.Cancelled)
                    }
                    try {
                        val device = withTimeoutOrNull(keyWaitTimeout) { hub.awaitDevice(transport) }
                            ?: throw HardwareKeyException(HardwareKeyException.Reason.NoKey)
                        sshDebugLog.record(
                            machineId = machineId,
                            tag = LOG_TAG,
                            message = "Found a security key over ${device.transport}. The app waits for the touch.",
                        )
                        withContext(ioDispatcher) {
                            runOnDevice(device) { session -> operation(session, commandState) }
                        }
                    } finally {
                        closeWatch.cancel()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HardwareKeyException) {
            throw recorded(machineId, error)
        } finally {
            sshStateManager.dismissHardwareKeyTouchPrompt(prompt)
        }
    }

    /** Opens a FIDO connection (USB) or a smart card connection (NFC) to [device] and runs [operation]. */
    private fun <T> runOnDevice(device: YubiKeyDevice, operation: (Ctap2Session) -> T): T =
        try {
            if (device.supportsConnection(FidoConnection::class.java)) {
                device.openConnection(FidoConnection::class.java).use { operation(Ctap2Session(it)) }
            } else {
                device.openConnection(SmartCardConnection::class.java).use { operation(Ctap2Session(it)) }
            }
        } catch (error: HardwareKeyException) {
            throw error
        } catch (error: Exception) {
            throw toHardwareKeyException(error)
        }

    private fun recorded(machineId: String, error: HardwareKeyException): HardwareKeyException {
        sshDebugLog.record(machineId = machineId, tag = LOG_TAG, message = error.message.orEmpty(), isError = true)
        return error
    }

    companion object {
        /** The maximum time that a call waits for a key over USB or NFC. */
        val KEY_WAIT_TIMEOUT: Duration = 60.seconds

        /** The debug log tag of the security key calls. */
        const val LOG_TAG: String = "FIDO2"

        /** The touch sheet text of [enroll]. */
        const val ENROLL_PROMPT: String =
            "Touch the security key, or hold it against the device, to enroll it for this machine."

        /** The touch sheet text of [sign]. */
        const val SIGN_PROMPT: String =
            "Touch the security key, or hold it against the device, to log in to this machine."

        /** The user name of a new credential. OpenSSH uses the same name. */
        private const val USER_NAME = "openssh"
        private const val USER_ID_LENGTH = 32
        private const val CHALLENGE_LENGTH = 32
        private const val PUBLIC_KEY_TYPE = "public-key"

        /** Converts an error of yubikit or of the key connection to a [HardwareKeyException]. */
        internal fun toHardwareKeyException(error: Exception): HardwareKeyException {
            val reason = when (error) {
                is CtapException -> when (error.ctapError) {
                    CtapException.ERR_USER_ACTION_TIMEOUT, CtapException.ERR_ACTION_TIMEOUT ->
                        HardwareKeyException.Reason.NotTouched
                    CtapException.ERR_KEEPALIVE_CANCEL, CtapException.ERR_OPERATION_DENIED ->
                        HardwareKeyException.Reason.Cancelled
                    CtapException.ERR_NO_CREDENTIALS, CtapException.ERR_INVALID_CREDENTIAL ->
                        HardwareKeyException.Reason.WrongKey
                    CtapException.ERR_PUAT_REQUIRED, CtapException.ERR_PIN_INVALID, CtapException.ERR_PIN_BLOCKED,
                    CtapException.ERR_PIN_AUTH_INVALID, CtapException.ERR_PIN_AUTH_BLOCKED,
                    CtapException.ERR_UV_BLOCKED, CtapException.ERR_UV_INVALID ->
                        HardwareKeyException.Reason.PinRequired
                    CtapException.ERR_UNSUPPORTED_ALGORITHM, CtapException.ERR_UNSUPPORTED_OPTION ->
                        HardwareKeyException.Reason.NotSupported
                    else -> HardwareKeyException.Reason.Failed
                }
                is ApplicationNotAvailableException -> HardwareKeyException.Reason.NotSupported
                else -> HardwareKeyException.Reason.Failed
            }
            val detail = (error as? CtapException)?.errorName ?: error.message ?: error::class.simpleName
            return HardwareKeyException(reason, detail, error)
        }
    }
}
