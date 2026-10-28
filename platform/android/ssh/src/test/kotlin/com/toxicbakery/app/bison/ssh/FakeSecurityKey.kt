package com.toxicbakery.app.bison.ssh

import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.util.Callback
import com.yubico.yubikit.fido.Cbor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import com.yubico.yubikit.core.util.Result as YubiResult

/**
 * A software FIDO2 authenticator for JVM tests. It answers the CTAP2 commands `getInfo`, `makeCredential`, and
 * `getAssertion` with real Ed25519 and P-256 keys, behind a [FidoConnection] that sends and receives the CTAPHID
 * frames of a USB key. Thus a test runs the real yubikit `Ctap2Session` and the real wire format of
 * [YubiKeyFido2Signer].
 *
 * [touch] decides what the key does when a command needs the touch of the user.
 */
internal class FakeSecurityKey {

    /** What the key does when a command needs the touch of the user. */
    enum class Touch {
        /** The user touches the key at once. */
        Immediate,

        /** The user does not touch the key. The key answers `CTAP2_ERR_USER_ACTION_TIMEOUT` at once. */
        Never,

        /** The key sends keepalives until the host cancels the command. It then answers `CTAP2_ERR_KEEPALIVE_CANCEL`. */
        UntilCancel,
    }

    @Volatile
    var touch: Touch = Touch.Immediate

    /** Completes when a command waits for the touch in [Touch.UntilCancel]. */
    val waitingForTouch = CompletableDeferred<Unit>()

    /** True after the host sent `CTAPHID_CANCEL`. */
    @Volatile
    var cancelReceived: Boolean = false
        private set

    /** The CTAP2 command bytes in the order that the key got them. */
    val commands: MutableList<Int> = CopyOnWriteArrayList()

    /** The parameters of the last `makeCredential` and `getAssertion` commands. */
    @Volatile
    var lastMakeCredential: Map<Int, Any?> = emptyMap()
        private set

    @Volatile
    var lastGetAssertion: Map<Int, Any?> = emptyMap()
        private set

    private val credentials = ConcurrentHashMap<String, Credential>()
    private val signCounter = AtomicInteger(INITIAL_COUNTER)
    private val random = SecureRandom()

    /** Returns a device that the discovery can give to [SecurityKeyHub]. */
    fun device(transport: Transport = Transport.USB): YubiKeyDevice = Device(transport)

    /** Answers one CTAP2 request: the command byte and the CBOR parameters. */
    private fun handleCbor(request: ByteArray): ByteArray {
        val command = request[0].toInt()
        commands += command
        @Suppress("UNCHECKED_CAST")
        val params = if (request.size > 1) Cbor.decode(request, 1, request.size - 1) as Map<Int, Any?> else emptyMap()
        return when (command) {
            CMD_GET_INFO -> ok(
                mapOf(
                    1 to listOf("U2F_V2", "FIDO_2_0", "FIDO_2_1_PRE"),
                    3 to AAGUID,
                    4 to mapOf("rk" to true, "up" to true, "plat" to false),
                    5 to MAX_MESSAGE_SIZE,
                ),
            )
            CMD_MAKE_CREDENTIAL -> makeCredential(params)
            CMD_GET_ASSERTION -> getAssertion(params)
            else -> byteArrayOf(ERR_INVALID_COMMAND)
        }
    }

    private fun makeCredential(params: Map<Int, Any?>): ByteArray {
        lastMakeCredential = params
        @Suppress("UNCHECKED_CAST")
        val rpId = (params[2] as Map<String, Any?>)["id"] as String
        @Suppress("UNCHECKED_CAST")
        val algorithms = (params[4] as List<Map<String, Any?>>).map { (it["alg"] as Number).toInt() }
        val algorithm = algorithms.firstOrNull { it == COSE_EDDSA || it == COSE_ES256 }
            ?: return byteArrayOf(ERR_UNSUPPORTED_ALGORITHM)
        val credentialId = ByteArray(CREDENTIAL_ID_LENGTH).also(random::nextBytes)
        val credential = if (algorithm == COSE_EDDSA) ed25519Credential(rpId) else p256Credential(rpId)
        credentials[credentialId.toHex()] = credential
        val authenticatorData = ByteBuffer.allocate(1024)
            .put(sha256(rpId.toByteArray()))
            .put(FLAG_USER_PRESENT_AND_ATTESTED)
            .putInt(0)
            .put(AAGUID)
            .putShort(credentialId.size.toShort())
            .put(credentialId)
            .put(Cbor.encode(credential.coseKey))
        return ok(mapOf(1 to "none", 2 to authenticatorData.toArray(), 3 to emptyMap<String, Any>()))
    }

