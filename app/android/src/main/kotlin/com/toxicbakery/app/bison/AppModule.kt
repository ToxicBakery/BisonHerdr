package com.toxicbakery.app.bison

import com.toxicbakery.app.bison.activity.StartedActivityCounter
import com.toxicbakery.app.bison.debug.debugModule
import com.toxicbakery.app.bison.di.coreDiModule
import com.toxicbakery.app.bison.di.platformModule
import com.toxicbakery.app.bison.model.FeatureFlags
import com.toxicbakery.app.bison.screen.screenModule
import com.toxicbakery.app.bison.ssh.ForegroundState
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

internal val appModule = DI.Module("appModule") {
    import(coreDiModule)
    import(platformModule)
    import(screenModule)
    import(debugModule)

    bindSingleton { StartedActivityCounter() }
    bindSingleton { FeatureFlags(hardwareSecurityKeys = BuildConfig.HARDWARE_SECURITY_KEYS) }
    // SshTransportManager keeps the surface leases off while no activity is started.
    bindSingleton<ForegroundState> { instance<StartedActivityCounter>() }
}
