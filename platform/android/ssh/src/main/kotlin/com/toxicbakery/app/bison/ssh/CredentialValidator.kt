package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calculates the [CredentialState] of a machine from the [secretStore] and the device key store.
 *
 * - A password machine is [CredentialState.PasswordMissing] when it has no password, or when [secretStore] has no
 *   secret for its reference. A plain-text password from an earlier version is [CredentialState.Ready], because
 *   the load migration moves it into [secretStore].
 * - A key store machine is [CredentialState.KeyMissing] when [keyExists] returns false for its alias.
 * - An error of [secretStore] gives [CredentialState.Ready]. The error is not a sign that the password is absent,
 *   and a connect then shows the error.
 */
class CredentialValidator(
    private val secretStore: SecretStore,
    private val keyExists: (keyAlias: String) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialChecker {

    override suspend fun check(machine: HostMachine): CredentialState = withContext(ioDispatcher) {
        when (val auth = machine.authMethod) {
            is SshAuthMethod.Password -> passwordState(machine, auth.encryptedPasswordRef)
            is SshAuthMethod.KeystoreKey ->
                if (keyExists(auth.keyAlias)) CredentialState.Ready else CredentialState.KeyMissing(auth.keyAlias)
            // The credential is on the security key, so the device cannot check it. The connect tells the cause.
            is SshAuthMethod.HardwareSecurityKey -> CredentialState.Ready
        }
    }

    private suspend fun passwordState(machine: HostMachine, value: String): CredentialState {
        if (value.isEmpty()) return CredentialState.PasswordMissing
        if (!SecretRef.hasPrefix(value)) return CredentialState.Ready
        val ref = SecretRef.parse(value) ?: return CredentialState.PasswordMissing
        val secret = try {
            secretStore.get(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Could not read the password of ${machine.id}." }
            return CredentialState.Ready
        }
        return if (secret == null) {
            CredentialState.PasswordMissing
        } else {
            secret.fill(0)
            CredentialState.Ready
        }
    }

    private companion object {
        val log = Arbor.tag("CredentialValidator")
    }
}
