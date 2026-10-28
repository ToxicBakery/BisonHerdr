package com.toxicbakery.app.bison.ssh

import android.security.keystore.UserNotAuthenticatedException
import com.jcraft.jsch.JSchException
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.store.KnownHostsStore
import com.toxicbakery.app.bison.store.MachineStore
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [SshBackend] that connects to a real Herdr host over JSch through [RealHerdrSshClient].
 *
 * Release builds use only this backend. Debug builds use it for all machines that the fake server did not make.
 *
 * Each session checks the host key against the [KnownHostsStore] before it sends credentials. [authenticate]
 * returns [SshAuthResult.HostKeyUnknown] or [SshAuthResult.HostKeyChanged] when the check stops the session.
 *
 * A password login reads the password from the [SecretStore]. When the store has no password, the client asks the
 * user for it through [SshStateManager.requestKeyboardInteractive], and can save it in the [SecretStore] and the
 * [MachineStore]. When no UI is open, [authenticate] returns [SshAuthResult.Failed] with a
 * [MissingSecretException], and the client opens no connection.
 *
 * The `keyboard-interactive` questions of a host also go to the user through
 * [SshStateManager.requestKeyboardInteractive], with the time limit [keyboardInteractiveTimeout]. When no activity
 * runs [SshStateManager.hostUserAuthPrompts], the login stops at once with [SshAuthResult.InputRequired]. A cancel,
 * also from [SshStateManager.cancelUserAuth] at a disconnect, gives [SshAuthResult.InputCancelled].
 *
 * After a connect, the client reconnects on its own when the session closes, and sends the events of the loop to
 * the receiver of [setSessionEvents]. See [SessionSupervisor].
 *
 * Before the login with a stored key that needs a user authentication, [authenticate] asks the user to unlock
 * the key through [SshStateManager.requestUserAuth]. The stored key decides, not the `requireBiometric` setting
 * of the machine, because machines can share a key alias. [authenticate] waits for the answer for
 * [USER_AUTH_TIMEOUT_MS] at most. The bastion of a ProxyJump machine logs in with the same key, so the one
 * prompt also unlocks the bastion login.
 *
 * The backbone is always [ActiveBackbone.SshEndpoint], because this version has no Mosh transport (issue #22). The
 * client measures the poll time, and the backend puts its average in the backbone with
 * [SshStateManager.setRoundTripTime]. See [RealHerdrSshClient.refreshSnapshotAndSurface].
 */
class RealSshBackend internal constructor(
    internal val client: RealHerdrSshClient,
    private val sshStateManager: SshStateManager,
    private val keystoreKeyManager: AndroidKeystoreKeyManager,
) : SshBackend {

    constructor(
        sshStateManager: SshStateManager,
        sshDebugLog: SshDebugLog,
        endpointSnapshotBus: EndpointSnapshotBus,
        paneSurfaceBus: PaneSurfaceBus,
        semanticNotificationBus: SemanticNotificationBus,
        keystoreKeyManager: AndroidKeystoreKeyManager,
        knownHostsStore: KnownHostsStore,
        secretStore: SecretStore,
        machineStore: MachineStore? = null,
        keyboardInteractiveTimeout: Duration = KEYBOARD_INTERACTIVE_TIMEOUT,
    ) : this(
        client = RealHerdrSshClient(
            sshDebugLog = sshDebugLog,
            endpointSnapshotBus = endpointSnapshotBus,
            paneSurfaceBus = paneSurfaceBus,
            semanticNotificationBus = semanticNotificationBus,
            knownHostsStore = knownHostsStore,
            keystoreKeyManagerProvider = { keystoreKeyManager },
            secretStore = secretStore,
            userPrompter = { prompt -> sshStateManager.requestKeyboardInteractive(prompt, keyboardInteractiveTimeout) },
            machineStore = machineStore,
        ),
        sshStateManager = sshStateManager,
        keystoreKeyManager = keystoreKeyManager,
    )

    init {
        client.roundTripListener = sshStateManager::setRoundTripTime
    }

    override suspend fun authenticate(machine: HostMachine, route: ResolvedSshRoute): SshAuthResult {
        val keyAuth = machine.authMethod as? SshAuthMethod.KeystoreKey
        var promptOutcome: UserAuthOutcome? = null
        if (keyAuth != null) {
            val keyUserAuth = withContext(Dispatchers.IO) { keystoreKeyManager.storedKeyUserAuth(keyAuth.keyAlias) }
            if (keyUserAuth == KeyUserAuth.EachSign) {
                // No prompt can unlock this key, so do not show one. The app opens no connection.
                return SshAuthResult.UserAuthRequired(keyAuth.keyAlias, keyNeedsUserForEachSign = true)
            }
            // The stored key decides. When the key store does not tell, the machine setting decides. A missing key
            // gets no prompt: the login then gives KeyMissing.
            val needsPrompt = keyUserAuth == KeyUserAuth.TimeBound ||
                (keyUserAuth == KeyUserAuth.Unknown && keyAuth.requireBiometric)
            if (needsPrompt) {
                val outcome = sshStateManager.requestUserAuth(
                    machineId = machine.id,
                    machineName = machine.name.ifBlank { machine.displayAddress },
                    keyAlias = keyAuth.keyAlias,
                    timeoutMs = USER_AUTH_TIMEOUT_MS,
                )
                if (outcome != UserAuthOutcome.Confirmed) {
                    // The key stays locked, so the login cannot sign. The app opens no connection.
                    return SshAuthResult.UserAuthRequired(keyAuth.keyAlias, outcome)
                }
                promptOutcome = outcome
            }
        }
        return client.connectAndVerify(machine, route).fold(
            onSuccess = { SshAuthResult.Success },
            onFailure = { error ->
                when (val result = toAuthResult(error)) {
                    is SshAuthResult.UserAuthRequired ->
                        result.copy(keyAlias = result.keyAlias ?: keyAuth?.keyAlias, promptOutcome = promptOutcome)
                    else -> result
                }
            },
        )
    }

    /**
     * Returns the SSH endpoint bridge for each [TransportMode], because this version has no Mosh transport
     * (issue #22). When the mode permits Mosh, the backend checks for `mosh-server` on the host. The fallback reason
     * comes from [MoshSessionEngine.fallbackReasonFor], and the backend writes it to the debug log.
     *
     * The poll time is [ActiveBackbone.UNMEASURED_RTT_MS] until the poll measures the first batch.
     */
    override suspend fun negotiateBackbone(machine: HostMachine, route: ResolvedSshRoute): ActiveBackbone.SshEndpoint {
        sshStateManager.setConnectionProgress(machine.id, ConnectionProgress.VerifyingUdpOrHello)
        val hasMoshServer = machine.transportMode != TransportMode.SshEndpointOnly &&
            client.checkMoshServerAvailable(machine, route)
        val reason = MoshSessionEngine.fallbackReasonFor(machine.transportMode, hasMoshServer)
        reason?.let { client.recordTransport(machine.id, it) }
        return ActiveBackbone.SshEndpoint(rttMs = ActiveBackbone.UNMEASURED_RTT_MS, fallbackReason = reason)
    }

    override suspend fun openSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
        backbone: ActiveBackbone,
        isSurfaceActive: () -> Boolean,
    ) {
        client.refreshSnapshotAndSurface(machine, route)
        client.startLivePolling(machine, route, isSurfaceActive)
    }

    override suspend fun sendEndpointCommand(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ): String? = client.executeEndpointRpc(machine, route, request)

    override suspend fun sendInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean = client.sendPaneInput(machine, route, paneId, event)

    override fun resize(machineId: String, cols: UShort, rows: UShort) {
        client.resizeViewport(machineId, cols, rows)
    }

    override fun <T> withFrameLock(machineId: String, block: () -> T): T = client.withFrameLock(machineId, block)

    override fun disconnect(machineId: String) {
        client.disconnect(machineId)
    }

    override fun setSessionEvents(events: SessionEvents) {
        client.sessionEvents = events
    }

    override suspend fun checkLiveness(machineId: String, timeout: Duration): LivenessResult =
        client.checkLiveness(machineId, timeout)

    override fun reconnectNow(machineId: String, deadSessionId: Long?) {
        client.reconnectNow(machineId, deadSessionId)
    }

    override fun closeSockets(machineId: String) {
        client.closeSockets(machineId)
    }

    override suspend fun refreshSurface(machine: HostMachine, route: ResolvedSshRoute) {
        client.refreshSnapshotAndSurface(machine, route)
    }

    internal companion object {
        /** The maximum time that [authenticate] waits for the answer of the user to a user authentication prompt. */
        const val USER_AUTH_TIMEOUT_MS: Long = 60_000L

        /**
         * The default time limit for the answers of the user to one prompt. It is shorter than the login time limit
         * of common SSH servers (120 seconds for OpenSSH), so that the server has not closed the connection yet.
         */
        val KEYBOARD_INTERACTIVE_TIMEOUT: Duration = 90.seconds

        /** JSch starts the message of a refused login with one of these texts. */
        private val AUTH_FAILURE_PREFIXES = listOf("Auth fail", "Auth cancel")

        /**
         * JSch starts the message of these errors with one of these texts. The connection or the session closed, but
         * the error has no [IOException] cause.
         */
        private val CLOSED_CONNECTION_PREFIXES = listOf(
            "connection is closed by foreign host",
            "channel is not opened.",
            "session is down",
        )

        /** The maximum number of causes that [toAuthResult] examines. */
        private const val MAX_CAUSE_DEPTH = 16

        /**
         * Maps a login [error] from JSch to its [SshAuthResult] kind. A rejected host key is its
         * [SshAuthResult.HostKeyRejected] kind. A [MissingKeyException] at any level of the cause chain is
         * [SshAuthResult.KeyMissing]. A [UserAuthRequiredException] or a [UserNotAuthenticatedException] at any
         * level of the cause chain is [SshAuthResult.UserAuthRequired]. A [UserInputException] at any level of the
         * cause chain gives its [UserInputException.result]. A refused login is [SshAuthResult.AuthFailed]. An
         * [IOException] at any level of the cause chain is [SshAuthResult.NetworkError]. A [JSchException] with a
         * message that starts with a text of [CLOSED_CONNECTION_PREFIXES] is also [SshAuthResult.NetworkError]. Only
         * the message of [error] counts, because a cause with that text can be in an error that tells a different
         * kind, for example a channel that the bastion refuses ([BastionChannelProxy.openFailure]). All other errors
         * are [SshAuthResult.Failed].
         */
        fun toAuthResult(error: Throwable): SshAuthResult {
            val message = error.message.orEmpty()
            val causes = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            val missingKey = causes.filterIsInstance<MissingKeyException>().firstOrNull()
            val userAuth = causes.firstOrNull { it is UserAuthRequiredException || it is UserNotAuthenticatedException }
            val userInput = causes.filterIsInstance<UserInputException>().firstOrNull()
            return when {
                error is HostKeyRejectedException -> error.rejection

                missingKey != null -> SshAuthResult.KeyMissing(missingKey.keyAlias)

                userAuth != null -> SshAuthResult.UserAuthRequired((userAuth as? UserAuthRequiredException)?.keyAlias)

                userInput != null -> userInput.result

                error is JSchException && AUTH_FAILURE_PREFIXES.any { message.startsWith(it) } ->
                    SshAuthResult.AuthFailed(message)

                error is JSchException && CLOSED_CONNECTION_PREFIXES.any { message.startsWith(it) } ->
                    SshAuthResult.NetworkError(error)

                causes.any { it is IOException } -> SshAuthResult.NetworkError(error)

                else -> SshAuthResult.Failed(error)
            }
        }
    }
}
