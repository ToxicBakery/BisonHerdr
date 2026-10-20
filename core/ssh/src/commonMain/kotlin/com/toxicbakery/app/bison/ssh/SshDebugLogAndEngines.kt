package com.toxicbakery.app.bison.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.Serializable

/**
 * Structured transport diagnostic log entry stored in [SshDebugLog].
 */
@Serializable
data class SshLogEntry(
    val id: Long = 0L,
    val timestampEpochMs: Long = 0L,
    val machineId: String,
    val tag: String,
    val message: String,
    val isError: Boolean = false,
)

/**
 * Bounded ring buffer of structured SSH, Mosh UDP SSP, and Endpoint Gen 1 diagnostic log entries.
 */
class SshDebugLog(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val nextId = MutableStateFlow(0L)
    private val _entries = MutableStateFlow<List<SshLogEntry>>(emptyList())
    val entries: StateFlow<List<SshLogEntry>> = _entries.asStateFlow()

    fun record(
        machineId: String,
        tag: String,
        message: String,
        isError: Boolean = false,
        timestampEpochMs: Long = 0L,
    ) {
        val entry = SshLogEntry(
            id = nextId.updateAndGet { it + 1L },
            timestampEpochMs = timestampEpochMs,
            machineId = machineId,
            tag = tag,
            message = message,
            isError = isError,
        )
        _entries.update { current ->
            if (maxEntries <= 0) {
                emptyList()
            } else if (current.size < maxEntries) {
                current + entry
            } else {
                buildList(maxEntries) {
                    addAll(current.subList(current.size - maxEntries + 1, current.size))
                    add(entry)
                }
            }
        }
    }

    fun log(
        machineId: String,
        message: String,
        tag: String = "SSH",
        isError: Boolean = false,
    ) {
        record(machineId = machineId, tag = tag, message = message, isError = isError)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 256
    }
}

/**
 * Platform-agnostic contract for the clean-room Mosh UDP SSP (`AES-128-OCB`) + `libghostty-vt` engine.
 */
interface MoshSessionEngine {
    suspend fun startSession(
        machineId: String,
        host: String,
        udpPort: Int,
        sessionKey: String,
        cols: UShort,
        rows: UShort,
        localEcho: Boolean = true,
    ): Boolean

    suspend fun sendInput(machineId: String, bytes: ByteArray): Boolean

    suspend fun resize(machineId: String, cols: UShort, rows: UShort): Boolean

    suspend fun setSuspended(machineId: String, suspended: Boolean)

    suspend fun notifyNetworkRoamed(machineId: String): Int

    suspend fun stopSession(machineId: String)

    fun isSessionActive(machineId: String): Boolean
}

/**
 * Platform-agnostic contract for YubiKey / FIDO2 (`sk-ssh-ed25519@openssh.com`,
 * `sk-ecdsa-sha2-nistp256@openssh.com`) and PIV smartcard SSH challenge signing over USB-C and NFC.
 */
interface HardwareKeySigner {
    suspend fun signChallenge(
        machineId: String,
        keyType: String,
        applicationId: String,
        challenge: ByteArray,
    ): Result<ByteArray>

    fun isHardwareKeyAvailable(): Boolean
}
