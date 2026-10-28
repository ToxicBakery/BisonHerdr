package com.toxicbakery.app.bison.debug

import com.toxicbakery.app.bison.appModule
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
 * Tests the bindings of the release [debugModule] in [appModule], the module that the `Application` imports. Thus an
 * override in another module of the app also fails the test. The `Application` also binds the store directories
 * with its `Context`. The test binds only the secret store directory, because the instances of the test need no
 * other directory. CI runs `testReleaseUnitTest` next to `testDebugUnitTest`.
 */
class ReleaseDebugModuleTest {

    private val di = DI {
        import(appModule)
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
