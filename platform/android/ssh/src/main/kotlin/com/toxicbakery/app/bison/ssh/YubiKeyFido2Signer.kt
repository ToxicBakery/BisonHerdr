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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
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
 * Calls run one at a time. A login takes a cancel mark at its start ([cancelMark]). A cancel of its machine after the
 * mark ([SshStateManager.cancelUserAuth], for example a disconnect) stops the call before its turn, during the wait for
 * its turn, before the prompt, and while the prompt shows. A call needs a visible activity ([SecurityKeyHub.hasHost]).
 * It puts a [SshRequest.PromptHardwareKeyTouch] in [SshStateManager], and waits for a key from [SecurityKeyHub] for
 * [keyWaitTimeout] at most. When no activity is visible any more, the wait stops with
 * [HardwareKeyException.Reason.NoUi]. The request on the key, with the touch, has the time limit [operationTimeout].
 * With the default limits, a call takes [LOGIN_TIME_LIMIT] at most. When the user closes the touch sheet, the call
 * cancels the request on the key and stops with [HardwareKeyException.Reason.Cancelled].
 */
class YubiKeyFido2Signer(
    private val hub: SecurityKeyHub,
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
    private val keyWaitTimeout: Duration = KEY_WAIT_TIMEOUT,
    private val operationTimeout: Duration = OPERATION_TIMEOUT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
) : HardwareKeySigner {

    private val operationLock = Mutex()

    /** A test seam: the call runs it at each [Stage], so that a test can put a cancel at that point. */
    internal var stageHook: (Stage) -> Unit = {}

    /** The points of a call where [stageHook] runs. */
    internal enum class Stage {
        /** The call has its turn and did not check the cancel mark yet. */
        LockTaken,

        /** The call checked the cancel mark and did not put its prompt yet. */
        BeforePrompt,
    }

    override fun cancelMark(machineId: String): Long = sshStateManager.takeLoginCancelMark(machineId)

    /** Returns the count of [machineId] in [SshStateManager.userAuthCancelCounts] now. */
    private fun currentMark(machineId: String): Long = sshStateManager.userAuthCancelCounts.value.of(machineId)

    override suspend fun enroll(
        machineId: String,
        keyType: HardwareKeyType,
        transport: HardwareKeyTransport,
        applicationId: String,
    ): SshAuthMethod.HardwareSecurityKey {
        val mark = currentMark(machineId)
        val algorithm = SecurityKeyWireFormat.coseAlgorithmOf(keyType)
        val application = checkedApplication(applicationId)
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = "Enroll a ${keyType.opensshKeyType} credential for $application",
        )
        val credential = runWithKey(machineId, mark, keyType, transport, ENROLL_PROMPT, null) { session, state ->
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
        cancelMark: Long?,
        bastionHostPort: String?,
    ): ByteArray {
        val mark = cancelMark ?: currentMark(machineId)
        if (!key.keyType.isFido2) {
            throw HardwareKeyException(HardwareKeyException.Reason.NotSupported, key.keyType.opensshKeyType)
        }
        if (!key.isEnrolled) throw HardwareKeyException(HardwareKeyException.Reason.NotEnrolled)
        val credentialId = Base64.decode(requireNotNull(key.credentialId))
        val application = checkedApplication(key.applicationId)
        val assertion = runWithKey(
            machineId,
            mark,
            key.keyType,
            key.transport,
            SIGN_PROMPT,
            bastionHostPort,
        ) { session, state ->
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
     * to a [HardwareKeyException]. [mark] is the cancel mark of [machineId] at the start of the login. A cancel of the
     * machine after the mark stops the call before the wait for its turn, during that wait, before the prompt, and
     * while the prompt shows. [bastionHostPort] goes into the prompt.
     */
    private suspend fun <T> runWithKey(
        machineId: String,
        mark: Long,
        keyType: HardwareKeyType,
        transport: HardwareKeyTransport,
        message: String,
        bastionHostPort: String?,
        operation: (Ctap2Session, CommandState) -> T,
    ): T {
        val owner = Any()
        try {
            checkNotCancelled(machineId, mark)
            lockOrCancel(machineId, mark, owner)
        } catch (error: HardwareKeyException) {
            throw recorded(machineId, error)
        }
        try {
            stageHook(Stage.LockTaken)
            return runWithLock(machineId, mark, keyType, transport, message, bastionHostPort, operation)
        } finally {
            operationLock.unlock(owner)
        }
    }

    /** Throws [HardwareKeyException.Reason.Cancelled] when [machineId] got a cancel after [mark]. */
    private fun checkNotCancelled(machineId: String, mark: Long) {
        if (currentMark(machineId) != mark) throw HardwareKeyException(HardwareKeyException.Reason.Cancelled)
    }

    /**
     * Waits for the turn of a call for [machineId], and takes [operationLock] for [owner]. When [machineId] gets a
     * cancel after [mark] in [SshStateManager.userAuthCancelCounts], the wait stops with
     * [HardwareKeyException.Reason.Cancelled]. The counts keep each cancel, so a cancel before the wait also counts.
     * Thus a call for a machine that the user disconnected does not show a prompt later.
     */
    private suspend fun lockOrCancel(machineId: String, mark: Long, owner: Any) {
        try {
            coroutineScope {
                val cancelWatch = launch(start = CoroutineStart.UNDISPATCHED) {
                    sshStateManager.userAuthCancelCounts.first { counts -> counts.of(machineId) != mark }
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
        mark: Long,
        keyType: HardwareKeyType,
        transport: HardwareKeyTransport,
        message: String,
        bastionHostPort: String?,
        operation: (Ctap2Session, CommandState) -> T,
    ): T {
        try {
            checkNotCancelled(machineId, mark)
        } catch (error: HardwareKeyException) {
            throw recorded(machineId, error)
        }
        if (!hub.hasHost) throw recorded(machineId, HardwareKeyException(HardwareKeyException.Reason.NoUi))
        val prompt = SshRequest.PromptHardwareKeyTouch(
            machineId = machineId,
            keyType = keyType.opensshKeyType,
            promptMessage = message,
            bastionHostPort = bastionHostPort,
        )
        val commandState = CommandState()
        stageHook(Stage.BeforePrompt)
        sshStateManager.setHardwareKeyTouchPrompt(prompt)
        return try {
            hub.withDiscovery {
                coroutineScope {
                    // The user closed the touch sheet, or a cancel of the machine came after the mark. A cancel can
                    // come after the last check and before the prompt, so the watch also reads the cancel counts. The
                    // watch starts at once, so that such a cancel stops the call before the request on the key.
                    val closeWatch = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            val open = sshStateManager.hardwareKeyTouchPrompt.value === prompt &&
                                currentMark(machineId) == mark
                            if (open) {
                                merge(
                                    sshStateManager.hardwareKeyTouchPrompt.filter { it !== prompt },
                                    sshStateManager.userAuthCancelCounts.filter { it.of(machineId) != mark },
                                ).first()
                            }
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
                        runWithTimeLimit(commandState) {
                            withContext(ioDispatcher) {
                                runOnDevice(device) { session -> operation(session, commandState) }
                            }
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

    /**
     * Runs [block], the request on the key, for [operationTimeout] at most. At the time limit, the function cancels the
     * request on the key with [commandState] and throws [HardwareKeyException.Reason.NotTouched]. The time limit uses
     * real time on [ioDispatcher], because the request blocks a thread.
     */
    private suspend fun <T> runWithTimeLimit(commandState: CommandState, block: suspend () -> T): T = coroutineScope {
        val timedOut = AtomicBoolean(false)
        val timeLimit = launch(ioDispatcher) {
            delay(operationTimeout)
            timedOut.set(true)
            commandState.cancel()
        }
        try {
            block()
        } catch (error: HardwareKeyException) {
            if (!timedOut.get()) throw error
            throw HardwareKeyException(
                HardwareKeyException.Reason.NotTouched,
                "The key did not answer in ${operationTimeout.inWholeSeconds} s",
                error,
            )
        } finally {
            timeLimit.cancel()
        }
    }

    /**
     * Returns [applicationId], or the default `ssh:` when it is blank. Throws [HardwareKeyException.Reason.NotSupported]
     * when it does not start with `ssh:`, because OpenSSH (`ssh-keygen`) needs that prefix.
     */
    private fun checkedApplication(applicationId: String): String {
        val application = applicationId.ifBlank { SshAuthMethod.HardwareSecurityKey.DEFAULT_APPLICATION_ID }
        if (!application.startsWith(SshAuthMethod.HardwareSecurityKey.DEFAULT_APPLICATION_ID)) {
            throw HardwareKeyException(
                HardwareKeyException.Reason.NotSupported,
                "The application \"$application\" does not start with ssh:",
            )
        }
        return application
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

        /**
         * The maximum time of the request on the key, after the call found the key. It includes the touch. A YubiKey
         * stops its own wait for the touch after about 30 s.
         */
        val OPERATION_TIMEOUT: Duration = 40.seconds

        /**
         * The maximum time of a call with the default limits: [KEY_WAIT_TIMEOUT] and [OPERATION_TIMEOUT]. It is less
         * than 120 s, the default `LoginGraceTime` of an OpenSSH server, so the server does not close the connection
         * during the wait.
         */
        val LOGIN_TIME_LIMIT: Duration = KEY_WAIT_TIMEOUT + OPERATION_TIMEOUT

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
