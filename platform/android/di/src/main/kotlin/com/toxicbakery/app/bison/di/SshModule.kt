package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.model.KeystoreKeySupport
import com.toxicbakery.app.bison.ssh.AndroidKeystoreKeyManager
import com.toxicbakery.app.bison.ssh.AndroidKeystoreKeySupport
import com.toxicbakery.app.bison.ssh.MoshSessionEngine
import com.toxicbakery.app.bison.ssh.MoshUdpEngine
import com.toxicbakery.app.bison.ssh.ProxyJumpTunnel
import com.toxicbakery.app.bison.ssh.RealSshBackend
import com.toxicbakery.app.bison.ssh.SshPublicKeyProvider
import com.toxicbakery.app.bison.ssh.SshTransportManager
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

internal val sshModule = DI.Module("sshModule") {
    bindSingleton {
        AndroidKeystoreKeyManager()
    }
    bindSingleton<KeystoreKeySupport> {
        AndroidKeystoreKeySupport
    }
    bindSingleton<SshPublicKeyProvider> {
        instance<AndroidKeystoreKeyManager>()
    }
    bindSingleton {
        ProxyJumpTunnel(
            sshDebugLog = instance(),
        )
    }
    bindSingleton {
        MoshUdpEngine(
            sshDebugLog = instance(),
            paneSurfaceBus = instance(),
        )
    }
    bindSingleton<MoshSessionEngine> {
        instance<MoshUdpEngine>()
    }
    bindSingleton {
        RealSshBackend(
            sshStateManager = instance(),
            sshDebugLog = instance(),
            endpointSnapshotBus = instance(),
            paneSurfaceBus = instance(),
            semanticNotificationBus = instance(),
            keystoreKeyManager = instance(),
            knownHostsStore = instance(),
        )
    }
    // The debugModule of each build type binds SshBackend: release binds RealSshBackend only.
    bindSingleton {
        SshTransportManager(
            backend = instance(),
            moshUdpEngine = instance(),
            sshStateManager = instance(),
            sshDebugLog = instance(),
            paneSurfaceBus = instance(),
            proxyJumpTunnel = instance(),
        )
    }
}
