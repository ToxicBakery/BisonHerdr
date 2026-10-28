package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSchException
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
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

    override suspend fun authenticate(machine: HostMachine, route: ResolvedSshRoute): SshAuthResult =
        client.connectAndVerify(machine, route).fold(
            onSuccess = { SshAuthResult.Success },
            onFailure = ::toAuthResult,
        )

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
         * [SshAuthResult.KeyMissing]. A refused login is [SshAuthResult.AuthFailed]. An [IOException] at
         * any level of the cause chain is [SshAuthResult.NetworkError]. A [JSchException] with a message that
         * starts with a text of [CLOSED_CONNECTION_PREFIXES] is also [SshAuthResult.NetworkError]. Only the message
         * of [error] counts, because a cause with that text can be in an error that tells a different kind, for
         * example a channel that the bastion refuses ([BastionChannelProxy.openFailure]). All other errors are
         * [SshAuthResult.Failed].
         */
        fun toAuthResult(error: Throwable): SshAuthResult {
            val message = error.message.orEmpty()
            val missingKey = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)
                .filterIsInstance<MissingKeyException>()
                .firstOrNull()
            return when {
                error is HostKeyRejectedException -> error.rejection

                missingKey != null -> SshAuthResult.KeyMissing(missingKey.keyAlias)

                error is JSchException && AUTH_FAILURE_PREFIXES.any { message.startsWith(it) } ->
                    SshAuthResult.AuthFailed(message)

                error is JSchException && CLOSED_CONNECTION_PREFIXES.any { message.startsWith(it) } ->
                    SshAuthResult.NetworkError(error)

                generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is IOException } ->
                    SshAuthResult.NetworkError(error)

                else -> SshAuthResult.Failed(error)
            }
        }
    }
}
