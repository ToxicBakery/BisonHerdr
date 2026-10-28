package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegate
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.ssh.HostKeyPrompt
import com.toxicbakery.app.bison.ssh.SshDebugLog
import com.toxicbakery.app.bison.ssh.SshLogEntry
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.store.KnownHostsStore
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.util.BaseViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConnectingMachineViewModel(
    val machineId: String,
    private val sshStateManager: SshStateManager,
    private val machineStore: MachineStore,
    private val machineViewModelDelegate: MachineViewModelDelegate,
    private val sshDebugLog: SshDebugLog,
    private val knownHostsStore: KnownHostsStore,
) : BaseViewModel<ConnectingMachineViewModel.State, ConnectingMachineViewModel.Event, ConnectingMachineViewModel.Action>(
    initialState = State(
        machineId = machineId,
        hostKeyPrompt = sshStateManager.hostKeyPrompts.value[machineId],
    ),
), MachineViewModelDelegate by machineViewModelDelegate {

    enum class StepStatus {
        Pending,
        Active,
        Completed,
        Failed,
    }

    @Immutable
    data class BootstrapStep(
        val stage: ConnectionProgress,
        val title: String,
        val subtitle: String,
        val status: StepStatus,
    )

    @Immutable
    data class State(
        val machineId: String,
        val hasDispatchedConnect: Boolean = false,
        /** The host key decision that the connection waits for, or null. */
        val hostKeyPrompt: HostKeyPrompt? = null,
    )

    sealed interface Event {
        data object StartConnectIfNeeded : Event
        data object RetryConnect : Event
        data object CancelConnect : Event

        /** Saves the unknown host key of [State.hostKeyPrompt] and connects again. */
        data object AcceptHostKey : Event

        /** Closes [State.hostKeyPrompt] and saves nothing. The connection stays failed. */
        data object RejectHostKey : Event

        /**
         * Removes the saved key of a changed [State.hostKeyPrompt] and connects again. The next
         * connection shows the new key as unknown, so the user must accept it.
         */
        data object RemoveSavedHostKey : Event
    }

    sealed interface Action {
        data class Connected(val machineId: String) : Action
        data object Cancelled : Action

        companion object {
            fun NavigateToWorkspace(machineId: String): Connected = Connected(machineId)
        }
    }

    val machine: StateFlow<HostMachine?> = machineStore.knownMachines
        .map { list -> list.firstOrNull { it.id == machineId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, machineStore.getMachine(machineId))

    val progress: StateFlow<ConnectionProgress> = combine(
        sshStateManager.connectionProgress,
        sshStateManager.connectedMachines,
    ) { progressMap, connectedSet ->
        when {
            machineId in connectedSet -> ConnectionProgress.Connected
            else -> progressMap[machineId] ?: ConnectionProgress.Idle
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        if (sshStateManager.isMachineConnected(machineId)) {
            ConnectionProgress.Connected
        } else {
            sshStateManager.connectionProgress.value[machineId] ?: ConnectionProgress.Idle
        },
    )

    val activeBackbone: StateFlow<ActiveBackbone?> = sshStateManager.activeTransportBackbone
        .map { it[machineId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sshStateManager.activeTransportBackbone.value[machineId])

    val steps: StateFlow<List<BootstrapStep>> = progress
        .map { currentProgress -> buildStepsForProgress(currentProgress) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, buildStepsForProgress(progress.value))

    val logs: StateFlow<List<SshLogEntry>> = sshDebugLog.entries
        .map { list -> list.filter { it.machineId == machineId || it.machineId == "*" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            sshStateManager.hostKeyPrompts
                .map { prompts -> prompts[machineId] }
                .distinctUntilChanged()
                .collect { prompt -> updateState { it.copy(hostKeyPrompt = prompt) } }
        }
        viewModelScope.launch {
            progress.collect { currentProgress ->
                if (currentProgress == ConnectionProgress.Connected) {
                    sendAction(Action.Connected(machineId))
                }
            }
        }
    }

    override fun onEvent(event: Event) {
        when (event) {
            Event.StartConnectIfNeeded -> {
                if (sshStateManager.isMachineConnected(machineId)) {
                    emitAction(Action.Connected(machineId))
                    return
                }
                if (consumePendingConnect(machineId)) {
                    updateState { it.copy(hasDispatchedConnect = true) }
                    return
                }
                val currentProgress = sshStateManager.connectionProgress.value[machineId]
                val isAlreadyConnecting = currentProgress == ConnectionProgress.AuthenticatingSsh ||
                    currentProgress == ConnectionProgress.BootstrappingMoshOrBridge ||
                    currentProgress == ConnectionProgress.VerifyingUdpOrHello ||
                    currentProgress == ConnectionProgress.SyncingHerdrSnapshot
                if (isAlreadyConnecting) {
                    updateState { it.copy(hasDispatchedConnect = true) }
                    return
                }
                if (!state.value.hasDispatchedConnect) {
                    updateState { it.copy(hasDispatchedConnect = true) }
                    viewModelScope.launch {
                        connect(machineId)
                    }
                }
            }

            Event.RetryConnect -> {
                updateState { it.copy(hasDispatchedConnect = true) }
                viewModelScope.launch {
                    connect(machineId)
                }
            }

            Event.CancelConnect -> {
                viewModelScope.launch {
                    disconnect(machineId)
                    sendAction(Action.Cancelled)
                }
            }

            Event.AcceptHostKey -> acceptHostKey()
            Event.RejectHostKey -> rejectHostKey()
            Event.RemoveSavedHostKey -> removeSavedHostKey()
        }
    }

    private fun acceptHostKey() {
        val prompt = state.value.hostKeyPrompt as? HostKeyPrompt.Unknown ?: return
        closeHostKeyPrompt()
        updateState { it.copy(hasDispatchedConnect = true) }
        sshDebugLog.record(
            machineId = machineId,
            tag = HOST_KEY_LOG_TAG,
            message = "Accepted ${prompt.presented.keyType} host key ${prompt.presented.fingerprint} " +
                "for ${prompt.presented.hostPort}",
        )
        viewModelScope.launch {
            knownHostsStore.put(prompt.presented)
            connect(machineId)
        }
    }

    private fun rejectHostKey() {
        val prompt = state.value.hostKeyPrompt ?: return
        closeHostKeyPrompt()
        sshDebugLog.record(
            machineId = machineId,
            tag = HOST_KEY_LOG_TAG,
            message = "Rejected ${prompt.presented.keyType} host key ${prompt.presented.fingerprint} " +
                "for ${prompt.presented.hostPort}",
            isError = true,
        )
    }

    private fun removeSavedHostKey() {
        val prompt = state.value.hostKeyPrompt as? HostKeyPrompt.Changed ?: return
        closeHostKeyPrompt()
        updateState { it.copy(hasDispatchedConnect = true) }
        sshDebugLog.record(
            machineId = machineId,
            tag = HOST_KEY_LOG_TAG,
            message = "Removed saved host key ${prompt.known.fingerprint} for ${prompt.known.hostPort}",
        )
        viewModelScope.launch {
            knownHostsStore.remove(prompt.known.host, prompt.known.port)
            connect(machineId)
        }
    }

    private fun closeHostKeyPrompt() {
        sshStateManager.clearHostKeyPrompt(machineId)
        updateState { it.copy(hostKeyPrompt = null) }
    }

    companion object {
        private const val HOST_KEY_LOG_TAG = "HostKey"

        private val ORDERED_STAGES = listOf(
            ConnectionProgress.AuthenticatingSsh to (
                "1. Authenticating SSH2" to "Verifying host fingerprint & signing with Keystore / YubiKey"
                ),
            ConnectionProgress.BootstrappingMoshOrBridge to (
                "2. Bootstrapping Dual-Transport" to "Probing mosh-server UDP SSP & Herdr remote-client-bridge"
                ),
            ConnectionProgress.VerifyingUdpOrHello to (
                "3. Verifying UDP SSP / Hello Handshake" to "Negotiating AES-128-OCB datagrams & Herdr Endpoint Gen 1"
                ),
            ConnectionProgress.SyncingHerdrSnapshot to (
                "4. Syncing Herdr Workspace Snapshot" to "Acquiring surface lease & initial BSP layout tree"
                ),
        )

        fun buildStepsForProgress(progress: ConnectionProgress): List<BootstrapStep> {
            val activeIndex = ORDERED_STAGES.indexOfFirst { it.first == progress }
            return ORDERED_STAGES.mapIndexed { index, (stage, labels) ->
                val status = when (progress) {
                    ConnectionProgress.Connected -> StepStatus.Completed
                    ConnectionProgress.Idle -> StepStatus.Pending
                    ConnectionProgress.Failed -> {
                        if (index == 0) StepStatus.Failed else StepStatus.Pending
                    }
                    else -> when {
                        activeIndex < 0 -> StepStatus.Pending
                        index < activeIndex -> StepStatus.Completed
                        index == activeIndex -> StepStatus.Active
                        else -> StepStatus.Pending
                    }
                }
                BootstrapStep(
                    stage = stage,
                    title = labels.first,
                    subtitle = labels.second,
                    status = status,
                )
            }
        }
    }
}