    private fun getAssertion(params: Map<Int, Any?>): ByteArray {
        lastGetAssertion = params
        val rpId = params[1] as String
        val clientDataHash = params[2] as ByteArray
        @Suppress("UNCHECKED_CAST")
        val allowList = params[3] as? List<Map<String, Any?>>
        val match = allowList.orEmpty()
            .map { it["id"] as ByteArray }
            .firstOrNull { id -> credentials[id.toHex()]?.rpId == rpId }
            ?: return byteArrayOf(ERR_NO_CREDENTIALS)
        val credential = credentials.getValue(match.toHex())
        val authenticatorData = ByteBuffer.allocate(AUTHENTICATOR_DATA_LENGTH)
            .put(sha256(rpId.toByteArray()))
            .put(FLAG_USER_PRESENT)
            .putInt(signCounter.incrementAndGet())
            .array()
        val signature = credential.sign(authenticatorData + clientDataHash)
        return ok(
            mapOf(
                1 to mapOf("type" to "public-key", "id" to match),
                2 to authenticatorData,
                3 to signature,
            ),
        )
    }

    private fun ed25519Credential(rpId: String): Credential {
        val privateKey = Ed25519PrivateKeyParameters(random)
        val publicKey = privateKey.generatePublicKey().encoded
        return Credential(
            rpId = rpId,
            coseKey = mapOf(1 to 1, 3 to COSE_EDDSA, -1 to 6, -2 to publicKey),
        ) { message ->
            Ed25519Signer().run {
                init(true, privateKey)
                update(message, 0, message.size)
                generateSignature()
            }
        }
    }

