package com.toxicbakery.app.bison.di

import android.app.Application
import com.toxicbakery.app.bison.ssh.AndroidKeystoreKeyManager
import com.toxicbakery.app.bison.ssh.HardwareKeySigner
import com.toxicbakery.app.bison.ssh.MoshSessionEngine
import com.toxicbakery.app.bison.ssh.MoshUdpEngine
import com.toxicbakery.app.bison.ssh.ProxyJumpTunnel
import com.toxicbakery.app.bison.ssh.SshTransportManager
import com.toxicbakery.app.bison.ssh.YubiKeyFido2Manager
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

internal val sshModule = DI.Module("sshModule") {
    bindSingleton {
        AndroidKeystoreKeyManager()
    }
    bindSingleton {
        YubiKeyFido2Manager(
            sshStateManager = instance(),
            sshDebugLog = instance(),
        )
    }
    bindSingleton<HardwareKeySigner> {
        instance<YubiKeyFido2Manager>()
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
        SshTransportManager(
            context = instance<Application>(),
            moshUdpEngine = instance(),
            sshStateManager = instance(),
            sshDebugLog = instance(),
            endpointSnapshotBus = instance(),
            paneSurfaceBus = instance(),
            semanticNotificationBus = instance(),
            keystoreKeyManager = instance(),
            yubiKeyFido2Manager = instance(),
            proxyJumpTunnel = instance(),
        )
    }
}
