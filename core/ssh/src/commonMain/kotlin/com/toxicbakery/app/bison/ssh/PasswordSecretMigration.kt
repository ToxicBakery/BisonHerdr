package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException

/**
 * Moves the plain-text passwords of earlier versions into the [SecretStore].
 *
 * Earlier versions wrote the password into [SshAuthMethod.Password.encryptedPasswordRef]. For each such machine,
 * [migrate] puts the password into [secretStore] and writes a [SecretRef] value in its place. The machine store
 * calls [migrate] each time that it loads `machines.json`, so a failed move tries again on the next load.
 */
class PasswordSecretMigration(
    private val secretStore: SecretStore,
) {

    /** Returns [machines] with a [SecretRef] value in place of each plain-text password. */
    suspend fun migrate(machines: List<HostMachine>): List<HostMachine> = machines.map { machine -> migrate(machine) }

    /**
     * Returns [machine] with a [SecretRef] value in place of its plain-text password. Returns [machine] with no
     * change when it has no plain-text password, or when the [secretStore] does not keep the password.
     */
    suspend fun migrate(machine: HostMachine): HostMachine {
        val auth = machine.authMethod as? SshAuthMethod.Password ?: return machine
        val plainText = auth.encryptedPasswordRef
        if (plainText.isEmpty() || SecretRef.hasPrefix(plainText)) return machine
        val ref = SecretRef.newPasswordRef()
        val secret = plainText.encodeToByteArray()
        return try {
            secretStore.put(ref, secret)
            log.i { "Moved the password of ${machine.id} into the secret store." }
            machine.copy(authMethod = auth.copy(encryptedPasswordRef = SecretRef.format(ref)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The error comes from the cipher or the file system. It does not contain the password.
            log.e(e) {
                "Could not move the password of ${machine.id} into the secret store. The next load tries again."
            }
            machine
        } finally {
            secret.fill(0)
        }
    }

    private companion object {
        val log = Arbor.tag("PasswordSecretMigration")
    }
}
