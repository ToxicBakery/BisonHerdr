package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.YubiKeyDevice
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests [YubiKeyFido2Signer] with a software FIDO2 key behind a fake CTAPHID `FidoConnection` ([FakeSecurityKey]).
 * The tests use the real yubikit `Ctap2Session`, so they test the CTAP2 requests and the OpenSSH wire format.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YubiKeyFido2SignerTest {

    private val stateManager = SshStateManager()
    private val debugLog = SshDebugLog()
    private val hub = SecurityKeyHub()
    private val key = FakeSecurityKey()
    private val discovery = RecordingDiscovery(hub)
    private val signer = YubiKeyFido2Signer(
        hub = hub,
        sshStateManager = stateManager,
        sshDebugLog = debugLog,
        keyWaitTimeout = KEY_WAIT_TIMEOUT,
    )

    @Test
    fun anEd25519KeyEnrollsAndSignsInTheOpenSshFormat() = runTest {
        hostWithUsbKey()

        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")

        assertEquals(HardwareKeyType.SkSshEd25519, enrolled.keyType)
        assertEquals("ssh:", enrolled.applicationId)
        assertFalse(enrolled.residentKey)
        assertEquals(SecurityKeyPublicKeys.ED25519_PUBLIC_KEY_LENGTH, Base64.decode(assertNotNull(enrolled.publicKey)).size)
        assertMakeCredentialRequest(expectedAlgorithm = -8)

        val data = "session id and userauth request".toByteArray()
        val blob = signer.sign(MACHINE_ID, enrolled, data)

        assertGetAssertionRequest(enrolled, data)
        assertSignatureVerifies(enrolled, data, blob, expectedCounter = FakeSecurityKey.INITIAL_COUNTER + 1)
    }

    @Test
    fun aP256KeyEnrollsAndSignsWithMpintIntegers() = runTest {
        hostWithUsbKey()

        val enrolled = signer.enroll(
            MACHINE_ID,
            HardwareKeyType.SkEcdsaSha2Nistp256,
            HardwareKeyTransport.UsbOnly,
            "ssh:bison",
        )

        assertEquals(HardwareKeyTransport.UsbOnly, enrolled.transport)
        val point = Base64.decode(assertNotNull(enrolled.publicKey))
        assertEquals(SecurityKeyPublicKeys.P256_POINT_LENGTH, point.size)
        assertEquals(0x04, point[0].toInt())
        assertMakeCredentialRequest(expectedAlgorithm = -7, applicationId = "ssh:bison")

        // Many signatures, so that some r and s values have the high bit set.
        repeat(SIGNATURE_COUNT) { index ->
            val data = "login $index".toByteArray()
            val blob = signer.sign(MACHINE_ID, enrolled, data)
            assertSignatureVerifies(enrolled, data, blob, expectedCounter = FakeSecurityKey.INITIAL_COUNTER + 1 + index)
        }
    }

    @Test
    fun theTouchPromptShowsDuringTheCallAndTheCancelButtonStopsIt() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        key.touch = FakeSecurityKey.Touch.UntilCancel

        val call = async { runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1, 2, 3)) } }
        key.waitingForTouch.await()

        val prompt = assertNotNull(stateManager.hardwareKeyTouchPrompt.value)
        assertEquals(MACHINE_ID, prompt.machineId)
        assertEquals(HardwareKeyType.SkSshEd25519.opensshKeyType, prompt.keyType)
        assertEquals(YubiKeyFido2Signer.SIGN_PROMPT, prompt.promptMessage)

        // The touch sheet calls this when the user taps the cancel button.
        stateManager.dismissHardwareKeyTouchPrompt(prompt)

        val error = assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, call.await())
        assertTrue(key.cancelReceived, "The signer did not cancel the request on the key")
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
        assertTrue(debugLog.entries.value.any { it.isError && it.message == error.message })
    }

    @Test
    fun aDisconnectOfTheMachineCancelsTheTouch() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        key.touch = FakeSecurityKey.Touch.UntilCancel

        val call = async { runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1)) } }
        key.waitingForTouch.await()
        stateManager.cancelUserAuth(MACHINE_ID)

        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, call.await())
        assertTrue(key.cancelReceived)
    }

    @Test
    fun aCancelAfterTheLastCheckStopsTheCallBeforeTheTouch() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        // The disconnect comes after the last check of the mark, and before the call puts its prompt.
        signer.stageHook = { stage ->
            if (stage == YubiKeyFido2Signer.Stage.BeforePrompt) stateManager.cancelUserAuth(MACHINE_ID)
        }

        val result = runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1)) }

        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, result)
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
        assertTrue(key.commands.none { it == CTAP_GET_ASSERTION }, "The key got a request: ${key.commands}")
    }

    @Test
    fun aCancelAfterTheTurnStopsTheCallBeforeThePrompt() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        runCurrent()
        val eventsAfterEnroll = discovery.events.toList()
        // The disconnect comes after the call got its turn, and before the check of the mark before the prompt.
        signer.stageHook = { stage ->
            if (stage == YubiKeyFido2Signer.Stage.LockTaken) stateManager.cancelUserAuth(MACHINE_ID)
        }

        val result = runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1)) }

        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, result)
        runCurrent()
        // The check before the prompt stopped the call, so the call put no prompt and did not start the discovery.
        assertEquals(eventsAfterEnroll, discovery.events)
        assertTrue(key.commands.none { it == CTAP_GET_ASSERTION }, "The key got a request: ${key.commands}")
    }

    @Test
    fun aDisconnectWhileTheConnectWaitsInTheQueueStopsTheLogin() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        // The connect request arrives, and waits in the queue. The user then disconnects the machine.
        stateManager.markConnectRequest(MACHINE_ID)
        stateManager.cancelUserAuth(MACHINE_ID)

        // The login starts after the wait, and takes the mark of the connect request.
        val loginMark = signer.cancelMark(MACHINE_ID)
        val result = runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1), loginMark) }

        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, result)
        // The mark of the connect request is for one login only. A reconnect takes the count now.
        signer.sign(MACHINE_ID, enrolled, byteArrayOf(2), signer.cancelMark(MACHINE_ID))
    }

    @Test
    fun theTouchPromptTellsWhenTheTouchIsForTheBastion() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        key.touch = FakeSecurityKey.Touch.UntilCancel

        val call = async {
            runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1), bastionHostPort = BASTION_HOST_PORT) }
        }
        key.waitingForTouch.await()

        assertEquals(BASTION_HOST_PORT, stateManager.hardwareKeyTouchPrompt.value?.bastionHostPort)
        stateManager.dismissHardwareKeyTouchPrompt()
        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, call.await())
    }

    @Test
    fun theDefaultTimeLimitsEndBeforeTheLoginGraceTimeOfOpenSsh() {
        assertEquals(
            YubiKeyFido2Signer.KEY_WAIT_TIMEOUT + YubiKeyFido2Signer.OPERATION_TIMEOUT,
            YubiKeyFido2Signer.LOGIN_TIME_LIMIT,
        )
        assertTrue(YubiKeyFido2Signer.LOGIN_TIME_LIMIT < OPENSSH_LOGIN_GRACE_TIME)
    }

    @Test
    fun aCancelBeforeTheCallStopsItWithNoPrompt() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        runCurrent()
        val eventsAfterEnroll = discovery.events.toList()
        // The login takes the mark at its start. The user then disconnects while JSch makes the TCP connection.
        val mark = signer.cancelMark(MACHINE_ID)
        stateManager.cancelUserAuth(MACHINE_ID)

        val result = runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1), mark) }

        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, result)
        runCurrent()
        // The call showed no prompt, so it did not start the discovery, and the key got no request.
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
        assertEquals(eventsAfterEnroll, discovery.events)
        assertTrue(key.commands.none { it == CTAP_GET_ASSERTION }, "The key got a request: ${key.commands}")

        // A different machine, and a new login of the same machine, still sign.
        signer.sign(OTHER_MACHINE_ID, enrolled, byteArrayOf(2), signer.cancelMark(OTHER_MACHINE_ID))
        signer.sign(MACHINE_ID, enrolled, byteArrayOf(3), signer.cancelMark(MACHINE_ID))

        // A cancel of all prompts also stops a login that started before it.
        val otherMark = signer.cancelMark(OTHER_MACHINE_ID)
        stateManager.cancelAllUserAuth()
        val all = runCatching { signer.sign(OTHER_MACHINE_ID, enrolled, byteArrayOf(4), otherMark) }
        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, all)
    }

    @Test
    fun theIdentityTakesTheCancelMarkWhenTheLoginStarts() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        // The client makes the identity before the TCP connection. The user then disconnects.
        val identity = HardwareKeyJschIdentity(
            machineId = MACHINE_ID,
            key = enrolled,
            signer = signer,
            cancelMark = signer.cancelMark(MACHINE_ID),
            bastionHostPort = null,
            callerJob = null,
        )
        stateManager.cancelUserAuth(MACHINE_ID)

        val error = assertFailsWith<HardwareKeyException> { identity.getSignature(byteArrayOf(1), null) }

        assertEquals(HardwareKeyException.Reason.Cancelled, error.reason)
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
    }

    @Test
    fun aCancelOfOneMachineDoesNotStopTheOtherWaitingCalls() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        key.touch = FakeSecurityKey.Touch.UntilCancel
        val first = async { runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1)) } }
        key.waitingForTouch.await()
        key.touch = FakeSecurityKey.Touch.Immediate

        // Two more machines wait for their turn. The user disconnects only the second machine.
        val second = async { runCatching { signer.sign(OTHER_MACHINE_ID, enrolled, byteArrayOf(2)) } }
        val third = async { runCatching { signer.sign(THIRD_MACHINE_ID, enrolled, byteArrayOf(3)) } }
        runCurrent()
        stateManager.cancelUserAuth(OTHER_MACHINE_ID)

        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, second.await())
        runCurrent()
        assertFalse(third.isCompleted, "The cancel of the second machine stopped the third machine")
        assertEquals(MACHINE_ID, stateManager.hardwareKeyTouchPrompt.value?.machineId)

        // The user closes the touch sheet of the first machine. The third machine then gets the key.
        stateManager.dismissHardwareKeyTouchPrompt()
        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, first.await())
        val blob = third.await().getOrThrow()
        assertSignatureVerifies(enrolled, byteArrayOf(3), blob, expectedCounter = FakeSecurityKey.INITIAL_COUNTER + 1)
    }

    @Test
    fun aKeyThatDoesNotAnswerStopsAtTheTimeLimit() = runTest {
        val shortSigner = YubiKeyFido2Signer(
            hub = hub,
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            keyWaitTimeout = KEY_WAIT_TIMEOUT,
            operationTimeout = SHORT_OPERATION_TIMEOUT,
        )
        hostWithUsbKey()
        val enrolled = shortSigner.enroll(
            MACHINE_ID,
            HardwareKeyType.SkSshEd25519,
            HardwareKeyTransport.UsbAndNfc,
            "ssh:",
        )
        key.touch = FakeSecurityKey.Touch.UntilCancel

        val result = runCatching { shortSigner.sign(MACHINE_ID, enrolled, byteArrayOf(1)) }

        assertHardwareKeyError(HardwareKeyException.Reason.NotTouched, result)
        assertTrue(key.cancelReceived, "The signer did not cancel the request on the key")
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
    }

    @Test
    fun theWaitForAKeyStopsWithNoUiWhenTheActivityStops() = runTest {
        val host = startHost(device = null)
        val call = async {
            runCatching {
                signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
            }
        }
        runCurrent()
        assertNotNull(stateManager.hardwareKeyTouchPrompt.value)

        // The activity stops before the user connects a key.
        host.cancel()

        assertHardwareKeyError(HardwareKeyException.Reason.NoUi, call.await())
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
    }

    @Test
    fun anApplicationWithNoSshPrefixIsRefused() = runTest {
        hostWithUsbKey()

        val enroll = runCatching {
            signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "web:example")
        }
        val sign = runCatching {
            val key = SshAuthMethod.HardwareSecurityKey(credentialId = "AAEC", publicKey = "AwQF", applicationId = "x")
            signer.sign(MACHINE_ID, key, byteArrayOf(1))
        }

        assertHardwareKeyError(HardwareKeyException.Reason.NotSupported, enroll)
        assertHardwareKeyError(HardwareKeyException.Reason.NotSupported, sign)
        assertTrue(discovery.events.isEmpty(), "The signer started the discovery: ${discovery.events}")
        assertTrue(key.commands.isEmpty())
    }

    @Test
    fun aDisconnectStopsACallThatWaitsForItsTurn() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        key.touch = FakeSecurityKey.Touch.UntilCancel
        val first = async { runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1)) } }
        key.waitingForTouch.await()

        // A login of a second machine waits for its turn. The user then disconnects the second machine.
        val second = async { runCatching { signer.sign(OTHER_MACHINE_ID, enrolled, byteArrayOf(2)) } }
        runCurrent()
        stateManager.cancelUserAuth(OTHER_MACHINE_ID)

        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, second.await())
        // The first call still waits for its touch, and the second machine never got a prompt.
        assertEquals(MACHINE_ID, stateManager.hardwareKeyTouchPrompt.value?.machineId)
        assertFalse(first.isCompleted)

        stateManager.cancelUserAuth(MACHINE_ID)
        assertHardwareKeyError(HardwareKeyException.Reason.Cancelled, first.await())
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
        assertEquals(1, key.commands.count { it == CTAP_GET_ASSERTION }, "Only the first call got to the key")

        // The lock is free again: a new call works.
        key.touch = FakeSecurityKey.Touch.Immediate
        signer.sign(OTHER_MACHINE_ID, enrolled, byteArrayOf(3))
    }

    @Test
    fun theUsbKeyStaysWhenOnlyTheNfcHostStops() = runTest {
        val usbHost = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.hostDiscovery(discovery.apply { device = key.device(Transport.USB) })
        }
        val nfcDiscovery = object : SecurityKeyDiscovery {
            override val transport: Transport = Transport.NFC

            override fun start() = Unit

            override fun stop() = Unit
        }
        // The activity pauses, for example for the USB permission dialog. The NFC host stops, the USB host stays.
        val nfcHost = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { hub.hostDiscovery(nfcDiscovery) }

        val found = hub.withDiscovery {
            runCurrent()
            nfcHost.cancel()
            runCurrent()
            assertTrue(hub.hasHost)
            assertEquals(Transport.USB, hub.awaitDevice(HardwareKeyTransport.UsbAndNfc).transport)
            "found"
        }

        assertEquals("found", found)
        usbHost.cancel()
        runCurrent()
        assertFalse(hub.hasHost)
    }

    @Test
    fun noTouchGivesNotTouched() = runTest {
        hostWithUsbKey()
        val enrolled = signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        key.touch = FakeSecurityKey.Touch.Never

        val result = runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1)) }

        assertHardwareKeyError(HardwareKeyException.Reason.NotTouched, result)
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
    }

    @Test
    fun withNoActivityTheCallStopsAtOnceAndShowsNoPrompt() = runTest {
        val enrolled = enrolledKeyFor(key)

        val result = runCatching { signer.sign(MACHINE_ID, enrolled, byteArrayOf(1)) }

        assertHardwareKeyError(HardwareKeyException.Reason.NoUi, result)
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
        assertTrue(discovery.events.isEmpty())
        assertTrue(key.commands.none { it == CTAP_GET_ASSERTION }, "The key got a request: ${key.commands}")
    }

    @Test
    fun withNoKeyTheCallStopsAfterTheTimeLimitAndStopsTheDiscovery() = runTest {
        startHost(device = null)

        val result = runCatching {
            signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        }

        assertHardwareKeyError(HardwareKeyException.Reason.NoKey, result)
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
        // The host stops the discovery when no call needs a key.
        runCurrent()
        assertEquals(listOf("start", "stop"), discovery.events)
    }

    @Test
    fun theTransportOfTheMachineLimitsTheKeys() = runTest {
        // Only a USB key is attached. An NFC-only machine waits for a tap, and no tap comes.
        hostWithUsbKey()
        val result = runCatching {
            signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.NfcOnly, "ssh:")
        }
        assertHardwareKeyError(HardwareKeyException.Reason.NoKey, result)

        // An NFC tap while the touch sheet shows gives the key.
        val call = async {
            signer.enroll(MACHINE_ID, HardwareKeyType.SkSshEd25519, HardwareKeyTransport.NfcOnly, "ssh:")
        }
        runCurrent()
        assertNotNull(stateManager.hardwareKeyTouchPrompt.value)
        hub.onNfcDevice(key.device(Transport.NFC))
        val enrolled = call.await()
        assertEquals(HardwareKeyTransport.NfcOnly, enrolled.transport)
        assertTrue(debugLog.entries.value.any { it.message.contains("over NFC") }, "No NFC entry in the debug log")
    }

    @Test
    fun aDifferentKeyGivesWrongKey() = runTest {
        hostWithUsbKey()
        // The credential of a different key.
        val otherKey = enrolledKeyFor(FakeSecurityKey())

        val result = runCatching { signer.sign(MACHINE_ID, otherKey, byteArrayOf(1)) }

        assertHardwareKeyError(HardwareKeyException.Reason.WrongKey, result)
    }

    @Test
    fun aKeyWithNoCredentialOrAPivKeyIsRefusedBeforeThePrompt() = runTest {
        hostWithUsbKey()

        val notEnrolled = runCatching { signer.sign(MACHINE_ID, SshAuthMethod.HardwareSecurityKey(), byteArrayOf(1)) }
        assertHardwareKeyError(HardwareKeyException.Reason.NotEnrolled, notEnrolled)

        val piv = runCatching {
            signer.enroll(MACHINE_ID, HardwareKeyType.PivSmartCard, HardwareKeyTransport.UsbAndNfc, "ssh:")
        }
        assertHardwareKeyError(HardwareKeyException.Reason.NotSupported, piv)
        assertTrue(discovery.events.isEmpty(), "The signer started the discovery: ${discovery.events}")
        assertTrue(key.commands.isEmpty())
    }

    @Test
    fun ctapErrorsMapToTheReasons() {
        val cases = mapOf(
            0x2f to HardwareKeyException.Reason.NotTouched,
            0x2d to HardwareKeyException.Reason.Cancelled,
            0x27 to HardwareKeyException.Reason.Cancelled,
            0x2e to HardwareKeyException.Reason.WrongKey,
            0x36 to HardwareKeyException.Reason.PinRequired,
            0x26 to HardwareKeyException.Reason.NotSupported,
            0x7f to HardwareKeyException.Reason.Failed,
        )
        for ((code, reason) in cases) {
            val error = com.yubico.yubikit.core.fido.CtapException(code.toByte())
            assertEquals(reason, YubiKeyFido2Signer.toHardwareKeyException(error).reason, "0x${code.toString(16)}")
        }
        val ioError = YubiKeyFido2Signer.toHardwareKeyException(java.io.IOException("USB transfer failed"))
        assertEquals(HardwareKeyException.Reason.Failed, ioError.reason)
        assertTrue(ioError.message.orEmpty().contains("USB transfer failed"))
    }

    /** Starts a host whose discovery gives the USB key of [key] at the start. */
    private fun TestScope.hostWithUsbKey(): Job = startHost(key.device(Transport.USB))

    /** Starts a host of [hub], as the visible activity does. [device] comes at each start of the discovery. */
    private fun TestScope.startHost(device: YubiKeyDevice?): Job {
        discovery.device = device
        return backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { hub.hostDiscovery(discovery) }
    }

    private fun assertMakeCredentialRequest(expectedAlgorithm: Int, applicationId: String = "ssh:") {
        val request = key.lastMakeCredential
        assertEquals(32, (request[1] as ByteArray).size, "clientDataHash")
        assertEquals(mapOf("id" to applicationId), request[2], "rp")
        @Suppress("UNCHECKED_CAST")
        val user = request[3] as Map<String, Any?>
        assertContentEquals(ByteArray(32), user["id"] as ByteArray)
        assertEquals("openssh", user["name"])
        assertEquals(listOf(mapOf("type" to "public-key", "alg" to expectedAlgorithm)), request[4])
        // No resident key, no user verification, and no PIN.
        assertNull(request[7])
        assertNull(request[8])
    }

    private fun assertGetAssertionRequest(enrolled: SshAuthMethod.HardwareSecurityKey, data: ByteArray) {
        val request = key.lastGetAssertion
        assertEquals(enrolled.applicationId, request[1])
        assertContentEquals(SecurityKeyWireFormat.sha256(data), request[2] as ByteArray)
        @Suppress("UNCHECKED_CAST")
        val allowList = request[3] as List<Map<String, Any?>>
        assertEquals("public-key", allowList.single()["type"])
        assertContentEquals(Base64.decode(assertNotNull(enrolled.credentialId)), allowList.single()["id"] as ByteArray)
        assertNull(request[5])
    }

    private fun assertHardwareKeyError(
        reason: HardwareKeyException.Reason,
        result: Result<*>,
    ): HardwareKeyException {
        val error = result.exceptionOrNull()
        assertTrue(error is HardwareKeyException, "Expected $reason, got $result")
        assertEquals(reason, error.reason, error.message)
        return error
    }

    /** Checks [blob] as OpenSSH does ([OpenSshSkSignatureCheck]), and checks the counter. */
    private fun assertSignatureVerifies(
        key: SshAuthMethod.HardwareSecurityKey,
        data: ByteArray,
        blob: ByteArray,
        expectedCounter: Int,
    ) {
        assertEquals(expectedCounter, OpenSshSkSignatureCheck.check(key, data, blob).counter)
    }

    /** Enrolls a credential on [fakeKey] with a separate signer and hub. */
    private suspend fun enrolledKeyFor(fakeKey: FakeSecurityKey): SshAuthMethod.HardwareSecurityKey {
        val otherHub = SecurityKeyHub()
        val otherSigner = YubiKeyFido2Signer(otherHub, SshStateManager(), SshDebugLog(), KEY_WAIT_TIMEOUT)
        val otherDiscovery = RecordingDiscovery(otherHub).apply { device = fakeKey.device() }
        return coroutineScope {
            val host = launch(start = CoroutineStart.UNDISPATCHED) { otherHub.hostDiscovery(otherDiscovery) }
            try {
                otherSigner.enroll("other", HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
            } finally {
                host.cancel()
            }
        }
    }

    /** A USB [SecurityKeyDiscovery] of [hub] that records its calls and gives [device] at each start. */
    private class RecordingDiscovery(private val hub: SecurityKeyHub) : SecurityKeyDiscovery {
        override val transport: Transport = Transport.USB

        val events = mutableListOf<String>()

        @Volatile
        var device: YubiKeyDevice? = null

        override fun start() {
            events += "start"
            device?.let { hub.onUsbDevice(it) }
        }

        override fun stop() {
            events += "stop"
        }
    }

    private companion object {
        const val MACHINE_ID = "yubikey-box"
        const val OTHER_MACHINE_ID = "second-box"
        const val THIRD_MACHINE_ID = "third-box"
        const val BASTION_HOST_PORT = "jump.example:2222"

        /** The default `LoginGraceTime` of an OpenSSH server. */
        val OPENSSH_LOGIN_GRACE_TIME = 120.seconds
        val SHORT_OPERATION_TIMEOUT = 300.milliseconds
        const val SIGNATURE_COUNT = 24
        const val CTAP_GET_ASSERTION = 0x02
        val KEY_WAIT_TIMEOUT = 5.seconds
    }
}
