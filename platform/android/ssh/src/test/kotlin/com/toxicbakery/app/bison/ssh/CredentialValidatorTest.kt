package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class CredentialValidatorTest {

    private val secretStore = softwareKeySecretStore()
    private val keys = mutableSetOf<String>()
    private val validator = CredentialValidator(
        secretStore = secretStore,
        keyExists = { alias -> alias in keys },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun aMachineWithAStoredPasswordIsReady() = runTest {
        secretStore.put("password-a", "hunter2".encodeToByteArray())

        assertEquals(CredentialState.Ready, validator.check(passwordMachine(SecretRef.format("password-a"))))
    }

    @Test
    fun aMachineWithNoSecretNeedsItsPassword() = runTest {
        assertEquals(CredentialState.PasswordMissing, validator.check(passwordMachine(SecretRef.format("password-a"))))
        assertEquals(CredentialState.PasswordMissing, validator.check(passwordMachine("")))
        assertEquals(CredentialState.PasswordMissing, validator.check(passwordMachine("secret:../machines.json")))
    }

    @Test
    fun aPlainTextPasswordThatTheMigrationDidNotMoveIsReady() = runTest {
        assertEquals(CredentialState.Ready, validator.check(passwordMachine("hunter2")))
    }

    @Test
    fun aKeyMachineNeedsItsKeyWhenTheKeyStoreHasNoKey() = runTest {
        val machine = passwordMachine("").copy(authMethod = SshAuthMethod.KeystoreKey(keyAlias = "bison_key"))

        assertEquals(CredentialState.KeyMissing("bison_key"), validator.check(machine))
        keys += "bison_key"
        assertEquals(CredentialState.Ready, validator.check(machine))
    }

    @Test
    fun theMachineCardShowsTheSameTextAsTheLoginError() {
        assertEquals(MissingKeyException.messageFor("bison_key"), CredentialState.KeyMissing("bison_key").message)
    }

    @Test
    fun anErrorOfTheSecretStoreIsReady() = runTest {
        val failing = object : SecretStore by secretStore {
            override suspend fun get(ref: String): ByteArray? = throw IllegalStateException("key store busy")
        }
        val machine = passwordMachine(SecretRef.format("password-a"))
        val failingValidator = CredentialValidator(failing, { true }, Dispatchers.Unconfined)

        assertEquals(CredentialState.Ready, failingValidator.check(machine))
    }

    private fun passwordMachine(value: String): HostMachine = HostMachine(
        id = "devbox",
        name = "devbox",
        hostname = "devbox",
        username = "ian",
        authMethod = SshAuthMethod.Password(encryptedPasswordRef = value),
    )
}
