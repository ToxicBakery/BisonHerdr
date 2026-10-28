package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.RoamingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * Optimistically queued prompt waiting to be flushed once a roaming Mosh/SSH stream acknowledges.
 */
@Serializable
data class QueuedPrompt(
    val id: String,
    val machineId: String,
    val paneId: String,
    val agentName: String? = null,
    val prompt: String,
    val enqueuedAtEpochMs: Long = 0L,
)

/**
 * Reactive transport state holder for all configured/connected Herdr machines.
 *
 * All state mutations use thread-safe [MutableStateFlow.update] CAS blocks.
 */
class SshStateManager {

    private val _connectedMachines = MutableStateFlow<Set<String>>(emptySet())
    val connectedMachines: StateFlow<Set<String>> = _connectedMachines.asStateFlow()

    private val _activeTransportBackbone = MutableStateFlow<Map<String, ActiveBackbone>>(emptyMap())
    val activeTransportBackbone: StateFlow<Map<String, ActiveBackbone>> = _activeTransportBackbone.asStateFlow()

    private val _connectionProgress = MutableStateFlow<Map<String, ConnectionProgress>>(emptyMap())
    val connectionProgress: StateFlow<Map<String, ConnectionProgress>> = _connectionProgress.asStateFlow()

    private val _roamingState = MutableStateFlow<Map<String, RoamingStatus>>(emptyMap())
    val roamingState: StateFlow<Map<String, RoamingStatus>> = _roamingState.asStateFlow()

    private val _hardwareKeyTouchPrompt = MutableStateFlow<SshRequest.PromptHardwareKeyTouch?>(null)
    val hardwareKeyTouchPrompt: StateFlow<SshRequest.PromptHardwareKeyTouch?> = _hardwareKeyTouchPrompt.asStateFlow()

    private val _hostKeyPrompts = MutableStateFlow<Map<String, HostKeyPrompt>>(emptyMap())

    /** The open host key decisions, by machine id. See [HostKeyPrompt]. */
    val hostKeyPrompts: StateFlow<Map<String, HostKeyPrompt>> = _hostKeyPrompts.asStateFlow()

    private val _needsUser = MutableStateFlow<Map<String, NeedsUserCause>>(emptyMap())

    /**
     * The machines whose connection stopped until the user does an action, with the cause, by machine id.
     * See [NeedsUserCause].
     */
    val needsUser: StateFlow<Map<String, NeedsUserCause>> = _needsUser.asStateFlow()

    private val _userAuthPrompts = MutableStateFlow<List<UserAuthPrompt>>(emptyList())

    /** The open user authentication prompts, the oldest first. See [UserAuthPrompt] and [requestUserAuth]. */
    val userAuthPrompts: StateFlow<List<UserAuthPrompt>> = _userAuthPrompts.asStateFlow()

    /** The number of callers that run [hostUserAuthPrompts] now. */
    private val userAuthHosts = MutableStateFlow(0)

    private val _queuedPrompts = MutableStateFlow<Map<String, List<QueuedPrompt>>>(emptyMap())
    val queuedPrompts: StateFlow<Map<String, List<QueuedPrompt>>> = _queuedPrompts.asStateFlow()

    fun isMachineConnected(machineId: String): Boolean =
        machineId in _connectedMachines.value

    fun markMachineConnected(
        machineId: String,
        backbone: ActiveBackbone,
    ) {
        _connectedMachines.update { it + machineId }
        _activeTransportBackbone.update { it + (machineId to backbone) }
        _connectionProgress.update { it + (machineId to ConnectionProgress.Connected) }
        _roamingState.update { it + (machineId to RoamingStatus.Healthy) }
        _needsUser.update { it - machineId }
    }

    fun onMachineConnected(machineId: String, backbone: ActiveBackbone) {
        markMachineConnected(machineId, backbone)
    }

    fun markMachineDisconnected(machineId: String) {
        _connectedMachines.update { it - machineId }
        _activeTransportBackbone.update { it - machineId }
        _connectionProgress.update { it + (machineId to ConnectionProgress.Idle) }
        _roamingState.update { it - machineId }
    }

