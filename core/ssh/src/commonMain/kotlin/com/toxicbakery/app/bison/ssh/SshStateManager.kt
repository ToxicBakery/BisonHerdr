package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.RoamingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
