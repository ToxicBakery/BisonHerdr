package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.PaneSurfaceBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
import com.toxicbakery.app.bison.ssh.SshDebugLog
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import org.kodein.di.DI
import org.kodein.di.bindSingletonOf

val sshCoreModule = DI.Module("sshCoreModule") {
    bindSingletonOf(::SshStateManager)
    bindSingletonOf(::SshRequestManager)
    bindSingletonOf(::SshDebugLog)
    bindSingletonOf(::EndpointSnapshotBus)
    bindSingletonOf(::PaneSurfaceBus)
    bindSingletonOf(::SemanticNotificationBus)
}
