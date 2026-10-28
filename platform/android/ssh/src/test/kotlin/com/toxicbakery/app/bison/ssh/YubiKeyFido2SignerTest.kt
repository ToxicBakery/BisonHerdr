package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.YubiKeyDevice
import java.io.DataInputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

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
    private fun TestScope.hostWithUsbKey() = startHost(key.device(Transport.USB))

    /** Starts a host of [hub], as the activity does in the foreground. [device] comes at each start of the discovery. */
    private fun TestScope.startHost(device: YubiKeyDevice?) {
        discovery.device = device
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { hub.hostDiscovery(discovery) }
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

    /**
     * Checks [blob] the same way as OpenSSH: the signed message is `SHA256(application) || flags || counter ||
     * SHA256(data)`, and the user presence flag is set.
     */
    private fun assertSignatureVerifies(
        key: SshAuthMethod.HardwareSecurityKey,
        data: ByteArray,
        blob: ByteArray,
        expectedCounter: Int,
    ) {
        val input = DataInputStream(blob.inputStream())
        val type = String(ByteArray(input.readInt()).also(input::readFully))
        val signature = ByteArray(input.readInt()).also(input::readFully)
        val flags = input.readByte()
        val counter = input.readInt()
        assertEquals(0, input.available(), "The blob has extra bytes")
        assertEquals(key.keyType.opensshKeyType, type)
        assertEquals(1, flags.toInt() and 0x01, "The user presence flag is not set")
        assertEquals(expectedCounter, counter)

        val message = SecurityKeyWireFormat.sha256(key.applicationId.toByteArray()) + byteArrayOf(flags) +
            java.nio.ByteBuffer.allocate(4).putInt(counter).array() + SecurityKeyWireFormat.sha256(data)
        val publicKey = Base64.decode(assertNotNull(key.publicKey))
        val verified = when (key.keyType) {
            HardwareKeyType.SkSshEd25519 -> Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(message, 0, message.size)
                verifySignature(signature)
            }
            else -> {
                val mpints = DataInputStream(signature.inputStream())
                val r = BigInteger(ByteArray(mpints.readInt()).also(mpints::readFully))
                val s = BigInteger(ByteArray(mpints.readInt()).also(mpints::readFully))
                assertEquals(0, mpints.available(), "The ECDSA signature has extra bytes")
                val der = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).encoded
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(p256PublicKey(publicKey))
                    update(message)
                    verify(der)
                }
            }
        }
        assertTrue(verified, "The ${key.keyType} signature does not verify")
    }

    private fun p256PublicKey(point: ByteArray): java.security.PublicKey {
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        val spec = ECPublicKeySpec(
            ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))),
            parameters.getParameterSpec(ECParameterSpec::class.java),
        )
        return KeyFactory.getInstance("EC").generatePublic(spec)
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

    /** A [SecurityKeyDiscovery] of [hub] that records its calls and gives [device] at each start. */
    private class RecordingDiscovery(private val hub: SecurityKeyHub) : SecurityKeyDiscovery {
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
        const val SIGNATURE_COUNT = 24
        const val CTAP_GET_ASSERTION = 0x02
        val KEY_WAIT_TIMEOUT = 5.seconds
    }
}
