package com.toxicbakery.app.bison.debug

import com.toxicbakery.app.bison.ssh.CredentialChecker
import com.toxicbakery.app.bison.ssh.CredentialValidator
import com.toxicbakery.app.bison.ssh.RealSshBackend
import com.toxicbakery.app.bison.ssh.SshBackend
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

/**
 * Release bindings. Binds the inert [NoDebugServer] so zero simulated server code ships in release builds.
 * Binds [RealSshBackend] as the [SshBackend] and [CredentialValidator] as the [CredentialChecker] for all machines.
 */
internal val debugModule = DI.Module("debugModule") {
    bindSingleton<DebugServer> { NoDebugServer }
    bindSingleton<SshBackend> { instance<RealSshBackend>() }
    bindSingleton<CredentialChecker> { instance<CredentialValidator>() }
}
