package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlinx.coroutines.delay

/**
 * An [SshBackend] that records each call and opens no connection. [authenticate] returns [authResult].
 * A test can change [authResult] between two calls.
 *
 * [checkLiveness] waits for [livenessDelay] and then returns [liveness]. [openSurface] runs [onOpenSurface], and
 * [sendEndpointCommand] runs [onSendEndpointCommand], so that a test can make the step throw or wait.
 * [sessionEvents] is the receiver that [setSessionEvents] got, so that a test can send the events of a
 * reconnect loop. [withFrameLock] runs [onFrameLockEnd] after its block, so that a test can read the state at the
 * end of the lock.
 */
internal class RecordingSshBackend(
    @Volatile var authResult: SshAuthResult = SshAuthResult.Success,
    @Volatile var liveness: LivenessResult = LivenessResult.Live,
    private val onOpenSurface: (suspend () -> Unit)? = null,
    private val onSendEndpointCommand: (suspend () -> Unit)? = null,
) : SshBackend {

    /** One entry for each call: the method name and the machine id, for example `resize workbox`. */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    /** The time that each liveness check takes. */
    @Volatile
    var livenessDelay: Duration = Duration.ZERO

    /** The receiver of the reconnect events, or null before [setSessionEvents]. */
    @Volatile
    var sessionEvents: SessionEvents? = null
        private set

    override suspend fun authenticate(machine: HostMachine, route: ResolvedSshRoute): SshAuthResult {
        calls += "authenticate ${machine.id}"
        return authResult
    }

    override suspend fun negotiateBackbone(machine: HostMachine, route: ResolvedSshRoute): ActiveBackbone {
        calls += "negotiateBackbone ${machine.id}"
        return ActiveBackbone.SshEndpoint(rttMs = 1)
    }

    override suspend fun openSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
        backbone: ActiveBackbone,
        isSurfaceActive: () -> Boolean,
    ) {
        calls += "openSurface ${machine.id}"
        onOpenSurface?.invoke()
    }

    override suspend fun sendEndpointCommand(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ): String? {
        calls += "sendEndpointCommand ${machine.id}"
        onSendEndpointCommand?.invoke()
        return null
    }

    override suspend fun sendInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean {
        calls += "sendInput ${machine.id}"
        return true
    }

    override fun resize(machineId: String, cols: UShort, rows: UShort) {
        calls += "resize $machineId"
    }

    override fun disconnect(machineId: String) {
        calls += "disconnect $machineId"
    }

    override fun setSessionEvents(events: SessionEvents) {
        sessionEvents = events
    }

    override suspend fun checkLiveness(machineId: String, timeout: Duration): LivenessResult {
        calls += "checkLiveness $machineId"
        delay(livenessDelay)
        return liveness
    }

    override fun reconnectNow(machineId: String, deadSessionId: Long?) {
        calls += "reconnectNow $machineId $deadSessionId"
    }

    override fun closeSockets(machineId: String) {
        calls += "closeSockets $machineId"
    }

    override suspend fun refreshSurface(machine: HostMachine, route: ResolvedSshRoute) {
        calls += "refreshSurface ${machine.id}"
    }

    /** Runs at the end of each [withFrameLock] call, before the call releases the lock. */
    @Volatile
    var onFrameLockEnd: (machineId: String) -> Unit = {}

    override fun <T> withFrameLock(machineId: String, block: () -> T): T = block().also { onFrameLockEnd(machineId) }
}
