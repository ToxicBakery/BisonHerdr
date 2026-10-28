package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.machine.MachineCardAction
import com.toxicbakery.app.bison.common.screen.machine.machineCardAction
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
        val hardwareKeyMachine = passwordMachine.copy(authMethod = SshAuthMethod.HardwareSecurityKey())

        assertEquals(
            MachineCardAction.Configure,
            machineCardAction(hardwareKeyMachine, isConnected = false, credentialState = CredentialState.Ready),
        )
    }

    private companion object {
        const val KEY_ALIAS = "work_key"
    }
}
