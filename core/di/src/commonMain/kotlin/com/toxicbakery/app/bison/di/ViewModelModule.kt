package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.common.screen.delegate.AgentActionDelegate
import com.toxicbakery.app.bison.common.screen.delegate.AgentActionDelegateImpl
import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegate
import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel
import com.toxicbakery.app.bison.common.screen.machine.ConnectingMachineViewModel
import com.toxicbakery.app.bison.common.screen.workspace.WorkspaceTerminalViewModel
import com.toxicbakery.app.bison.common.version.ReleaseFetcher
import com.toxicbakery.app.bison.common.version.VersionWatcher
import com.toxicbakery.app.bison.model.KeystoreKeySupport
import com.toxicbakery.app.bison.ssh.SshPublicKeyProvider
import org.kodein.di.DI
import org.kodein.di.bindFactory
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

val viewModelModule = DI.Module("viewModelModule") {
    bindFactory<String, WorkspaceTerminalViewModel> { machineId: String ->
        WorkspaceTerminalViewModel(
            machineId = machineId,
            machineStore = instance(),
            sshStateManager = instance(),
            paneSurfaceBus = instance(),
            machineViewModelDelegate = instance(),
            agentActionDelegate = instance(),
            appSettingsStore = instance(),
        )
    }
    bindFactory<String, ConfigureMachineViewModel> { machineId: String ->
        ConfigureMachineViewModel(
            machineId = machineId,
            machineStore = instance(),
            machineViewModelDelegate = instance(),
            // The platform layer binds the real key support. Other hosts use all key types.
            keystoreKeySupport = instanceOrNull<KeystoreKeySupport>() ?: KeystoreKeySupport.AllAlgorithms,
            sshPublicKeyProvider = instanceOrNull<SshPublicKeyProvider>(),
        )
    }
    bindFactory<String, ConnectingMachineViewModel> { machineId: String ->
        ConnectingMachineViewModel(
            machineId = machineId,
            sshStateManager = instance(),
            machineStore = instance(),
            machineViewModelDelegate = instance(),
            sshDebugLog = instance(),
        )
    }
    bindSingleton<MachineViewModelDelegate> {
        MachineViewModelDelegateImpl(
            machineStore = instance(),
            sshRequestManager = instance(),
            endpointSnapshotBus = instance(),
        )
    }
    bindSingleton<AgentActionDelegate> {
        AgentActionDelegateImpl(
            sshRequestManager = instance(),
            semanticNotificationBus = instance(),
            endpointSnapshotBus = instance(),
        )
    }
    bindSingleton<ReleaseFetcher> {
        ReleaseFetcher.default(json = instance())
    }
    bindSingleton {
        VersionWatcher(
            appSettings = instance(),
            releaseFetcher = instance(),
        )
    }
}