    fun onMachineDisconnected(machineId: String) {
        markMachineDisconnected(machineId)
    }

    fun setBackbone(machineId: String, backbone: ActiveBackbone) {
        _activeTransportBackbone.update { it + (machineId to backbone) }
    }

    fun updateBackbone(machineId: String, backbone: ActiveBackbone) {
        setBackbone(machineId, backbone)
    }

    fun setConnectionProgress(machineId: String, progress: ConnectionProgress) {
        _connectionProgress.update { it + (machineId to progress) }
    }

    fun updateProgress(machineId: String, progress: ConnectionProgress) {
        setConnectionProgress(machineId, progress)
    }

    fun setRoamingStatus(machineId: String, status: RoamingStatus) {
        _roamingState.update { it + (machineId to status) }
    }

    fun updateRoamingStatus(machineId: String, status: RoamingStatus) {
        setRoamingStatus(machineId, status)
    }

    fun setHardwareKeyTouchPrompt(prompt: SshRequest.PromptHardwareKeyTouch?) {
        _hardwareKeyTouchPrompt.value = prompt
    }

    fun dismissHardwareKeyTouchPrompt() {
        _hardwareKeyTouchPrompt.value = null
    }

    /** Puts [prompt] in [hostKeyPrompts]. It replaces the open prompt of the same machine. */
    fun setHostKeyPrompt(prompt: HostKeyPrompt) {
        _hostKeyPrompts.update { it + (prompt.machineId to prompt) }
    }

    /** Removes the open host key prompt of [machineId]. */
    fun clearHostKeyPrompt(machineId: String) {
        _hostKeyPrompts.update { it - machineId }
    }

    /** Puts [cause] in [needsUser] for [machineId]. It replaces the cause that the machine has. */
    fun setNeedsUser(machineId: String, cause: NeedsUserCause) {
        _needsUser.update { it + (machineId to cause) }
    }

    /** Removes the cause of [machineId] from [needsUser]. */
    fun clearNeedsUser(machineId: String) {
        _needsUser.update { it - machineId }
    }

    /**
     * Asks the user to confirm the identity for the key [keyAlias] of [machineId], and waits for the answer.
     * The UI shows [machineName].
     *
     * - When no UI runs [hostUserAuthPrompts], it returns [UserAuthOutcome.NoUi] at once and puts no prompt.
     * - When no answer comes in [timeoutMs], it returns [UserAuthOutcome.TimedOut] and removes the prompt.
     * - A new prompt for [machineId] cancels the open prompt of the same machine.
     */
    suspend fun requestUserAuth(
        machineId: String,
        machineName: String,
        keyAlias: String,
        timeoutMs: Long,
    ): UserAuthOutcome {
        if (userAuthHosts.value == 0) return UserAuthOutcome.NoUi
        val prompt = UserAuthPrompt(machineId = machineId, machineName = machineName, keyAlias = keyAlias)
        var replaced: List<UserAuthPrompt> = emptyList()
        _userAuthPrompts.update { current ->
            replaced = current.filter { it.machineId == machineId }
            current.filter { it.machineId != machineId } + prompt
        }
        replaced.forEach { answerUserAuth(it, UserAuthOutcome.Cancelled) }
        try {
            // The last UI can stop after the first check. Its stop cancels only the prompts that it can see.
            if (userAuthHosts.value == 0) return UserAuthOutcome.NoUi
            return withTimeoutOrNull(timeoutMs) { prompt.answer.await() } ?: UserAuthOutcome.TimedOut
        } finally {
            // A late answer from the UI has no effect.
            answerUserAuth(prompt, UserAuthOutcome.TimedOut)
        }
    }

    /**
     * Shows each open [UserAuthPrompt] with [show], one at a time, and gives the result of [show] as the answer.
     *
     * A UI calls this function only while it can show a prompt, for example while its activity is started.
     * [requestUserAuth] asks the user only while one or more callers run this function. The function runs until
     * the caller cancels it. When a prompt closes before [show] returns, for example after a time-out, it cancels
     * [show]. When the last caller stops, the open prompts get [UserAuthOutcome.Cancelled].
     */
    suspend fun hostUserAuthPrompts(show: suspend (UserAuthPrompt) -> UserAuthOutcome) {
        userAuthHosts.update { it + 1 }
        try {
            _userAuthPrompts
                .map { prompts -> prompts.firstOrNull() }
                .distinctUntilChanged { old, new -> old === new }
                .collectLatest { prompt ->
                    if (prompt != null) answerUserAuth(prompt, showSafely(prompt, show))
                }
        } finally {
            if (userAuthHosts.updateAndGet { it - 1 } == 0) cancelAllUserAuth()
        }
    }

