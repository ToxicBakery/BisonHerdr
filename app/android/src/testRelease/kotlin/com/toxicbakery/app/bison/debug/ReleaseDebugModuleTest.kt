package com.toxicbakery.app.bison.debug

import com.toxicbakery.app.bison.di.coreDiModule
import com.toxicbakery.app.bison.di.platformModule
import com.toxicbakery.app.bison.ssh.CredentialChecker
import com.toxicbakery.app.bison.ssh.CredentialValidator
import com.toxicbakery.app.bison.ssh.RealSshBackend
import com.toxicbakery.app.bison.ssh.SshBackend
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProviderTags
import kotlin.test.Test
import kotlin.test.assertSame
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Tests the bindings of the release [debugModule] with the same modules as the app. The release build type has
 * unit tests only for this check, so CI runs `testReleaseUnitTest` next to `testDebugUnitTest`.
 */
class ReleaseDebugModuleTest {

    private val di = DI {
        import(coreDiModule)
        import(platformModule)
        import(debugModule)
        // The Application binds this directory with its Context.
        bindSingleton<StoreIOProvider>(tag = StoreIOProviderTags.SECRETS) { InMemoryStoreIOProvider() }
    }

    @Test
    fun sshBackendIsTheRealBackendForAllMachines() {
        assertSame(di.direct.instance<RealSshBackend>(), di.direct.instance<SshBackend>())
    }

    @Test
    fun debugServerIsTheInertServer() {
        assertSame(NoDebugServer, di.direct.instance<DebugServer>())
    }

    @Test
    fun credentialCheckerIsTheValidator() {
        assertSame(di.direct.instance<CredentialValidator>(), di.direct.instance<CredentialChecker>())
    }
}
