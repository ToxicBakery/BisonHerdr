package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.PaneSurfaceBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
import com.toxicbakery.app.bison.ssh.SshDebugLog
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import org.kodein.di.DI
import org.kodein.di.bindSingleton

val sshCoreModule = DI.Module("sshCoreModule") {
    bindSingleton { SshStateManager() }
    bindSingleton { SshRequestManager() }
    bindSingleton { SshDebugLog() }
    bindSingleton { EndpointSnapshotBus() }
    bindSingleton { PaneSurfaceBus() }
    bindSingleton { SemanticNotificationBus() }
}