    /**
     * Gives [UserAuthOutcome.Cancelled] to the open user authentication prompt of [machineId]. The wait of
     * [requestUserAuth] then ends at once. A disconnect calls it before its request goes into a queue, because the
     * connect that waits for the prompt can hold that queue.
     */
    fun cancelUserAuth(machineId: String) {
        _userAuthPrompts.value
            .filter { it.machineId == machineId }
            .forEach { answerUserAuth(it, UserAuthOutcome.Cancelled) }
    }

    /** Gives [UserAuthOutcome.Cancelled] to all open user authentication prompts. */
    fun cancelAllUserAuth() {
        _userAuthPrompts.value.forEach { answerUserAuth(it, UserAuthOutcome.Cancelled) }
    }

    /** Returns the result of [show], or [UserAuthOutcome.Failed] when [show] throws an error. */
    private suspend fun showSafely(
        prompt: UserAuthPrompt,
        show: suspend (UserAuthPrompt) -> UserAuthOutcome,
    ): UserAuthOutcome =
        try {
            show(prompt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            UserAuthOutcome.Failed(error.message ?: error::class.simpleName.orEmpty())
        }

    /** Gives [outcome] as the answer to [prompt] when [prompt] has no answer, and removes [prompt]. */
    private fun answerUserAuth(prompt: UserAuthPrompt, outcome: UserAuthOutcome) {
        prompt.answer.complete(outcome)
        _userAuthPrompts.update { current -> current.filter { it !== prompt } }
    }

    fun enqueuePrompt(prompt: QueuedPrompt) {
        val key = promptQueueKey(prompt.machineId, prompt.paneId)
        _queuedPrompts.update { current ->
            val existing = current[key].orEmpty()
            current + (key to (existing + prompt))
        }
    }

    fun enqueuePrompt(
        machineId: String,
        paneId: String,
        prompt: String,
        agentName: String? = null,
        id: String = "${machineId}_${paneId}_${(_queuedPrompts.value[promptQueueKey(machineId, paneId)]?.size ?: 0) + 1}",
        enqueuedAtEpochMs: Long = 0L,
    ): QueuedPrompt {
        val item = QueuedPrompt(
            id = id,
            machineId = machineId,
            paneId = paneId,
            agentName = agentName,
            prompt = prompt,
            enqueuedAtEpochMs = enqueuedAtEpochMs,
        )
        enqueuePrompt(item)
        return item
    }

    fun queuedPromptsForPane(machineId: String, paneId: String): List<QueuedPrompt> =
        _queuedPrompts.value[promptQueueKey(machineId, paneId)].orEmpty()

    fun queuedPromptsForMachine(machineId: String): List<QueuedPrompt> =
        _queuedPrompts.value.values.flatten().filter { it.machineId == machineId }

    /**
     * Atomically removes and returns queued prompts for [machineId] (and optionally a specific [paneId])
     * so they can be flushed when the stream reconnects.
     */
    fun flushQueuedPrompts(machineId: String, paneId: String? = null): List<QueuedPrompt> {
        var flushed: List<QueuedPrompt> = emptyList()
        _queuedPrompts.update { current ->
            if (paneId != null) {
                val key = promptQueueKey(machineId, paneId)
                flushed = current[key].orEmpty()
                current - key
            } else {
                val matchingKeys = current.filterValues { list ->
                    list.any { it.machineId == machineId }
                }.keys
                flushed = matchingKeys.flatMap { key -> current[key].orEmpty() }
                current - matchingKeys
            }
        }
        return flushed
    }

    companion object {
        fun promptQueueKey(machineId: String, paneId: String): String = "$machineId:$paneId"
    }
}
