package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.machine.MachineCardAction
import com.toxicbakery.app.bison.common.screen.machine.machineCardAction
import com.toxicbakery.app.bison.model.FeatureFlags
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.ssh.CredentialState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests the action of the main button of a machine card ([machineCardAction]). */
class MachineCardActionTest {

    private val passwordMachine = HostMachine(
        id = "workbox",
        name = "Workbox",
        hostname = "workbox.lan",
        username = "ian",
        authMethod = SshAuthMethod.Password(encryptedPasswordRef = "secret:password-absent"),
    )

    @Test
    fun aMissingPasswordConnectsAndTheConnectAsksForThePassword() {
        assertEquals(
            MachineCardAction.Connect,
            machineCardAction(passwordMachine, isConnected = false, credentialState = CredentialState.PasswordMissing),
        )
    }

    @Test
    fun aMissingKeyOpensTheEditorThatMakesTheKey() {
        val keyMachine = passwordMachine.copy(authMethod = SshAuthMethod.KeystoreKey(keyAlias = KEY_ALIAS))
        val keyMissing = CredentialState.KeyMissing(KEY_ALIAS)

        assertEquals(
            MachineCardAction.Configure,
            machineCardAction(keyMachine, isConnected = false, credentialState = keyMissing),
        )
    }

    @Test
    fun aReadyMachineConnectsAndAConnectedMachineOpensItsTerminal() {
        assertEquals(
            MachineCardAction.Connect,
            machineCardAction(passwordMachine, isConnected = false, credentialState = CredentialState.Ready),
        )
        assertEquals(
            MachineCardAction.OpenWorkspace,
            machineCardAction(passwordMachine, isConnected = true, credentialState = CredentialState.PasswordMissing),
        )
    }

    @Test
    fun anUnsupportedMethodOpensTheEditor() {
        val hardwareKeyMachine = passwordMachine.copy(authMethod = enrolledKey)

        assertEquals(
            MachineCardAction.Configure,
            machineCardAction(hardwareKeyMachine, isConnected = false, credentialState = CredentialState.Ready),
        )
        val pivMachine = passwordMachine.copy(authMethod = enrolledKey.copy(keyType = HardwareKeyType.PivSmartCard))
        assertEquals(
            MachineCardAction.Configure,
            machineCardAction(pivMachine, isConnected = false, credentialState = CredentialState.Ready, flagsOn),
        )
    }

    /** With the flag, an enrolled security key connects, and a key with no credential opens the editor. */
    @Test
    fun withTheFlagAnEnrolledSecurityKeyConnects() {
        val enrolledMachine = passwordMachine.copy(authMethod = enrolledKey)
        val newKeyMachine = passwordMachine.copy(authMethod = SshAuthMethod.HardwareSecurityKey())

        assertEquals(
            MachineCardAction.Connect,
            machineCardAction(enrolledMachine, isConnected = false, credentialState = CredentialState.Ready, flagsOn),
        )
        assertEquals(
            MachineCardAction.Configure,
            machineCardAction(newKeyMachine, isConnected = false, credentialState = CredentialState.Ready, flagsOn),
        )
    }

    private companion object {
        const val KEY_ALIAS = "work_key"

        val flagsOn = FeatureFlags(hardwareSecurityKeys = true)

        /** A security key with a credential. The values are only test data. */
        val enrolledKey = SshAuthMethod.HardwareSecurityKey(credentialId = "AAEC", publicKey = "AwQF")
    }
}