    private fun p256Credential(rpId: String): Credential {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val point = (keyPair.public as ECPublicKey).w
        return Credential(
            rpId = rpId,
            coseKey = mapOf(
                1 to 2,
                3 to COSE_ES256,
                -1 to 1,
                -2 to point.affineX.toFixedBytes(),
                -3 to point.affineY.toFixedBytes(),
            ),
        ) { message ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(message)
                sign()
            }
        }
    }

    private class Credential(
        val rpId: String,
        val coseKey: Map<Int, Any>,
        val sign: (ByteArray) -> ByteArray,
    )

    private inner class Device(private val transport: Transport) : YubiKeyDevice {
        override fun getTransport(): Transport = transport

        override fun supportsConnection(connectionType: Class<out YubiKeyConnection>): Boolean =
            connectionType.isAssignableFrom(Connection::class.java)

        override fun <T : YubiKeyConnection> requestConnection(
            connectionType: Class<T>,
            callback: Callback<YubiResult<T, IOException>>,
        ) {
            callback.invoke(YubiResult.success(openConnection(connectionType)))
        }

        override fun <T : YubiKeyConnection> openConnection(connectionType: Class<T>): T =
            checkNotNull(connectionType.cast(Connection()))
    }

    /** The CTAPHID frames of a USB key (64-byte packets, one channel). */
    private inner class Connection : FidoConnection {
        private val responses = ArrayDeque<ByteArray>()
        private var request: PendingRequest? = null
        private var heldChannel: Int? = null

        override fun send(packet: ByteArray) {
            val buffer = ByteBuffer.wrap(packet)
            val channel = buffer.getInt()
            val first = buffer.get().toInt() and 0xff
            if (first and TYPE_INIT != 0) {
                val length = buffer.getShort().toInt() and 0xffff
                if (first == CTAPHID_CANCEL) {
                    cancelReceived = true
                    return
                }
                request = PendingRequest(channel, first, length).also { it.append(packet, INIT_HEADER) }
            } else {
                checkNotNull(request) { "A continuation packet came with no request" }.append(packet, CONT_HEADER)
            }
            val complete = request?.takeIf { it.isComplete } ?: return
            request = null
            handle(complete)
        }

        override fun receive(packet: ByteArray) {
            val held = heldChannel
            if (held != null && responses.isEmpty()) {
                if (cancelReceived) {
                    heldChannel = null
                    queue(held, CTAPHID_CBOR, byteArrayOf(ERR_KEEPALIVE_CANCEL))
                } else {
                    Thread.sleep(KEEPALIVE_INTERVAL_MS)
                    frame(held, CTAPHID_KEEPALIVE, byteArrayOf(STATUS_UPNEEDED)).first().copyInto(packet)
                    return
                }
            }
            val frame = responses.removeFirstOrNull() ?: throw IOException("The fake key has no response")
            frame.copyInto(packet)
        }

        override fun close() {
            // Nothing to close.
        }

        private fun handle(request: PendingRequest) {
            when (request.command) {
                CTAPHID_INIT -> queue(
                    BROADCAST_CHANNEL,
                    CTAPHID_INIT,
                    request.data() + ByteBuffer.allocate(Int.SIZE_BYTES).putInt(CHANNEL).array() +
                        byteArrayOf(CTAPHID_PROTOCOL, 5, 4, 3, CAPABILITY_WINK_AND_CBOR),
                )
                CTAPHID_CBOR -> {
                    val data = request.data()
                    val needsTouch = data[0].toInt() == CMD_MAKE_CREDENTIAL || data[0].toInt() == CMD_GET_ASSERTION
                    when {
                        !needsTouch || touch == Touch.Immediate -> queue(request.channel, CTAPHID_CBOR, handleCbor(data))
                        touch == Touch.Never -> {
                            commands += data[0].toInt()
                            queue(request.channel, CTAPHID_CBOR, byteArrayOf(ERR_USER_ACTION_TIMEOUT))
                        }
                        else -> {
                            commands += data[0].toInt()
                            heldChannel = request.channel
                            waitingForTouch.complete(Unit)
                        }
                    }
                }
                else -> queue(request.channel, CTAPHID_ERROR, byteArrayOf(1))
            }
        }

        private fun queue(channel: Int, command: Int, payload: ByteArray) {
            responses.addAll(frame(channel, command, payload))
        }
    }

    /** One CTAPHID request: the channel, the command, and the payload of the packets so far. */
    private class PendingRequest(val channel: Int, val command: Int, private val length: Int) {
        private val payload = ByteArrayOutputStream()

        val isComplete: Boolean
            get() = payload.size() >= length

        fun append(packet: ByteArray, header: Int) {
            payload.write(packet, header, minOf(length - payload.size(), packet.size - header))
        }

        fun data(): ByteArray = payload.toByteArray()
    }

    companion object {
        const val INITIAL_COUNTER = 41

        private const val PACKET_SIZE = FidoConnection.PACKET_SIZE
        private const val INIT_HEADER = 7
        private const val CONT_HEADER = 5
        private const val TYPE_INIT = 0x80
        private const val CTAPHID_INIT = 0x86
        private const val CTAPHID_CBOR = 0x90
        private const val CTAPHID_CANCEL = 0x91
        private const val CTAPHID_KEEPALIVE = 0xbb
        private const val CTAPHID_ERROR = 0xbf
        private const val CTAPHID_PROTOCOL: Byte = 2
        private const val CAPABILITY_WINK_AND_CBOR: Byte = 0x05
        private const val STATUS_UPNEEDED: Byte = 2
        private const val BROADCAST_CHANNEL = -1
        private const val CHANNEL = 0x01020304
        private const val KEEPALIVE_INTERVAL_MS = 10L

        private const val CMD_MAKE_CREDENTIAL = 0x01
        private const val CMD_GET_ASSERTION = 0x02
        private const val CMD_GET_INFO = 0x04
        private const val ERR_INVALID_COMMAND: Byte = 0x01
        private const val ERR_UNSUPPORTED_ALGORITHM: Byte = 0x26
        private const val ERR_KEEPALIVE_CANCEL: Byte = 0x2d
        private const val ERR_NO_CREDENTIALS: Byte = 0x2e
        private const val ERR_USER_ACTION_TIMEOUT: Byte = 0x2f

        private const val COSE_EDDSA = -8
        private const val COSE_ES256 = -7
        private const val FLAG_USER_PRESENT: Byte = 0x01
        private const val FLAG_USER_PRESENT_AND_ATTESTED: Byte = 0x41
        private const val AUTHENTICATOR_DATA_LENGTH = 37
        private const val CREDENTIAL_ID_LENGTH = 64
        private const val MAX_MESSAGE_SIZE = 1200
        private val AAGUID = ByteArray(16) { (0xa0 + it).toByte() }

        private fun ok(response: Map<Int, Any>): ByteArray = byteArrayOf(0) + Cbor.encode(response)

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun ByteBuffer.toArray(): ByteArray {
            val array = ByteArray(position())
            flip()
            get(array)
            return array
        }

        /** Returns the 32-byte big-endian form of a P-256 coordinate. */
        private fun java.math.BigInteger.toFixedBytes(): ByteArray {
            val bytes = toByteArray().takeLast(32).toByteArray()
            return ByteArray(32 - bytes.size) + bytes
        }

        /** Splits [payload] into CTAPHID packets for [channel]. */
        private fun frame(channel: Int, command: Int, payload: ByteArray): List<ByteArray> {
            val packets = mutableListOf<ByteArray>()
            val first = ByteBuffer.allocate(PACKET_SIZE)
                .putInt(channel)
                .put(command.toByte())
                .putShort(payload.size.toShort())
            var offset = minOf(payload.size, PACKET_SIZE - INIT_HEADER)
            first.put(payload, 0, offset)
            packets += first.array()
            var sequence = 0
            while (offset < payload.size) {
                val size = minOf(payload.size - offset, PACKET_SIZE - CONT_HEADER)
                packets += ByteBuffer.allocate(PACKET_SIZE)
                    .putInt(channel)
                    .put(sequence++.toByte())
                    .put(payload, offset, size)
                    .array()
                offset += size
            }
            return packets
        }
    }
}
