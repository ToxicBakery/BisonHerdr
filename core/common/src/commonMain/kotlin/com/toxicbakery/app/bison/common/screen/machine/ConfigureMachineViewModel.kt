package com.toxicbakery.app.bison.common.screen.machine

import androidx.lifecycle.viewModelScope
import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegate
import com.toxicbakery.app.bison.model.FeatureFlags
import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.KeystoreKeySupport
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.ssh.HardwareKeySigner
import com.toxicbakery.app.bison.ssh.KeyCreationException
import com.toxicbakery.app.bison.ssh.KeyCreationFailure
import com.toxicbakery.app.bison.ssh.SecretRef
import com.toxicbakery.app.bison.ssh.SecretStore
import com.toxicbakery.app.bison.ssh.SecurityKeyPublicKeys
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
 *
 * The FIDO2 security key method needs [FeatureFlags.hardwareSecurityKeys] and a [hardwareKeySigner] (issue #23).
 * Without them, the screen does not offer the method, and a loaded machine with a security key can save but cannot
 * connect. With them, [Event.EnrollHardwareKey] makes a credential on the key, and the screen shows its public key.
 */
class ConfigureMachineViewModel(
    val machineId: String,
    private val machineStore: MachineStore,
    private val machineViewModelDelegate: MachineViewModelDelegate,
    private val secretStore: SecretStore,
    keystoreKeySupport: KeystoreKeySupport = KeystoreKeySupport.AllAlgorithms,
    private val sshPublicKeyProvider: SshPublicKeyProvider? = null,
    featureFlags: FeatureFlags = FeatureFlags(),
    private val hardwareKeySigner: HardwareKeySigner? = null,
) : BaseViewModel<ConfigureMachineViewModel.State, ConfigureMachineViewModel.Event, ConfigureMachineViewModel.Action>(
    initialState = createInitialState(
        machineId = machineId,
        existing = machineStore.getMachine(machineId),
        keystoreKeySupport = keystoreKeySupport,
        hardwareKeysEnabled = featureFlags.hardwareSecurityKeys && hardwareKeySigner != null,
    ).copy(canShowPublicKey = sshPublicKeyProvider != null),
), MachineViewModelDelegate by machineViewModelDelegate {

    /** The SSH login methods. The screen offers the methods of [State.authChoices]. */
    enum class AuthType {
        KeystoreKey,
        Password,

        /**
         * A FIDO2 security key. The screen offers it only when [State.hardwareKeysEnabled] is true (issue #23). A
         * loaded machine can have it in all builds.
         */
        HardwareSecurityKey,
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
        /** True while the screen shows the warning before [Event.ConfirmRegenerateKey]. */
        val showRegenerateKeyWarning: Boolean = false,
        val passwordRef: String = "",
        /** Tells if the loaded machine has a stored password. The password field shows a hint for it. */
        val storedPassword: StoredPassword = StoredPassword.None,
        /**
         * True when this build offers FIDO2 security keys: [FeatureFlags.hardwareSecurityKeys] is true, and the
         * platform supplies a [HardwareKeySigner].
         */
        val hardwareKeysEnabled: Boolean = false,
        /**
         * The security key of a loaded machine, or the key from the last enrollment, with all of its fields. When
         * [hardwareKeysEnabled] is false, the screen cannot change it, and "Save Host" keeps it as it is.
         */
        val hardwareKey: SshAuthMethod.HardwareSecurityKey? = null,
        /** The key type for the next enrollment. A change to a different type removes the enrolled credential. */
        val hardwareKeyType: HardwareKeyType = HardwareKeyType.SkSshEd25519,
        /** The connections that the app accepts for the security key. */
        val hardwareKeyTransport: HardwareKeyTransport = HardwareKeyTransport.UsbAndNfc,
        /** True while [Event.EnrollHardwareKey] waits for the security key. */
        val isEnrollingKey: Boolean = false,
        /** The cause when the last enrollment failed. */
        val enrollError: String? = null,
        val proxyJumpEnabled: Boolean = false,
        val bastionHost: String = "",
        val bastionPortText: String = "22",
        val bastionUser: String = "",
        val themeOverrideId: String? = null,
        val fontSizeSp: Float = HostMachine.DEFAULT_FONT_SIZE_SP,
        val validationError: String? = null,
    ) {
        val parsedPort: Int?
            get() = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }

        /**
         * False when [herdrSessionName] has text that Herdr does not accept. An empty field is valid: the machine
         * then uses the default session. See [HostMachine.isValidHerdrSessionName].
         */
        val isHerdrSessionNameValid: Boolean
            get() = herdrSessionName.trim().let { it.isEmpty() || HostMachine.isValidHerdrSessionName(it) }

        val canSave: Boolean
            get() = hostname.isNotBlank() && username.isNotBlank() && parsedPort != null && isHerdrSessionNameValid

        /** The login methods that the screen offers. */
        val authChoices: List<AuthType>
            get() = if (hardwareKeysEnabled) AUTH_CHOICES_WITH_HARDWARE_KEYS else AUTH_CHOICES

        /**
         * False when this build cannot log in with [authType]: a security key needs [hardwareKeysEnabled] and a
         * FIDO2 key type.
         */
        val isAuthTypeSupported: Boolean
            get() = authType != AuthType.HardwareSecurityKey || (hardwareKeysEnabled && hardwareKeyType.isFido2)

        /** The enrolled security key for [hardwareKeyType], or null when the user must enroll the key first. */
        val enrolledHardwareKey: SshAuthMethod.HardwareSecurityKey?
            get() = hardwareKey?.takeIf { it.isEnrolled && it.keyType == hardwareKeyType }

        /** The `authorized_keys` line of [enrolledHardwareKey], or null when the key has incorrect values. */
        val hardwareKeyPublicKeyLine: String?
            get() = enrolledHardwareKey?.let { key ->
                runCatching { SecurityKeyPublicKeys.authorizedKeyLine(key) }.getOrNull()
            }

        /** False when the app cannot log in with [authType]. The screen then blocks the connect. */
        val canConnect: Boolean
            get() = canSave && isAuthTypeSupported &&
                (authType != AuthType.HardwareSecurityKey || hardwareKeyPublicKeyLine != null)
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

        /** Shows the warning before the app replaces the key store key with a new key. */
        data object RegenerateKey : Event

        /**
         * Replaces the key store key with a new key that has the current key parameters, and shows the new
         * public key. Each host that has only the old public key then refuses the login.
         */
        data object ConfirmRegenerateKey : Event

        /** Closes the warning of [RegenerateKey] and keeps the key. */
        data object DismissRegenerateKey : Event
        data class UpdateRequireBiometric(val require: Boolean) : Event
        data class UpdatePasswordRef(val passwordRef: String) : Event

        /** Selects the key type for the next enrollment. Only a FIDO2 type has an effect. */
        data class UpdateHardwareKeyType(val keyType: HardwareKeyType) : Event

        /** Selects the connections that the app accepts for the security key. */
        data class UpdateHardwareKeyTransport(val transport: HardwareKeyTransport) : Event

        /**
         * Makes a new credential on the security key while the touch sheet shows. The screen then shows the public
         * key of the credential. "Save Host" keeps the credential.
         */
        data object EnrollHardwareKey : Event
        data class UpdateProxyJumpEnabled(val enabled: Boolean) : Event
        data class UpdateBastionHost(val host: String) : Event
        data class UpdateBastionPort(val portText: String) : Event
        data class UpdateBastionUser(val user: String) : Event
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
            is Event.UpdateAuthType -> updateState { current ->
                if (event.authType in current.authChoices) current.copy(authType = event.authType) else current
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
            is Event.ShowPublicKey -> loadPublicKey { alias, algorithm, requireBiometric ->
                authorizedKeyLine(alias, algorithm, requireBiometric)
            }
            Event.RegenerateKey -> if (sshPublicKeyProvider != null) {
                updateState { it.copy(showRegenerateKeyWarning = true) }
            }
            Event.ConfirmRegenerateKey -> {
                // The old public key is not valid after this step, so do not show it.
                updateState { it.copy(showRegenerateKeyWarning = false, publicKeyLine = null) }
                loadPublicKey { alias, algorithm, requireBiometric ->
                    regenerateAuthorizedKeyLine(alias, algorithm, requireBiometric)
                }
            }
            Event.DismissRegenerateKey -> updateState { it.copy(showRegenerateKeyWarning = false) }
            is Event.UpdatePasswordRef -> updateState { it.copy(passwordRef = event.passwordRef) }
            is Event.UpdateHardwareKeyType -> updateState { current ->
                if (!current.hardwareKeysEnabled || !event.keyType.isFido2 || current.isEnrollingKey) {
                    current
                } else {
                    current.copy(hardwareKeyType = event.keyType, enrollError = null)
                }
            }
            is Event.UpdateHardwareKeyTransport -> updateState { current ->
                if (current.hardwareKeysEnabled) current.copy(hardwareKeyTransport = event.transport) else current
            }
            Event.EnrollHardwareKey -> enrollHardwareKey()
            is Event.UpdateProxyJumpEnabled -> updateState { it.copy(proxyJumpEnabled = event.enabled) }
            is Event.UpdateBastionHost -> updateState { it.copy(bastionHost = event.host) }
            is Event.UpdateBastionPort -> updateState { it.copy(bastionPortText = event.portText) }
            is Event.UpdateBastionUser -> updateState { it.copy(bastionUser = event.user) }
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

    /** Shows the public key that [load] gives for the current key alias, key type, and biometric setting. */
    private fun loadPublicKey(
        load: suspend SshPublicKeyProvider.(alias: String, algorithm: KeyAlgorithm, requireBiometric: Boolean) -> String,
    ) {
        val provider = sshPublicKeyProvider ?: return
        val request = state.value
        if (request.isLoadingPublicKey) return
        val alias = request.keystoreAlias.trim().ifBlank { request.keyAlgorithm.defaultKeystoreAlias }
        val algorithm = request.keyAlgorithm
        updateState { it.copy(isLoadingPublicKey = true, publicKeyError = null, keyCreationFailure = null) }
        viewModelScope.launch {
            val result = runCatching { provider.load(alias, algorithm, request.requireBiometric) }
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

    /** Runs [HardwareKeySigner.enroll] for the selected key type and keeps the enrolled key in the state. */
    private fun enrollHardwareKey() {
        val signer = hardwareKeySigner ?: return
        val request = state.value
        if (!request.hardwareKeysEnabled || !request.hardwareKeyType.isFido2 || request.isEnrollingKey) return
        updateState { it.copy(isEnrollingKey = true, enrollError = null) }
        viewModelScope.launch {
            val result = try {
                Result.success(
                    signer.enroll(
                        machineId = request.id.ifBlank { NEW_MACHINE_ID },
                        keyType = request.hardwareKeyType,
                        transport = request.hardwareKeyTransport,
                        applicationId = request.hardwareKey?.applicationId
                            ?: SshAuthMethod.HardwareSecurityKey.DEFAULT_APPLICATION_ID,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            updateState { current ->
                val enrolled = result.getOrNull()
                if (enrolled != null) {
                    current.copy(isEnrollingKey = false, hardwareKey = enrolled, hardwareKeyType = enrolled.keyType)
                } else {
                    val error = result.exceptionOrNull()
                    current.copy(
                        isEnrollingKey = false,
                        enrollError = "$ENROLL_ERROR ${error?.message ?: error?.let { it::class.simpleName }}",
                    )
                }
            }
        }
    }

    /** Returns the security key that "Save Host" keeps for the state [current]. */
    private fun hardwareKeyToSave(current: State): SshAuthMethod.HardwareSecurityKey {
        val base = current.hardwareKey ?: SshAuthMethod.HardwareSecurityKey()
        return when {
            // The screen cannot change the key, so keep the loaded key with all of its fields.
            !current.hardwareKeysEnabled -> base
            base.keyType == current.hardwareKeyType -> base.copy(transport = current.hardwareKeyTransport)
            // The credential is for a different key type, so the machine must enroll the key again.
            else -> SshAuthMethod.HardwareSecurityKey(
                keyType = current.hardwareKeyType,
                transport = current.hardwareKeyTransport,
                applicationId = base.applicationId,
            )
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
        if (connectImmediately && !current.isAuthTypeSupported) {
            updateState { it.copy(validationError = UNSUPPORTED_AUTH_ERROR) }
            return
        }
        // The session name field shows the error. This check comes before the check of canConnect, because
        // canConnect is also false for a session name that Herdr does not accept.
        if (!current.isHerdrSessionNameValid) return
        if (connectImmediately && !current.canConnect) {
            updateState { it.copy(validationError = HARDWARE_KEY_NOT_ENROLLED_ERROR) }
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
            AuthType.HardwareSecurityKey -> hardwareKeyToSave(current)
        }

        val proxyJump = if (current.proxyJumpEnabled && current.bastionHost.isNotBlank()) {
            ProxyJumpConfig(
                bastionHost = current.bastionHost.trim(),
                bastionPort = current.bastionPortText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 22,
                bastionUser = current.bastionUser.trim().ifBlank { current.username.trim() },
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

        /** The error that "Save & Connect" shows for a security key with no enrolled credential. */
        const val HARDWARE_KEY_NOT_ENROLLED_ERROR: String = "Enroll the security key before you connect to this host."

        /** The start of the error when the enrollment of the security key failed. */
        const val ENROLL_ERROR: String = "The security key was not enrolled:"

        /** The machine ID of the enrollment prompt of a machine that has no ID yet. */
        internal const val NEW_MACHINE_ID: String = "new-machine"

        /** The login methods that the screen offers when the build has no security key support. */
        val AUTH_CHOICES: List<AuthType> = listOf(AuthType.KeystoreKey, AuthType.Password)

        /** The login methods that the screen offers when [State.hardwareKeysEnabled] is true. */
        val AUTH_CHOICES_WITH_HARDWARE_KEYS: List<AuthType> = AuthType.entries.toList()

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
            hardwareKeysEnabled: Boolean = false,
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
                    hardwareKeysEnabled = hardwareKeysEnabled,
                )
            }
            val hardwareAuth = existing.authMethod as? SshAuthMethod.HardwareSecurityKey
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
                hardwareKeysEnabled = hardwareKeysEnabled,
                hardwareKey = hardwareAuth,
                hardwareKeyType = hardwareAuth?.keyType ?: HardwareKeyType.SkSshEd25519,
                hardwareKeyTransport = hardwareAuth?.transport ?: HardwareKeyTransport.UsbAndNfc,
                proxyJumpEnabled = pj != null,
                bastionHost = pj?.bastionHost.orEmpty(),
                bastionPortText = (pj?.bastionPort ?: 22).toString(),
                bastionUser = pj?.bastionUser.orEmpty(),
                themeOverrideId = existing.themeOverrideId,
                fontSizeSp = existing.fontSizeSp,
            )
        }
    }
}
