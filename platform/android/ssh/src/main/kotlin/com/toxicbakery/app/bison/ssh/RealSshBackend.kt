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
import java.io.IOException
import kotlin.time.Duration

/**
 * The [SshBackend] that connects to a real Herdr host over JSch through [RealHerdrSshClient].
 *
 * Release builds use only this backend. Debug builds use it for all machines that the fake server did not make.
 *
 * Each session checks the host key against the [KnownHostsStore] before it sends credentials. [authenticate]
 * returns [SshAuthResult.HostKeyUnknown] or [SshAuthResult.HostKeyChanged] when the check stops the session.
 *
 * A password login reads the password from the [SecretStore]. When the store has no password, [authenticate]
 * returns [SshAuthResult.Failed] with a [MissingSecretException], and the client opens no connection.
 *
 * After a connect, the client reconnects on its own when the session closes, and sends the events of the loop to
 * the receiver of [setSessionEvents]. See [SessionSupervisor].
 *
 * Before the login with a key that has `requireBiometric`, [authenticate] asks the user to unlock the key
 * through [SshStateManager.requestUserAuth]. It waits for the answer for [USER_AUTH_TIMEOUT_MS] at most. The
 * bastion of a ProxyJump machine logs in with the same key, so the one prompt also unlocks the bastion login.
 */
class RealSshBackend internal constructor(
    internal val client: RealHerdrSshClient,
    private val sshStateManager: SshStateManager,
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
    ) : this(
        client = RealHerdrSshClient(
            sshDebugLog = sshDebugLog,
            endpointSnapshotBus = endpointSnapshotBus,
            paneSurfaceBus = paneSurfaceBus,
            semanticNotificationBus = semanticNotificationBus,
            knownHostsStore = knownHostsStore,
            keystoreKeyManagerProvider = { keystoreKeyManager },
            secretStore = secretStore,
        ),
        sshStateManager = sshStateManager,
    )

    override suspend fun authenticate(machine: HostMachine, route: ResolvedSshRoute): SshAuthResult {
        val keyAuth = machine.authMethod as? SshAuthMethod.KeystoreKey
        // Do not ask the user for a key that does not need a user authentication.
        val promptOutcome = if (keyAuth != null && keyAuth.requireBiometric) {
            sshStateManager.requestUserAuth(
                machineId = machine.id,
                machineName = machine.name.ifBlank { machine.displayAddress },
                keyAlias = keyAuth.keyAlias,
                timeoutMs = USER_AUTH_TIMEOUT_MS,
            )
        } else {
            null
        }
        if (keyAuth != null && promptOutcome != null && promptOutcome != UserAuthOutcome.Confirmed) {
            // The key stays locked, so the login cannot sign. The app opens no connection.
            return SshAuthResult.UserAuthRequired(keyAuth.keyAlias, promptOutcome)
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

    override suspend fun negotiateBackbone(machine: HostMachine, route: ResolvedSshRoute): ActiveBackbone {
        sshStateManager.setConnectionProgress(machine.id, ConnectionProgress.VerifyingUdpOrHello)
        val hasMosh = if (machine.transportMode != TransportMode.SshEndpointOnly) {
            client.checkMoshServerAvailable(machine, route)
        } else {
            false
        }
        return if (hasMosh) {
            ActiveBackbone.MoshUdp(
                udpPort = machine.parsedMoshPortRange().first,
                rttMs = 9,
                localEchoEnabled = true,
                roamedCount = 0,
            )
        } else {
            ActiveBackbone.SshEndpoint(
                rttMs = 12,
                fallbackReason = null,
            )
        }
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
         * level of the cause chain is [SshAuthResult.UserAuthRequired]. A refused login is
         * [SshAuthResult.AuthFailed]. An [IOException] at any level of the cause chain is
         * [SshAuthResult.NetworkError]. A [JSchException] with a message that starts with a text of
         * [CLOSED_CONNECTION_PREFIXES] is also [SshAuthResult.NetworkError]. Only the message of [error] counts,
         * because a cause with that text can be in an error that tells a different kind, for example a channel
         * that the bastion refuses ([BastionChannelProxy.openFailure]). All other errors are [SshAuthResult.Failed].
         */
        fun toAuthResult(error: Throwable): SshAuthResult {
            val message = error.message.orEmpty()
            val causes = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            val missingKey = causes.filterIsInstance<MissingKeyException>().firstOrNull()
            val userAuth = causes.firstOrNull { it is UserAuthRequiredException || it is UserNotAuthenticatedException }
            return when {
                error is HostKeyRejectedException -> error.rejection

                missingKey != null -> SshAuthResult.KeyMissing(missingKey.keyAlias)

                userAuth != null -> SshAuthResult.UserAuthRequired((userAuth as? UserAuthRequiredException)?.keyAlias)

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
