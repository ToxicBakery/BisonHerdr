package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.TransportMode
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
 * Bounded ring buffer of structured SSH and Herdr diagnostic log entries.
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
 * Platform-agnostic contract for an engine of Mosh UDP sessions.
 *
 * This version has no Mosh transport. Issue #22 (https://github.com/ToxicBakery/BisonHerdr/issues/22) adds it.
 * Until then, [startSession] starts no session and returns false, and the app connects each machine over the SSH
 * endpoint bridge.
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

    companion object {
        /** The log text when a caller asks for a Mosh session. */
        const val NOT_AVAILABLE: String = "Mosh is not available in this version"

        /** The fallback reason of the SSH endpoint backbone when the host has `mosh-server`. */
        const val MOSH_SERVER_FOUND: String = "The host has mosh-server, but Mosh is not available in this version"

        /**
         * Returns the fallback reason of the SSH endpoint backbone of a machine with [mode], or null. [hasMoshServer]
         * is true when the host has `mosh-server`. A [TransportMode.MoshOnly] machine always gets a reason, because
         * it asks for Mosh.
         */
        fun fallbackReasonFor(mode: TransportMode, hasMoshServer: Boolean): String? =
            when {
                hasMoshServer -> MOSH_SERVER_FOUND
                mode == TransportMode.MoshOnly -> NOT_AVAILABLE
                else -> null
            }
    }
}

/**
 * Platform-agnostic contract for YubiKey / FIDO2 (`sk-ssh-ed25519@openssh.com`,
 * `sk-ecdsa-sha2-nistp256@openssh.com`) and PIV smartcard SSH challenge signing over USB-C and NFC.
 *
 * No class implements this contract in this version, and the app does not connect a machine that uses a
 * hardware security key. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds the FIDO2 signer.
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
