package com.toxicbakery.app.bison.common.screen.machine

import androidx.lifecycle.viewModelScope
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.store.KnownHostsStore
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.util.BaseViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shows the saved host keys of the saved machine [machineId] and deletes them.
 *
 * A machine uses the key of its SSH host and, with ProxyJump, the key of its bastion host.
 */
class KnownHostsViewModel(
    val machineId: String,
    machineStore: MachineStore,
    private val knownHostsStore: KnownHostsStore,
) : BaseViewModel<KnownHostsViewModel.State, KnownHostsViewModel.Event, KnownHostsViewModel.Action>(
    initialState = State(
        entries = machineStore.getMachine(machineId)
            ?.let { entriesFor(it, knownHostsStore.knownHosts.value) }
            .orEmpty(),
    ),
) {

    data class State(
        /** The saved host keys of the machine. */
        val entries: List<KnownHost> = emptyList(),
    )

    sealed interface Event {
        /** Deletes [entry]. The next connection to its `host:port` asks the user to accept the key again. */
        data class Delete(val entry: KnownHost) : Event
    }

    /** This view model sends no action. */
    sealed interface Action

    init {
        viewModelScope.launch {
            combine(machineStore.knownMachines, knownHostsStore.knownHosts) { machines, knownHosts ->
                machines.firstOrNull { it.id == machineId }
                    ?.let { entriesFor(it, knownHosts) }
                    .orEmpty()
            }.collect { entries -> updateState { it.copy(entries = entries) } }
        }
    }

    override fun onEvent(event: Event) {
        when (event) {
            is Event.Delete -> viewModelScope.launch {
                knownHostsStore.remove(event.entry.host, event.entry.port)
            }
        }
    }

    companion object {
        /**
         * Returns the `host:port` endpoints that [machine] checks a host key for: the SSH host and, with
         * ProxyJump, the bastion host. This agrees with the host rule of `ProxyJumpTunnel.resolveRoute`:
         * each endpoint has the host name that the user typed, with no change.
         */
        fun hostKeyEndpoints(machine: HostMachine): List<Pair<String, Int>> {
            val bastion = machine.proxyJump
                ?.takeIf { it.bastionHost.isNotBlank() }
                ?.let { it.bastionHost.trim() to it.bastionPort }
            return listOfNotNull(machine.hostname.trim() to machine.port, bastion)
        }

        /** Returns the entries of [knownHosts] for the endpoints of [machine]. See [hostKeyEndpoints]. */
        fun entriesFor(machine: HostMachine, knownHosts: List<KnownHost>): List<KnownHost> {
            val endpoints = hostKeyEndpoints(machine)
            return knownHosts.filter { entry -> endpoints.any { (host, port) -> entry.isFor(host, port) } }
        }
    }
}
