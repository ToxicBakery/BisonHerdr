package com.toxicbakery.app.bison.common.screen.machine

import androidx.lifecycle.viewModelScope
import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegate
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.KeystoreKeySupport
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.ssh.KeyCreationException
import com.toxicbakery.app.bison.ssh.KeyCreationFailure
import com.toxicbakery.app.bison.ssh.SecretRef
import com.toxicbakery.app.bison.ssh.SecretStore
import com.toxicbakery.app.bison.ssh.SshPublicKeyProvider
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.util.BaseViewModel
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Edits one machine. A password goes into the [secretStore], and the machine keeps only a [SecretRef] value.
 *
 * - The password field never shows the stored password. When it is empty, "Save Host" keeps the stored password.
 * - A new password gets a new reference, and the save removes the old secret.
 * - A delete of the machine removes its secret.
 */
class ConfigureMachineViewModel(
    val machineId: String,
    private val machineStore: MachineStore,
    private val machineViewModelDelegate: MachineViewModelDelegate,
    private val secretStore: SecretStore,
    keystoreKeySupport: KeystoreKeySupport = KeystoreKeySupport.AllAlgorithms,
    private val sshPublicKeyProvider: SshPublicKeyProvider? = null,
) : BaseViewModel<ConfigureMachineViewModel.State, ConfigureMachineViewModel.Event, ConfigureMachineViewModel.Action>(
    initialState = createInitialState(machineId, machineStore.getMachine(machineId), keystoreKeySupport)
        .copy(canShowPublicKey = sshPublicKeyProvider != null),
), MachineViewModelDelegate by machineViewModelDelegate {

    /**
     * The SSH login methods. [isSupported] is false for a method that this version cannot use. The screen
     * does not offer such a method, but a loaded machine can still have it.
     */
    enum class AuthType(val isSupported: Boolean = true) {
        KeystoreKey,
        Password,

        /** This version does not support hardware security keys. See issue #23. */
        HardwareSecurityKey(isSupported = false),
    }

    data class State(
        val id: String,
        val isEditingExisting: Boolean,
        val name: String = "",
        val hostname: String = "",
        val portText: String = "22",
        val username: String = "",
        val transportMode: TransportMode = TransportMode.AutoPreferMosh,
        val moshServerPath: String = "",
        val moshUdpPortRange: String = HostMachine.DEFAULT_MOSH_UDP_PORT_RANGE,
        val herdrSessionName: String = "",
        val authType: AuthType = AuthType.KeystoreKey,
        val keystoreAlias: String = KeyAlgorithm.Ed25519.defaultKeystoreAlias,
        val keyAlgorithm: KeyAlgorithm = KeyAlgorithm.Ed25519,
        /** The key types that this device can create. The screen shows only these. */
        val availableKeyAlgorithms: List<KeyAlgorithm> = KeyAlgorithm.entries.toList(),
        val requireBiometric: Boolean = false,
        /** True when the platform can give the public key of a key store key. */
        val canShowPublicKey: Boolean = false,
        /** The `authorized_keys` line for [keystoreAlias] and [keyAlgorithm], after [Event.ShowPublicKey]. */
        val publicKeyLine: String? = null,
        val isLoadingPublicKey: Boolean = false,
        val publicKeyError: String? = null,
        /** The cause when the device key store did not make the key. The screen shows it next to the key types. */
        val keyCreationFailure: KeyCreationFailure? = null,
        val passwordRef: String = "",
        /** Tells if the loaded machine has a stored password. The password field shows a hint for it. */
        val storedPassword: StoredPassword = StoredPassword.None,
        /**
         * The hardware security key of a loaded machine. The screen cannot change it, so "Save Host" keeps
         * it with all of its fields. This version does not support the method (issue #23).
         */
        val hardwareKey: SshAuthMethod.HardwareSecurityKey? = null,
        val proxyJumpEnabled: Boolean = false,
        val bastionHost: String = "",
        val bastionPortText: String = "22",
        val bastionUser: String = "",
        val tailscaleMagicDns: Boolean = false,
        val themeOverrideId: String? = null,
        val fontSizeSp: Float = HostMachine.DEFAULT_FONT_SIZE_SP,
        val validationError: String? = null,
    ) {
        val parsedPort: Int?
            get() = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }

        val canSave: Boolean
            get() = hostname.isNotBlank() && username.isNotBlank() && parsedPort != null

        /** The login methods that the screen offers. */
        val authChoices: List<AuthType>
            get() = AUTH_CHOICES

        /** False when this version cannot log in with [authType]. The screen then blocks the connect. */
        val canConnect: Boolean
            get() = canSave && authType.isSupported
    }

    /** The state of the stored password of the loaded machine. */
    enum class StoredPassword {
        /** The machine has no stored password. */
        None,

        /** The machine has a stored password. An empty password field keeps it. */
        Stored,

        /** The machine refers to a stored password, but this device does not have it, for example after a restore. */
        Missing,
    }

    sealed interface Event {
        data class UpdateName(val name: String) : Event
        data class UpdateHostname(val hostname: String) : Event
        data class UpdatePort(val portText: String) : Event
        data class UpdateUsername(val username: String) : Event
        data class UpdateTransportMode(val mode: TransportMode) : Event
        data class UpdateMoshServerPath(val path: String) : Event
        data class UpdateMoshUdpPortRange(val range: String) : Event
        data class UpdateHerdrSessionName(val sessionName: String) : Event
        data class UpdateAuthType(val authType: AuthType) : Event
        data class UpdateKeystoreAlias(val alias: String) : Event
        data class UpdateKeyAlgorithm(val algorithm: KeyAlgorithm) : Event

        /** Loads the public key of the key store key. This creates the key if it does not exist. */
        data object ShowPublicKey : Event
        data class UpdateRequireBiometric(val require: Boolean) : Event
        data class UpdatePasswordRef(val passwordRef: String) : Event
        data class UpdateProxyJumpEnabled(val enabled: Boolean) : Event
        data class UpdateBastionHost(val host: String) : Event
        data class UpdateBastionPort(val portText: String) : Event
        data class UpdateBastionUser(val user: String) : Event
        data class UpdateTailscaleMagicDns(val enabled: Boolean) : Event
        data class UpdateThemeOverride(val themeId: String?) : Event
        data object SaveMachine : Event
        data object SaveAndConnect : Event
        data object DeleteMachine : Event
    }

    sealed interface Action {
        data class Saved(val machineId: String, val connectImmediately: Boolean) : Action
        data object Deleted : Action
    }

    init {
        checkStoredPassword()
    }

    override fun onEvent(event: Event) {
        when (event) {
            is Event.UpdateName -> updateState { it.copy(name = event.name, validationError = null) }
            is Event.UpdateHostname -> updateState { it.copy(hostname = event.hostname, validationError = null) }
            is Event.UpdatePort -> updateState { it.copy(portText = event.portText, validationError = null) }
            is Event.UpdateUsername -> updateState { it.copy(username = event.username, validationError = null) }
            is Event.UpdateTransportMode -> updateState { it.copy(transportMode = event.mode) }
            is Event.UpdateMoshServerPath -> updateState { it.copy(moshServerPath = event.path) }
            is Event.UpdateMoshUdpPortRange -> updateState { it.copy(moshUdpPortRange = event.range) }
            is Event.UpdateHerdrSessionName -> updateState { it.copy(herdrSessionName = event.sessionName) }
            is Event.UpdateAuthType -> if (event.authType.isSupported) {
                updateState { it.copy(authType = event.authType) }
            }
            is Event.UpdateKeystoreAlias -> updateState { it.copy(keystoreAlias = event.alias).withoutPublicKey() }
            is Event.UpdateKeyAlgorithm -> updateState { current ->
                if (event.algorithm !in current.availableKeyAlgorithms) {
                    current
                } else {
                    current.copy(
                        keyAlgorithm = event.algorithm,
                        keystoreAlias = aliasForAlgorithm(current.keystoreAlias, current.keyAlgorithm, event.algorithm),
                    ).withoutPublicKey()
                }
            }
            is Event.UpdateRequireBiometric -> updateState {
                it.copy(requireBiometric = event.require, keyCreationFailure = null)
            }
            is Event.ShowPublicKey -> loadPublicKey()
            is Event.UpdatePasswordRef -> updateState { it.copy(passwordRef = event.passwordRef) }
            is Event.UpdateProxyJumpEnabled -> updateState { it.copy(proxyJumpEnabled = event.enabled) }
            is Event.UpdateBastionHost -> updateState { it.copy(bastionHost = event.host) }
            is Event.UpdateBastionPort -> updateState { it.copy(bastionPortText = event.portText) }
            is Event.UpdateBastionUser -> updateState { it.copy(bastionUser = event.user) }
            is Event.UpdateTailscaleMagicDns -> updateState { it.copy(tailscaleMagicDns = event.enabled) }
            is Event.UpdateThemeOverride -> updateState { it.copy(themeOverrideId = event.themeId) }
            Event.SaveMachine -> saveMachine(connectImmediately = false)
            Event.SaveAndConnect -> saveMachine(connectImmediately = true)
            Event.DeleteMachine -> {
                val targetId = state.value.id
                viewModelScope.launch {
                    val removed = machineStore.getMachine(targetId)
                    removeMachine(targetId)
                    storedPasswordRef(removed)?.let { ref -> removeSecret(ref) }
                    sendAction(Action.Deleted)
                }
            }
        }
    }

    /** Sets [StoredPassword.Missing] when the loaded machine refers to a password that this device does not have. */
    private fun checkStoredPassword() {
        val value = (machineStore.getMachine(machineId)?.authMethod as? SshAuthMethod.Password)?.encryptedPasswordRef
        if (value == null || !SecretRef.hasPrefix(value)) return
        val ref = SecretRef.parse(value)
        if (ref == null) {
            updateState { it.copy(storedPassword = StoredPassword.Missing) }
            return
        }
        viewModelScope.launch {
            val secret = try {
                secretStore.get(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "Could not read the stored password of $machineId." }
                return@launch
            }
            if (secret == null) {
                updateState { it.copy(storedPassword = StoredPassword.Missing) }
            } else {
                secret.fill(0)
            }
        }
    }

    /**
     * Puts [password] into the [secretStore] with a new reference. Returns the [SecretRef] value, or null when the
     * store fails. The failure shows in [State.validationError].
     */
    private suspend fun storePassword(password: String): String? {
        val ref = SecretRef.newPasswordRef()
        val secret = password.encodeToByteArray()
        return try {
            secretStore.put(ref, secret)
            SecretRef.format(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The error comes from the device key store or the file system. It does not contain the password.
            updateState { it.copy(validationError = "$PASSWORD_SAVE_ERROR ${e.message ?: e::class.simpleName}") }
            null
        } finally {
            secret.fill(0)
        }
    }

    /** Removes the stored password of [previous] when [saved] does not use it. */
    private suspend fun removeReplacedPassword(previous: HostMachine?, saved: HostMachine) {
        val oldRef = storedPasswordRef(previous) ?: return
        if (storedPasswordRef(saved) != oldRef) {
            removeSecret(oldRef)
        }
    }

    /** Removes the secret of [ref]. A failure does not stop the caller, because the secret has no more use. */
    private suspend fun removeSecret(ref: String) {
        try {
            secretStore.remove(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Could not remove the secret of a machine password." }
        }
    }

    private fun State.withoutPublicKey(): State =
        copy(publicKeyLine = null, isLoadingPublicKey = false, publicKeyError = null, keyCreationFailure = null)

    private fun loadPublicKey() {
        val provider = sshPublicKeyProvider ?: return
        val request = state.value
        if (request.isLoadingPublicKey) return
        val alias = request.keystoreAlias.trim().ifBlank { request.keyAlgorithm.defaultKeystoreAlias }
        val algorithm = request.keyAlgorithm
        updateState { it.copy(isLoadingPublicKey = true, publicKeyError = null, keyCreationFailure = null) }
        viewModelScope.launch {
            val result = runCatching { provider.authorizedKeyLine(alias, algorithm, request.requireBiometric) }
            val error = result.exceptionOrNull()
            updateState { current ->
                val currentAlias = current.keystoreAlias.trim().ifBlank { current.keyAlgorithm.defaultKeystoreAlias }
                when {
                    // The user changed the key while it loaded. This result is for the old key.
                    currentAlias != alias || current.keyAlgorithm != algorithm -> current
                    else -> current.copy(
                        isLoadingPublicKey = false,
                        publicKeyLine = result.getOrNull(),
                        keyCreationFailure = (error as? KeyCreationException)?.failure,
                        publicKeyError = error?.takeIf { it !is KeyCreationException }?.let { err ->
                            "Could not get the public key: ${err.message ?: err::class.simpleName}"
                        },
                    )
                }
            }
        }
    }

    private fun saveMachine(connectImmediately: Boolean) {
        val current = state.value
        val port = current.parsedPort
        if (current.hostname.isBlank() || current.username.isBlank() || port == null) {
            updateState {
                it.copy(validationError = "Hostname, username, and a valid TCP port (1..65535) are required.")
            }
            return
        }
        if (connectImmediately && !current.authType.isSupported) {
            updateState { it.copy(validationError = UNSUPPORTED_AUTH_ERROR) }
            return
        }

        val resolvedId = current.id.ifBlank {
            val slug = current.hostname.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            "host-${slug.ifBlank { "herdr" }}-${current.username.trim().lowercase()}"
        }
        val resolvedName = current.name.trim().ifBlank { current.hostname.trim() }
        val previous = machineStore.getMachine(resolvedId)
        val previousPasswordValue = (previous?.authMethod as? SshAuthMethod.Password)?.encryptedPasswordRef.orEmpty()
        val typedPassword = current.passwordRef

        val authMethod: SshAuthMethod = when (current.authType) {
            AuthType.KeystoreKey -> SshAuthMethod.KeystoreKey(
                keyAlias = current.keystoreAlias.trim().ifBlank { current.keyAlgorithm.defaultKeystoreAlias },
                algorithm = current.keyAlgorithm,
                requireBiometric = current.requireBiometric,
            )
            // An empty password field keeps the stored password. A typed password replaces it below.
            AuthType.Password -> SshAuthMethod.Password(
                encryptedPasswordRef = previousPasswordValue,
            )
            // The view model does not select this method, so it is the method of the loaded machine.
            AuthType.HardwareSecurityKey -> current.hardwareKey ?: SshAuthMethod.HardwareSecurityKey()
        }

        val proxyJump = if (current.proxyJumpEnabled && current.bastionHost.isNotBlank()) {
            ProxyJumpConfig(
                bastionHost = current.bastionHost.trim(),
                bastionPort = current.bastionPortText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 22,
                bastionUser = current.bastionUser.trim().ifBlank { current.username.trim() },
                tailscaleMagicDns = current.tailscaleMagicDns,
            )
        } else {
            null
        }

        val machine = HostMachine(
            id = resolvedId,
            name = resolvedName,
            hostname = current.hostname.trim(),
            port = port,
            username = current.username.trim(),
            authMethod = authMethod,
            transportMode = current.transportMode,
            moshServerPath = current.moshServerPath.trim().takeIf { it.isNotEmpty() },
            moshUdpPortRange = current.moshUdpPortRange.trim().ifBlank { HostMachine.DEFAULT_MOSH_UDP_PORT_RANGE },
            herdrSessionName = current.herdrSessionName.trim().takeIf { it.isNotEmpty() },
            proxyJump = proxyJump,
            themeOverrideId = current.themeOverrideId,
            fontSizeSp = current.fontSizeSp,
        )

        viewModelScope.launch {
            val saved = if (current.authType == AuthType.Password && typedPassword.isNotEmpty()) {
                val passwordValue = storePassword(typedPassword) ?: return@launch
                machine.copy(authMethod = SshAuthMethod.Password(encryptedPasswordRef = passwordValue))
            } else {
                machine
            }
            upsertMachine(saved)
            removeReplacedPassword(previous, saved)
            updateState { it.copy(passwordRef = "", storedPassword = storedPasswordOf(saved)) }
            if (connectImmediately) {
                markPendingConnect(saved.id)
                connect(saved.id)
            }
            sendAction(Action.Saved(machineId = saved.id, connectImmediately = connectImmediately))
        }
    }

    companion object {
        private val log = Arbor.tag("ConfigureMachineViewModel")

        /** The hint below the password field when the machine has a stored password. */
        const val STORED_PASSWORD_HINT: String =
            "This device keeps the password of this machine. Leave this field empty to keep the stored password."

        /** The hint below the password field when this device does not have the stored password. */
        const val MISSING_PASSWORD_HINT: String =
            "This device does not have the password of this machine. Type the password again."

        /** The start of the error when the device cannot keep the password. */
        const val PASSWORD_SAVE_ERROR: String = "The device could not keep the password:"

        /** Returns the reference of the stored password of [machine], or null when it has no stored password. */
        internal fun storedPasswordRef(machine: HostMachine?): String? =
            (machine?.authMethod as? SshAuthMethod.Password)?.encryptedPasswordRef?.let(SecretRef::parse)

        /** Returns [StoredPassword.Stored] when [machine] has a password value, else [StoredPassword.None]. */
        internal fun storedPasswordOf(machine: HostMachine?): StoredPassword {
            val value = (machine?.authMethod as? SshAuthMethod.Password)?.encryptedPasswordRef
            return if (value.isNullOrEmpty()) StoredPassword.None else StoredPassword.Stored
        }

        /** The text that the app shows for a login method that this version does not support. */
        const val UNSUPPORTED_AUTH_LABEL: String = "Not supported in this version"

        /** The error that "Save & Connect" shows for a login method that this version does not support. */
        const val UNSUPPORTED_AUTH_ERROR: String =
            "$UNSUPPORTED_AUTH_LABEL: hardware security keys. Select a different authentication method to connect."

        /** The login methods that the screen offers: only the methods that this version supports. */
        val AUTH_CHOICES: List<AuthType> = AuthType.entries.filter { it.isSupported }

        /**
         * Returns the alias for a change from [previous] to [next]. A blank alias or the default
         * alias of [previous] becomes the default alias of [next], so that two key types never
         * share the default alias. A custom alias does not change.
         */
        internal fun aliasForAlgorithm(alias: String, previous: KeyAlgorithm, next: KeyAlgorithm): String =
            if (alias.isBlank() || alias.trim() == previous.defaultKeystoreAlias) next.defaultKeystoreAlias else alias

        fun createInitialState(
            machineId: String,
            existing: HostMachine?,
            keystoreKeySupport: KeystoreKeySupport = KeystoreKeySupport.AllAlgorithms,
        ): State {
            val available = keystoreKeySupport.supportedAlgorithms
            if (existing == null) {
                val algorithm = keystoreKeySupport.defaultAlgorithm
                return State(
                    id = machineId,
                    isEditingExisting = false,
                    keyAlgorithm = algorithm,
                    keystoreAlias = algorithm.defaultKeystoreAlias,
                    availableKeyAlgorithms = available,
                )
            }
            val authType = when (existing.authMethod) {
                is SshAuthMethod.KeystoreKey -> AuthType.KeystoreKey
                is SshAuthMethod.Password -> AuthType.Password
                is SshAuthMethod.HardwareSecurityKey -> AuthType.HardwareSecurityKey
            }
            val keystoreAuth = existing.authMethod as? SshAuthMethod.KeystoreKey
            // A key type that this device cannot create changes to the default type. Such a key
            // was never created on this device, so no working key is lost.
            val storedAlgorithm = keystoreAuth?.algorithm ?: keystoreKeySupport.defaultAlgorithm
            val keyAlgorithm = storedAlgorithm.takeIf { keystoreKeySupport.isSupported(it) }
                ?: keystoreKeySupport.defaultAlgorithm
            val pj = existing.proxyJump
            return State(
                id = existing.id,
                isEditingExisting = true,
                name = existing.name,
                hostname = existing.hostname,
                portText = existing.port.toString(),
                username = existing.username,
                transportMode = existing.transportMode,
                moshServerPath = existing.moshServerPath.orEmpty(),
                moshUdpPortRange = existing.moshUdpPortRange,
                herdrSessionName = existing.herdrSessionName.orEmpty(),
                authType = authType,
                keystoreAlias = aliasForAlgorithm(
                    alias = keystoreAuth?.keyAlias ?: storedAlgorithm.defaultKeystoreAlias,
                    previous = storedAlgorithm,
                    next = keyAlgorithm,
                ),
                keyAlgorithm = keyAlgorithm,
                availableKeyAlgorithms = available,
                requireBiometric = keystoreAuth?.requireBiometric ?: false,
                storedPassword = storedPasswordOf(existing),
                hardwareKey = existing.authMethod as? SshAuthMethod.HardwareSecurityKey,
                proxyJumpEnabled = pj != null,
                bastionHost = pj?.bastionHost.orEmpty(),
                bastionPortText = (pj?.bastionPort ?: 22).toString(),
                bastionUser = pj?.bastionUser.orEmpty(),
                tailscaleMagicDns = pj?.tailscaleMagicDns ?: false,
                themeOverrideId = existing.themeOverrideId,
                fontSizeSp = existing.fontSizeSp,
            )
        }
    }
}
