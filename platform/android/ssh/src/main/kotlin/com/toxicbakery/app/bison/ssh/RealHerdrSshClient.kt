package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.FeatureFlags
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientKeyEvent
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.ClientShellPane
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.protocol.ClientShellTab
import com.toxicbakery.app.bison.protocol.ClientShellWorkspace
import com.toxicbakery.app.bison.protocol.CursorShape
import com.toxicbakery.app.bison.protocol.CursorState
import com.toxicbakery.app.bison.protocol.FrameData
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.PaneLayoutPane
import com.toxicbakery.app.bison.protocol.PaneLayoutSnapshot
import com.toxicbakery.app.bison.protocol.PaneLayoutSplit
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.PaneSurfacePane
import com.toxicbakery.app.bison.protocol.PaneSurfaceScroll
import com.toxicbakery.app.bison.protocol.PaneSurfaceSplitDirection
import com.toxicbakery.app.bison.protocol.SurfaceRect
import com.toxicbakery.app.bison.protocol.deriveSplitsFromLayout
import com.toxicbakery.app.bison.store.KnownHostsStore
import com.toxicbakery.app.bison.store.MachineStore
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.util.ArrayDeque
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Live SSH2 client (powered by JSch) that connects to unmodified upstream Herdr servers,
 * fetches real `herdr api snapshot` topology (workspaces, tabs, panes, coding agents, BSP layouts),
 * streams visible ANSI pane buffers via `herdr remote-api-bridge` (`pane.read`), and dispatches
 * real interactive keystrokes (`pane.send_text`, `pane.send_keys`, `pane.send_input`), prompts,
 * and workspace/tab/pane focus mutations.
 *
 * Uses two dedicated persistent POSIX `sh` worker channels per machine ([WorkerLane.Input] and [WorkerLane.Poll])
 * over `TCP_NODELAY` sockets with client-side optimistic local echo (`<1ms`), keystroke coalescing, and
 * focused-pane-only reads so interactive typing never spawns per-key SSH channels or blocks behind background polls.
 *
 * Each `herdr` command has `--session <name>` when the machine has a Herdr session name. See [HerdrCommand].
 *
 * Each session checks the server host key against [knownHostsStore] through [KnownHostsRepository] before it
 * sends credentials. An unknown or changed key stops the session with a [HostKeyRejectedException].
 *
 * A password machine keeps a [SecretRef] value. The client reads the password from [secretStore] before each
 * connection, and clears its copy after the connection. When the store has no password, the client asks the user for
 * it through [userPrompter] before it opens a connection. With no UI, the session stops with a
 * [MissingSecretException]. When the user wants to save the typed password, the client saves it after a login that
 * passes with no more questions to the user. See [openSessionLocked] and [savePassword].
 *
 * Each session has a [KeyboardInteractiveUserInfo]. It answers the `keyboard-interactive` questions of the host with
 * the password of the machine or through [userPrompter], and it never accepts a host key.
 *
 * A machine with a ProxyJump bastion gets two sessions. The client logs in to the bastion first, with the bastion
 * user and the credentials of the machine. It then logs in to the target over a `direct-tcpip` channel of the
 * bastion session ([BastionChannelProxy]), and runs all commands on the target. Each session checks the host key of
 * its own `host:port`.
 *
 * Only a connect step opens a session on demand: from [connectAndVerify] to [startLivePolling]. After the connect,
 * [startLivePolling] makes a [SessionSupervisor] for the machine, and only the supervisor opens its sessions. After
 * [disconnect], no command opens a session. When a session closes, the supervisor reconnects in
 * [reconnectScope] with the delays from [reconnectDelays]. It sends its events to [sessionEvents]. A command that
 * finds a closed session waits only for the attempt that runs now, and then fails.
 *
 * Each socket gets its address from [localNetworkGuard]. When Android 17 stops the connection to the host because the
 * app does not have the local network permission, the guard throws a [LocalNetworkPermissionException]. It throws it
 * before the socket opens, or after a connect timeout to a private address. The reconnect loop then stops for the
 * user.
 *
 * [refreshSnapshotAndSurface] measures the poll time with [timeSource]: the time of its first command batch. A
 * [RoundTripMeter] keeps the average for each machine, and gives it to [roundTripListener] at most one time each
 * second. A connect, a disconnect, and a reconnect of the supervisor start a new average.
 *
 * A machine with a FIDO2 security key logs in with a [HardwareKeyJschIdentity] over [hardwareKeySigner]. The user
 * touches the key for each new session. A ProxyJump machine thus needs two touches: one for the bastion and one for
 * the target. When [hardwareKeySigner] is null, the client refuses such a machine before the network connection.
 */
internal class RealHerdrSshClient(
    private val sshDebugLog: SshDebugLog,
    private val endpointSnapshotBus: EndpointSnapshotBus,
    private val paneSurfaceBus: PaneSurfaceBus,
    private val semanticNotificationBus: SemanticNotificationBus,
    private val knownHostsStore: KnownHostsStore,
    private val keystoreKeyManagerProvider: () -> AndroidKeystoreKeyManager = { AndroidKeystoreKeyManager() },
    /** Gives the passwords of the machines that keep a [SecretRef] value. */
    private val secretStore: SecretStore,
    private val remoteBatchExecutor: (
        suspend (
            machine: HostMachine,
            route: ResolvedSshRoute,
            lane: WorkerLane,
            commands: List<RemoteShellCommand>,
        ) -> String
    )? = null,
    /** Runs the reconnect loops. A test gives a scope with virtual time. */
    private val reconnectScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Gives the delay of a reconnect loop after a number of failed attempts in sequence. */
    private val reconnectDelays: (failures: Int) -> Duration = ReconnectBackoff()::delayAfter,
    /**
     * Asks the user the questions of a login and waits for the answers: the `keyboard-interactive` questions of a
     * host, and the password of a machine with no stored password. The default has no UI.
     */
    private val userPrompter: suspend (KeyboardInteractivePrompt) -> KeyboardInteractiveOutcome =
        { KeyboardInteractiveOutcome.NoUi },
    /** Gets the new password reference of a machine when the user saves a typed password. See [savePassword]. */
    private val machineStore: MachineStore? = null,
    /** Measures the poll time. A test gives a source with virtual time. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /**
     * Puts a frame of a machine on [paneSurfaceBus]. The client calls it in the frame lock of the machine (see
     * [frameLocks]). A test gives a function that stops a step at this point.
     */
    private val framePublisher: (machineId: String, frame: PaneSurfaceFrame) -> Unit = paneSurfaceBus::publish,
    /** Gives the address of each socket, and finds the connections that the local network protection stops. */
    internal val localNetworkGuard: LocalNetworkGuard,
    /**
     * Signs the login of a machine with a FIDO2 security key. It is null when [FeatureFlags.hardwareSecurityKeys] is
     * false. Then the client refuses such a machine.
     */
    private val hardwareKeySigner: HardwareKeySigner? = null,
) {
    internal enum class WorkerLane {
        Input,
        Poll,
    }

    internal sealed interface RemoteShellCommand {
        data class Exec(val command: String) : RemoteShellCommand
        data class ApiBridgeLine(val ndjsonLine: String) : RemoteShellCommand
    }

    internal data class PendingPaneInput(
        val seq: Long,
        val paneId: String,
        val event: ClientPaneInputEvent,
    )

    internal sealed interface OptimisticEdit {
        val seq: Long
        val paneId: String
        val createdAtMs: Long

        data class InsertText(
            override val seq: Long,
            override val paneId: String,
            val text: String,
            override val createdAtMs: Long = System.currentTimeMillis(),
        ) : OptimisticEdit

        data class Backspace(
            override val seq: Long,
            override val paneId: String,
            override val createdAtMs: Long = System.currentTimeMillis(),
        ) : OptimisticEdit

        data class Newline(
            override val seq: Long,
            override val paneId: String,
            override val createdAtMs: Long = System.currentTimeMillis(),
        ) : OptimisticEdit
    }

    private data class ClientSelectionState(
        @Volatile var focusedWorkspaceId: String? = null,
        @Volatile var focusedTabId: String? = null,
        @Volatile var focusedPaneId: String? = null,
    )

    private class SgrState(
        val fg: UInt,
        val bg: UInt,
        val mod: UShort,
    )

    private data class CachedSurfaceInputs(
        val snapshot: ClientShellSnapshot,
        val layout: PaneLayoutSnapshot?,
        val ansiByPane: Map<String, String>,
    )

    /** Drops all bytes. `OutputStream.nullOutputStream()` needs API 33, and the minimum SDK of the app is 31. */
    private object DiscardOutputStream : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private class PersistentShellWorker(
        val channel: ChannelExec,
        val stdin: OutputStream,
        val stdout: BufferedReader,
    ) {
        val isAlive: Boolean
            get() = !channel.isClosed && channel.isConnected

        /** True after the first batch. Only the holder of the lane lock reads and writes it. */
        var ranBatch: Boolean = false

        fun close() {
            runCatching { stdin.close() }
            runCatching { stdout.close() }
            runCatching { channel.disconnect() }
        }
    }

    /**
     * Opens the sockets of the sessions. [localNetworkGuard] gives the address before the socket opens, and the error
     * of a failed connect.
     */
    private class LowLatencySocketFactory(private val localNetworkGuard: LocalNetworkGuard) : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val address = localNetworkGuard.socketAddressFor(host, port)
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                runCatching { socket.trafficClass = IPTOS_LOWDELAY }
                socket.connect(address, SOCKET_CONNECT_TIMEOUT_MS)
            } catch (error: IOException) {
                runCatching { socket.close() }
                throw localNetworkGuard.connectFailure(address, KnownHost.formatHostPort(host, port), error)
            }
            return socket
        }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val socketFactory = LowLatencySocketFactory(localNetworkGuard)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val knownHostsRepository = KnownHostsRepository(knownHostsStore)

    /** The session to the target of each machine. All commands use this session. */
    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * The bastion session of each machine with a ProxyJump bastion. The session of the machine in [sessions] sends
     * all of its data over a channel of this session. Thus [liveSession] gives a session only while its bastion
     * session is open, and [closeSessions] closes the target session first.
     */
    private val bastionSessions = ConcurrentHashMap<String, Session>()
    private val sessionOpenLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * The supervisor of each machine that completed a connect. While a machine has a supervisor, only the supervisor
     * opens its sessions. See [SessionSupervisor].
     */
    private val supervisors = ConcurrentHashMap<String, SessionSupervisor>()

    /**
     * The machines at a connect step: from the start of [connectAndVerify] to [startLivePolling] or [disconnect]. A
     * command opens a session on demand only for these machines.
     */
    private val connectSteps: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The password reference that [savePassword] gave to each machine with no valid reference, by machine id: the
     * old value and the new value. A reconnect uses a [HostMachine] from before the save, so [resolvePassword] uses
     * the new value in place of the old value.
     */
    private val savedPasswordRefs = ConcurrentHashMap<String, Pair<String, String>>()

    /** The id of each open target session. Each new session gets a new id. See [LivenessResult.Dead]. */
    private val sessionIds = ConcurrentHashMap<Session, Long>()
    private val sessionIdCounter = AtomicLong(1L)

    /** Receives the events of the reconnect loops. [RealSshBackend.setSessionEvents] sets it. */
    @Volatile
    internal var sessionEvents: SessionEvents? = null

    /**
     * Receives the average poll time of a machine in whole milliseconds. It returns true when it changed the
     * value that the app shows. [RealSshBackend] sets it.
     */
    @Volatile
    internal var roundTripListener: ((machineId: String, rttMs: Int) -> Boolean)? = null

    /** Keeps the average poll time of each machine. See [refreshSnapshotAndSurface]. */
    private val roundTripMeter = RoundTripMeter(timeSource)

    /** Sends the events of each supervisor to [sessionEvents] when it is set. */
    private val supervisorEvents = object : SessionEvents {
        override fun onReconnecting(machineId: String, cause: Throwable?) {
            sessionEvents?.onReconnecting(machineId, cause)
        }

        override fun onReconnectFailed(
            machineId: String,
            failures: Int,
            result: SshAuthResult.NetworkError,
            nextDelay: Duration,
        ) {
            sessionEvents?.onReconnectFailed(machineId, failures, result, nextDelay)
        }

        override suspend fun onReconnected(machineId: String) {
            // The poll time of the old session does not apply to the new session. The UI shows no value until the
            // first sample of the new session.
            roundTripMeter.reset(machineId)
            roundTripListener?.invoke(machineId, ActiveBackbone.UNMEASURED_RTT_MS)
            sessionEvents?.onReconnected(machineId)
        }

        override suspend fun onNeedsUser(machineId: String, result: SshAuthResult) {
            sessionEvents?.onNeedsUser(machineId, result)
        }
    }

    private val inputLaneLocks = ConcurrentHashMap<String, Mutex>()
    private val pollLaneLocks = ConcurrentHashMap<String, Mutex>()
    private val inputWorkers = ConcurrentHashMap<String, PersistentShellWorker>()
    private val pollWorkers = ConcurrentHashMap<String, PersistentShellWorker>()
    private val pollJobs = ConcurrentHashMap<String, Job>()
    private val inputFlushJobs = ConcurrentHashMap<String, Job>()
    private val inputFlushSignals = ConcurrentHashMap<String, Channel<Unit>>()
    private val pendingInputQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingPaneInput>>()
    private val optimisticEdits = ConcurrentHashMap<String, ArrayDeque<OptimisticEdit>>()
    private val selectionState = ConcurrentHashMap<String, ClientSelectionState>()
    private val cachedInputs = ConcurrentHashMap<String, CachedSurfaceInputs>()
    private val viewportSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** The machines whose last poll got no snapshot. See [recordMissingSnapshot]. */
    private val machinesWithoutSnapshot: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val revisionCounter = AtomicLong(10L)
    private val inputSequence = AtomicLong(0L)
    private val lastInputConfirmedSeq = ConcurrentHashMap<String, Long>()
    private val shellBatchCounter = AtomicLong(1L)

    /** Scroll position of each pane from the Herdr snapshot, by machine and then by pane. */
    private val paneScrolls = ConcurrentHashMap<String, Map<String, PaneSurfaceScroll>>()

    private val paneScrollSequence = AtomicLong(0L)
    private val pollSequence = AtomicLong(0L)
    private val paneScrollSignals = ConcurrentHashMap<String, Channel<Unit>>()
    private val paneScrollJobs = ConcurrentHashMap<String, Job>()

    /**
     * The frame data of each machine. Each [MachineFrames] object is also the frame lock of its machine.
     *
     * A step that changes the data of a frame and then publishes a frame does both in this lock. The data is
     * [cachedInputs], [paneScrolls], [viewportSizes], [selectionState], the optimistic edits, and the fields of
     * [MachineFrames]. All steps of this client that publish on [paneSurfaceBus] or [endpointSnapshotBus] use the lock.
     * [SshTransportManager.setSurfaceLease] uses it through [withFrameLock]. A poll also compares its number with
     * [MachineFrames.minPollSeq], so that an older poll cannot replace the data of a newer poll or a newer selection.
     * Thus a frame of these steps cannot replace a newer frame of these steps.
     *
     * The lock does not cover a frame of [MoshUdpEngine]. This version has no Mosh session (issue #22).
     *
     * Each machine has its own lock, so a step does not wait for the frames of a different machine. No step holds the
     * lock across network I/O. In the app, [sendPaneInput] and [resizeViewport] run on the serial fast-input queue of
     * the session service, on `Dispatchers.Default`. A Java monitor is not fair, so such a step can wait for more than
     * one frame build of the same machine. A frame build has no network I/O. [disconnect] clears the data in the lock,
     * and stops the polls that still run. It keeps the lock, so that a step that still runs and a new step use the
     * same lock.
     */
    private val frameLocks = ConcurrentHashMap<String, MachineFrames>()

    /** A `pane.scroll` request. [seq] increases with each request of all machines. */
    private data class PaneScrollRequest(val paneId: String, val offsetFromBottom: Int, val seq: Long)

    /** The `pane.scroll` requests of one pane. Only the holder of the frame lock reads or writes the fields. */
    private class PaneScrollRequests {
        /** The newest request that the scroll worker did not send yet. Older requests of the pane are dropped. */
        var queued: PaneScrollRequest? = null

        /** The newest request that did not fail: not sent yet, in flight, or answered. */
        var newest: PaneScrollRequest? = null

        /** The newest request that Herdr answered, or that failed. */
        var answered: PaneScrollRequest? = null

        /** True when the answer of [answered] had the pane text, and the client put that text in [cachedInputs]. */
        var answerHasText: Boolean = false
    }

    /** The frame lock of one machine and its data. Only the holder of the lock reads or writes the fields. */
    private class MachineFrames {
        /** The `pane.scroll` requests of each pane, by pane id. */
        val scrollRequests = HashMap<String, PaneScrollRequests>()

        /**
         * A poll with a lower number publishes nothing, because its data is older than the data on the buses. A poll
         * that publishes sets this value to its own number. A change of the selection and [disconnect] set it to a new
         * number, because a poll that started before can have read the old selection, or can end after the
         * disconnect. See [refreshSnapshotAndSurface].
         */
        var minPollSeq: Long = 0L
    }

    /** Runs [block] in the frame lock of [machineId], and gives it the frame data of the machine. */
    private inline fun <T> withFrames(machineId: String, block: (MachineFrames) -> T): T {
        val frames = frameLocks.computeIfAbsent(machineId) { MachineFrames() }
        return synchronized(frames) { block(frames) }
    }

    /**
     * Runs [block] in the frame lock of [machineId], and returns its result. A step of a different class uses it to
     * read the frame of the machine from [paneSurfaceBus] and publish a changed copy. See [frameLocks].
     */
    internal fun <T> withFrameLock(machineId: String, block: () -> T): T = withFrames(machineId) { block() }

    /**
     * Stops the polls of [machineId] that started before this call: they publish nothing. Call it after a change of
     * the selection, because these polls can have read the old selection.
     */
    private fun discardRunningPolls(machineId: String) {
        withFrames(machineId) { frames -> frames.minPollSeq = pollSequence.incrementAndGet() }
    }

    private fun sessionMutexFor(machineId: String): Mutex =
        sessionOpenLocks.computeIfAbsent(machineId) { Mutex() }

    private fun laneMutexFor(machineId: String, lane: WorkerLane): Mutex =
        when (lane) {
            WorkerLane.Input -> inputLaneLocks.computeIfAbsent(machineId) { Mutex() }
            WorkerLane.Poll -> pollLaneLocks.computeIfAbsent(machineId) { Mutex() }
        }

    private fun inputQueueFor(machineId: String): ConcurrentLinkedQueue<PendingPaneInput> =
        pendingInputQueues.computeIfAbsent(machineId) { ConcurrentLinkedQueue() }

    private fun optimisticEditsFor(machineId: String): ArrayDeque<OptimisticEdit> =
        optimisticEdits.computeIfAbsent(machineId) { ArrayDeque() }

    /**
     * Opens the session to [machine] and runs one check command. Returns the error when the session does
     * not open, so that the caller can tell the kind of the failure.
     *
     * This is a connect from the user. It stops the reconnect loop of an earlier connect, and [startLivePolling]
     * starts a new loop after the connect.
     */
    suspend fun connectAndVerify(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        supervisors.remove(machine.id)?.stop()
        roundTripMeter.reset(machine.id)
        // A new connect starts a new series of polls with no snapshot. See recordMissingSnapshot.
        machinesWithoutSnapshot.remove(machine.id)
        connectSteps += machine.id
        runCatching {
            val out = executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Poll,
                commands = listOf(RemoteShellCommand.Exec("${HerdrCommand.of(machine)} --version || true")),
            )
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "Connected to ${describeRoute(route)} (${out.trim().ifBlank { "SSH ok" }})",
            )
        }.onFailure { err ->
            val errDetail = err.message?.takeIf { it.isNotBlank() }
                ?: err::class.simpleName
                ?: "Unknown SSH error"
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "SSH connection failed to ${describeFailedEndpoint(route, err)}: $errDetail",
                isError = true,
            )
        }
    }

    /**
     * Runs the shell [command] on [machine] and returns its standard output. The call opens the session when
     * necessary. It throws the SSH error when the session does not open, for example a failed login.
     */
    internal suspend fun runCommand(
        machine: HostMachine,
        route: ResolvedSshRoute,
        command: String,
    ): String = withContext(Dispatchers.IO) {
        executeBatchOnLane(
            machine = machine,
            route = route,
            lane = WorkerLane.Poll,
            commands = listOf(RemoteShellCommand.Exec(command)),
        )
    }

    /** Writes [message] about the transport of [machineId] to the debug log. */
    internal fun recordTransport(machineId: String, message: String) {
        sshDebugLog.record(machineId = machineId, tag = TRANSPORT_LOG_TAG, message = message)
    }

    /**
     * Returns true when `command -v` finds `mosh-server`, or [HostMachine.moshServerPath], on [machine]. Returns
     * false when it does not find it or when the command fails.
     *
     * This version has no Mosh transport (issue #22). The result only tells the user that the host has
     * `mosh-server`.
     */
    suspend fun checkMoshServerAvailable(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val moshBin = machine.moshServerPath?.takeIf { it.isNotBlank() } ?: "mosh-server"
            val out = executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Poll,
                commands = listOf(RemoteShellCommand.Exec("command -v $moshBin")),
            )
            out.trim().isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * Makes the [SessionSupervisor] of [machine] and starts the poll loop. This is the last connect step.
     *
     * The poll loop reads the snapshot and the surface each [LIVE_POLL_INTERVAL_MS] while [isSurfaceActive] returns
     * true. It pauses while the supervisor reconnects, and it stops when the supervisor stops.
     */
    fun startLivePolling(
        machine: HostMachine,
        route: ResolvedSshRoute,
        isSurfaceActive: () -> Boolean,
    ) {
        val supervisor = SessionSupervisor(
            machineId = machine.id,
            scope = reconnectScope,
            delayAfter = reconnectDelays,
            isSessionLive = { liveSession(machine.id) != null },
            attempt = { reconnectAttempt(machine, route) },
            events = supervisorEvents,
        )
        supervisors.put(machine.id, supervisor)?.stop()
        connectSteps -= machine.id
        pollJobs.remove(machine.id)?.cancel()
        val job = scope.launch {
            while (isActive && supervisor.awaitLive()) {
                if (isSurfaceActive()) {
                    runCatching {
                        refreshSnapshotAndSurface(machine, route)
                    }.onFailure { err ->
                        ensureActive()
                        sshDebugLog.record(
                            machineId = machine.id,
                            tag = "RealSSH",
                            message = "Live poll warning: ${err.message}",
                            isError = true,
                        )
                    }
                }
                delay(LIVE_POLL_INTERVAL_MS)
            }
        }
        pollJobs[machine.id] = job
    }

    fun disconnect(machineId: String) {
        supervisors.remove(machineId)?.stop()
        roundTripMeter.reset(machineId)
        connectSteps -= machineId
        pollJobs.remove(machineId)?.cancel()
        paneScrollJobs.remove(machineId)?.cancel()
        paneScrollSignals.remove(machineId)?.close()
        inputFlushJobs.remove(machineId)?.cancel()
        inputFlushSignals.remove(machineId)?.close()
        pendingInputQueues.remove(machineId)?.clear()
        // A poll that still runs, for example the refresh of an RPC, publishes nothing after this step.
        withFrames(machineId) { frames ->
            discardRunningPolls(machineId)
            frames.scrollRequests.clear()
            paneScrolls.remove(machineId)
            optimisticEdits.remove(machineId)?.let { synchronized(it) { it.clear() } }
            selectionState.remove(machineId)
            cachedInputs.remove(machineId)
            viewportSizes.remove(machineId)
        }
        lastInputConfirmedSeq.remove(machineId)
        machinesWithoutSnapshot.remove(machineId)
        inputWorkers.remove(machineId)?.close()
        pollWorkers.remove(machineId)?.close()
        closeSessions(machineId)
    }

    /**
     * Returns true when [machineId] has an open session. A session through a bastion is open only while its bastion
     * session is open too.
     */
    internal fun hasLiveSession(machineId: String): Boolean = liveSession(machineId) != null

    /**
     * Checks that the session of [machineId] works. It sends a keepalive message, and then runs `true` on an `exec`
     * channel. Returns [LivenessResult.Dead] when the machine has no open session, when a step fails, or when the
     * steps take more than [timeout].
     *
     * A socket on a lost network can stay open for minutes, so only a reply from the host proves that the session
     * works. A write to such a socket can block for longer than [timeout], so the check runs in a different
     * coroutine, and the caller stops at [timeout] in real time.
     */
    internal suspend fun checkLiveness(machineId: String, timeout: Duration): LivenessResult =
        withContext(Dispatchers.IO) {
            val session = liveSession(machineId) ?: return@withContext LivenessResult.Dead(sessionId = null)
            val sessionId = sessionIds[session]
            val check = scope.async { probeSession(session, timeout) }
            val live = withTimeoutOrNull(timeout) { check.await() } ?: false.also { check.cancel() }
            if (live) LivenessResult.Live else LivenessResult.Dead(sessionId)
        }

    /** Sends a keepalive message and runs `true` on [session]. Returns true when the command ends in [timeout]. */
    private suspend fun probeSession(session: Session, timeout: Duration): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        return try {
            session.sendKeepAliveMsg()
            val channel = session.openChannel("exec") as ChannelExec
            try {
                channel.setCommand("true")
                channel.connect(remainingMs(deadline))
                while (!channel.isClosed && deadline.hasNotPassedNow()) {
                    delay(LIVENESS_POLL_INTERVAL_MS)
                }
                channel.isClosed && channel.exitStatus == 0
            } finally {
                channel.disconnect()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Asks the supervisor of [machineId] for an attempt now. See [SessionSupervisor.reconnectNow].
     *
     * [deadSessionId] is the session of a failed liveness check, or null. When a different session is live, the
     * reconnect loop opened it after the check started, and this function does nothing.
     */
    internal fun reconnectNow(machineId: String, deadSessionId: Long?) {
        val liveSessionId = liveSession(machineId)?.let(sessionIds::get)
        if (deadSessionId != null && liveSessionId != null && liveSessionId != deadSessionId) {
            sshDebugLog.record(
                machineId = machineId,
                tag = "RealSSH",
                message = "The failed check used an old session. The new session stays open.",
            )
            return
        }
        supervisors[machineId]?.reconnectNow()
    }

    /** Returns the id of the live session of [machineId], or null. See [LivenessResult.Dead]. */
    internal fun liveSessionId(machineId: String): Long? = liveSession(machineId)?.let(sessionIds::get)

    /** Returns the state of the supervisor of [machineId], or null when the machine has no supervisor. */
    internal fun reconnectState(machineId: String): SessionSupervisor.State? = supervisors[machineId]?.state?.value

    /** Returns the average poll time of [machineId] in whole milliseconds, or null before the first sample. */
    internal fun averageRoundTripMs(machineId: String): Int? = roundTripMeter.averageMs(machineId)

    /**
     * Closes the workers and the sessions of [machineId] at once, after the device lost its network, and tells the
     * supervisor. Thus no attempt waits for a TCP timeout on the old network. The supervisor makes no attempt until
     * [reconnectNow], because an attempt with no network fails.
     */
    internal fun closeSockets(machineId: String) {
        inputWorkers.remove(machineId)?.close()
        pollWorkers.remove(machineId)?.close()
        closeSessions(machineId)
        supervisors[machineId]?.networkLost(IOException("The device lost its network"))
    }

    /**
     * Returns the time to [deadline] in milliseconds for a JSch timeout. JSch reads 0 as no limit, so the result is
     * 1 or more.
     */
    private fun remainingMs(deadline: TimeMark): Int =
        (-deadline.elapsedNow().inWholeMilliseconds).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    /** Returns the `host:port` text of the target of [route], and the bastion when the route has one. */
    private fun describeRoute(route: ResolvedSshRoute): String =
        if (route.viaBastion) {
            "${route.targetAddress} via bastion ${route.bastionAddress}"
        } else {
            route.targetAddress
        }

    /**
     * Returns the text that names the endpoint of [route] where [error] occurred: the bastion, or the target.
     * A bastion error is a [BastionConnectException], or a host key rejection for the `host:port` of the bastion.
     */
    private fun describeFailedEndpoint(route: ResolvedSshRoute, error: Throwable): String {
        val rejected = (error as? HostKeyRejectedException)?.rejection?.presented
        val onBastion = error is BastionConnectException || rejected?.isFor(route.entryHost, route.entryPort) == true
        return if (route.viaBastion && onBastion) {
            "bastion ${route.bastionAddress} of ${route.targetAddress}"
        } else {
            describeRoute(route)
        }
    }

    fun resizeViewport(
        machineId: String,
        cols: UShort,
        rows: UShort,
    ) {
        val targetCols = cols.toInt().coerceIn(20, 300)
        val targetRows = rows.toInt().coerceIn(8, 150)
        withFrames(machineId) {
            val prev = viewportSizes.put(machineId, targetCols to targetRows)
            if (prev == (targetCols to targetRows)) return
            val cached = cachedInputs[machineId] ?: return
            val frame = buildCompositeSurfaceFrame(
                machineId = machineId,
                snapshot = cached.snapshot,
                layout = cached.layout,
                ansiByPane = cached.ansiByPane,
            )
            framePublisher(machineId, frame)
        }
    }

    /**
     * Reads the snapshot and the visible panes of [machine], and publishes them. Returns false when the output has
     * no snapshot.
     *
     * The time of the first command batch is the poll time of the machine (`ActiveBackbone.rttMs`). It is one
     * round trip on the SSH session, plus the run time of `herdr api snapshot` and of one `herdr remote-api-bridge`
     * for each visible pane. Thus it is more than the network round trip time. The client gives the time to
     * [roundTripMeter]. A batch that fails, that runs during a reconnect, or that is the first batch of a new worker
     * gives no time.
     *
     * The poll sets [paneScrolls] and [cachedInputs], and publishes its frame, in one step in the frame lock of the
     * machine. Thus a scroll request or an input answer cannot occur between the build and the publish of the poll
     * frame. See [frameLocks] and [mergePaneScrolls].
     *
     * Each poll gets a number when its first batch gets the lock of the Poll lane. Thus the numbers of two polls have
     * the order of their snapshots. The poll publishes its snapshot and its frame only while its number is not lower
     * than [MachineFrames.minPollSeq]. Thus the data of an older poll cannot replace the data of a newer poll, and a
     * poll that read the selection before a change publishes nothing.
     */
    suspend fun refreshSnapshotAndSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Boolean = withContext(Dispatchers.IO) {
        val pollStartInputSeq = inputSequence.get()
        val pollStartConfirmedSeq = lastInputConfirmedSeq[machine.id] ?: 0L
        // The first batch sets these values in the lock of the Poll lane. For each pane, Herdr answered the scroll
        // requests up to the number in answeredBeforeSnapshot before the batch. Thus the snapshot shows them.
        var pollSeq = 0L
        var answeredBeforeSnapshot = emptyMap<String, Long>()
        val predictedVisiblePanes = cachedInputs[machine.id]?.let { existing ->
            val activeTabId = existing.snapshot.focusedTabId
            existing.layout?.panes?.map { it.paneId }?.takeIf { it.isNotEmpty() }
                ?: existing.snapshot.panes
                    .filter { activeTabId == null || it.tabId == activeTabId }
                    .map { it.paneId }
                    .ifEmpty { listOfNotNull(existing.snapshot.focusedPaneId) }
        }.orEmpty()

        val initialCommands = buildList {
            // Herdr writes an error answer only to standard error. The parse below ignores the lines that are not JSON,
            // for example a warning with a good answer. See recordMissingSnapshot.
            add(RemoteShellCommand.Exec("${HerdrCommand.of(machine)} api snapshot 2>&1"))
            predictedVisiblePanes.forEach { paneId ->
                add(RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(paneId)))
            }
        }

        val combinedRawOutput = executeBatchOnLane(
            machine = machine,
            route = route,
            lane = WorkerLane.Poll,
            commands = initialCommands,
            onRoundTrip = { elapsed -> recordRoundTrip(machine.id, elapsed) },
            onLaneLocked = {
                pollSeq = pollSequence.incrementAndGet()
                answeredBeforeSnapshot = answeredScrollSeqs(machine.id)
            },
        )

        var snapshotObj: JsonObject? = null
        val ansiByPane = mutableMapOf<String, String>()

        combinedRawOutput.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val resultObj = obj["result"]?.jsonObject ?: return@forEach
                    val snapCandidate = resultObj["snapshot"]?.jsonObject
                    if (snapCandidate != null && snapshotObj == null) {
                        snapshotObj = snapCandidate
                    }
                    val readObj = resultObj["read"]?.jsonObject
                    if (readObj != null) {
                        val pId = readObj["pane_id"]?.jsonPrimitive?.content
                        val text = readObj["text"]?.jsonPrimitive?.content
                        if (!pId.isNullOrBlank() && text != null) {
                            ansiByPane[pId] = text
                        }
                    }
                }
            }

        val resolvedSnapshotObj = snapshotObj ?: run {
            recordMissingSnapshot(machine, combinedRawOutput)
            return@withContext false
        }
        machinesWithoutSnapshot.remove(machine.id)
        val serverScrolls = parsePaneScrolls(resolvedSnapshotObj)

        val sel = selectionState.computeIfAbsent(machine.id) { ClientSelectionState() }
        // The parse reads and writes the selection. In the lock, a change of the selection cannot occur during the
        // parse.
        val parsed = withFrames(machine.id) { frames ->
            // A newer poll or a change of the selection occurred. This poll has older data, so it publishes nothing.
            if (pollSeq < frames.minPollSeq) return@withFrames null
            frames.minPollSeq = pollSeq
            parseSnapshotObject(machine.id, resolvedSnapshotObj, sel).also { parsed ->
                endpointSnapshotBus.publish(machine.id, parsed.snapshot)
            }
        } ?: return@withContext true

        val activeTabId = parsed.snapshot.focusedTabId
        val activeLayout = parsed.layoutsByTab[activeTabId]
            ?: parsed.layoutsByTab.values.firstOrNull()
        val visiblePaneIds = activeLayout?.panes?.map { it.paneId }
            ?.takeIf { it.isNotEmpty() }
            ?: parsed.snapshot.panes
                .filter { activeTabId == null || it.tabId == activeTabId }
                .map { it.paneId }
                .ifEmpty { listOfNotNull(parsed.snapshot.focusedPaneId) }

        val snapshotPaneIds = parsed.snapshot.panes.mapTo(HashSet()) { it.paneId }
        if (visiblePaneIds.isEmpty()) {
            withFrames(machine.id) { frames ->
                if (pollSeq >= frames.minPollSeq) {
                    pruneScrollRequests(frames, snapshotPaneIds)
                    mergePaneScrolls(machine.id, frames, serverScrolls, snapshotPaneIds, answeredBeforeSnapshot)
                }
            }
            return@withContext true
        }

        val missingPaneIds = visiblePaneIds.filter { it !in ansiByPane }.toSet()
        // The second batch reads the missing panes after the snapshot. For each of these panes, Herdr answered the
        // scroll requests up to this number before that read.
        var answeredBeforeMissingReads = answeredBeforeSnapshot
        if (missingPaneIds.isNotEmpty()) {
            val missingCommands = missingPaneIds.map { paneId ->
                RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(paneId))
            }
            val missingResponse = executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Poll,
                commands = missingCommands,
                onLaneLocked = { answeredBeforeMissingReads = answeredScrollSeqs(machine.id) },
            )
            parsePaneReadLinesInto(missingResponse, ansiByPane)
        }

        val framePublished = withFrames(machine.id) { frames ->
            // A newer poll or a change of the selection occurred while this poll read the missing panes.
            if (pollSeq < frames.minPollSeq) return@withFrames false
            frames.minPollSeq = pollSeq
            pruneScrollRequests(frames, snapshotPaneIds)
            mergePaneScrolls(machine.id, frames, serverScrolls, snapshotPaneIds, answeredBeforeSnapshot)

            // The input lane can update the focused pane while this poll is in flight. Then the cache has newer text
            // of that pane. Keep that text, so that the poll does not remove recent keystrokes.
            val currentConfirmedSeq = lastInputConfirmedSeq[machine.id] ?: 0L
            val currentInputSeq = inputSequence.get()
            if (currentConfirmedSeq > pollStartConfirmedSeq || currentInputSeq > pollStartInputSeq) {
                val focusedId = parsed.snapshot.focusedPaneId
                val newerCachedAnsi = focusedId?.let { cachedInputs[machine.id]?.ansiByPane?.get(it) }
                if (focusedId != null && newerCachedAnsi != null) {
                    ansiByPane[focusedId] = newerCachedAnsi
                }
            }

            keepScrollAnswerText(
                machineId = machine.id,
                frames = frames,
                serverScrolls = serverScrolls,
                answeredBeforeSnapshot = answeredBeforeSnapshot,
                missingPaneIds = missingPaneIds,
                answeredBeforeMissingReads = answeredBeforeMissingReads,
                ansiByPane = ansiByPane,
            )

            cachedInputs[machine.id] = CachedSurfaceInputs(
                snapshot = parsed.snapshot,
                layout = activeLayout,
                ansiByPane = ansiByPane,
            )

            val frame = buildCompositeSurfaceFrame(
                machineId = machine.id,
                snapshot = parsed.snapshot,
                layout = activeLayout,
                ansiByPane = ansiByPane,
            )
            framePublisher(machine.id, frame)
            true
        }
        if (!framePublished) return@withContext true

        // Update agent snippets from the read pane buffers
        parsed.snapshot.agents.forEach { agent ->
            val rawText = ansiByPane[agent.paneId]
            if (!rawText.isNullOrBlank()) {
                val cleanSnippet = stripAnsiCodes(rawText)
                    .lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                    .toList()
                    .takeLast(4)
                    .joinToString("\n")
                if (cleanSnippet.isNotBlank()) {
                    semanticNotificationBus.updateAgentSnippet(
                        machineId = machine.id,
                        paneId = agent.paneId,
                        snippet = cleanSnippet,
                    )
                }
            }
        }
        true
    }

    /** Returns the number of the newest answered scroll request of each pane of [machineId]. */
    private fun answeredScrollSeqs(machineId: String): Map<String, Long> = withFrames(machineId) { frames ->
        frames.scrollRequests.mapValues { (_, requests) -> requests.answered?.seq ?: 0L }
    }

    /**
     * Removes the scroll requests of each pane that is not in [snapshotPaneIds] and has no request in the queue or in
     * flight. Thus the requests of closed panes do not stay until [disconnect]. The caller holds the frame lock.
     */
    private fun pruneScrollRequests(frames: MachineFrames, snapshotPaneIds: Set<String>) {
        frames.scrollRequests.entries.removeAll { (paneId, requests) ->
            val unanswered = requests.newest?.let { it.seq > (requests.answered?.seq ?: 0L) } == true
            paneId !in snapshotPaneIds && requests.queued == null && !unanswered
        }
    }

    /**
     * Sets [paneScrolls] of [machineId] from the positions in a poll snapshot, [serverScrolls]. The caller holds the
     * frame lock of the machine, and gives its [frames].
     *
     * For each pane, Herdr answered the scroll requests up to the number in [answeredBeforeSnapshot] before the
     * snapshot, so the snapshot shows their positions. A newer request can be newer than the snapshot: it is not sent
     * yet, it is in flight, or Herdr answered it during the poll. Thus each pane of the snapshot with such a request
     * keeps the position of its newest request. A pane that is not in [snapshotPaneIds] gets no position.
     */
    private fun mergePaneScrolls(
        machineId: String,
        frames: MachineFrames,
        serverScrolls: Map<String, PaneSurfaceScroll>,
        snapshotPaneIds: Set<String>,
        answeredBeforeSnapshot: Map<String, Long>,
    ) {
        val merged = serverScrolls.toMutableMap()
        frames.scrollRequests.forEach { (paneId, requests) ->
            if (paneId !in snapshotPaneIds) return@forEach
            val newest = requests.newest?.takeIf { it.seq > (answeredBeforeSnapshot[paneId] ?: 0L) } ?: return@forEach
            val scroll = serverScrolls[paneId] ?: PaneSurfaceScroll()
            merged[paneId] = scroll.copy(offsetFromBottom = newest.offsetFromBottom.toUInt())
        }
        paneScrolls[machineId] = merged
    }

    /**
     * Puts the cached text of a pane in [ansiByPane] of a poll when the poll read the pane before Herdr moved it. The
     * caller holds the frame lock of the machine, and gives its [frames].
     *
     * The first batch reads the snapshot and the predicted panes. The second batch reads the [missingPaneIds] later.
     * For each pane, Herdr answered the scroll requests up to the number in [answeredBeforeSnapshot] or in
     * [answeredBeforeMissingReads] before the batch that read the pane. The client uses the cached text of a pane only
     * when all of these conditions are true:
     * - Herdr answered a scroll request of the pane during the batch that read the pane.
     * - The answer had the pane text, so the cache has text that is newer than the scroll.
     * - The poll read the pane before the scroll. For a pane of the first batch, the snapshot does not show the
     *   position of the answer. A pane of the second batch has no position at the time of its read, so the client
     *   cannot tell the order. Then it keeps the cached text.
     *
     * Otherwise the poll keeps the text that it read. A failed request gives no text.
     */
    private fun keepScrollAnswerText(
        machineId: String,
        frames: MachineFrames,
        serverScrolls: Map<String, PaneSurfaceScroll>,
        answeredBeforeSnapshot: Map<String, Long>,
        missingPaneIds: Set<String>,
        answeredBeforeMissingReads: Map<String, Long>,
        ansiByPane: MutableMap<String, String>,
    ) {
        frames.scrollRequests.forEach { (paneId, requests) ->
            val answered = requests.answered ?: return@forEach
            val secondBatch = paneId in missingPaneIds
            val answeredBeforeRead = (if (secondBatch) answeredBeforeMissingReads else answeredBeforeSnapshot)[paneId]
            val answeredDuringRead = answered.seq > (answeredBeforeRead ?: 0L)
            val readBeforeScroll = secondBatch ||
                serverScrolls[paneId]?.offsetFromBottom != answered.offsetFromBottom.toUInt()
            if (answeredDuringRead && requests.answerHasText && readBeforeScroll && paneId in ansiByPane) {
                cachedInputs[machineId]?.ansiByPane?.get(paneId)?.let { ansiByPane[paneId] = it }
            }
        }
    }

    /** Adds the poll time [elapsed] of [machineId] to [roundTripMeter], which gives it to [roundTripListener]. */
    private fun recordRoundTrip(machineId: String, elapsed: Duration) {
        roundTripMeter.record(machineId, elapsed) { rttMs -> roundTripListener?.invoke(machineId, rttMs) == true }
    }

    private fun parseParamsObject(paramsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(paramsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    suspend fun executeEndpointRpc(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
    ): String? = withContext(Dispatchers.IO) {
        val sel = selectionState.computeIfAbsent(machine.id) { ClientSelectionState() }

        // A focus request changes the selection at once. In the frame lock, a poll cannot parse the snapshot during
        // the change.
        withFrames(machine.id) { applySelection(machine.id, sel, request) }

        // Scroll gestures send one request per line. Keep only the newest request, so that the pane
        // follows the finger, and do not refresh the full snapshot for each line.
        if (request.method == "pane.scroll") {
            val paramsObj = parseParamsObject(request.paramsJson)
            val paneId = paramsObj["pane_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val offset = paramsObj["offset_from_bottom"]?.jsonPrimitive?.intOrNull
            if (paneId != null && offset != null) {
                requestPaneScroll(machine, route, paneId, offset)
                return@withContext PANE_SCROLL_ACCEPTED_RESPONSE
            }
        }

        // Fast-path key/input RPCs (e.g., sendKeyChord -> pane.send_keys) onto the Input lane
        // without running a full topology snapshot.
        val isPaneInputRpc = request.method == "pane.send_keys" ||
            request.method == "pane.send_text" ||
            request.method == "pane.send_input"
        if (isPaneInputRpc) {
            val paramsObj = parseParamsObject(request.paramsJson)
            val targetPaneId = paramsObj["pane_id"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?: sel.focusedPaneId
                ?: endpointSnapshotBus.snapshotFor(machine.id)?.focusedPaneId
            val ndjsonLine = HerdrRpcBuilder.toNdjsonLine(request).trim()
            return@withContext runCatching {
                val cmds = buildList {
                    add(RemoteShellCommand.ApiBridgeLine(ndjsonLine))
                    if (!targetPaneId.isNullOrBlank()) {
                        add(RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(targetPaneId)))
                    }
                }
                val out = executeBatchOnLane(machine, route, WorkerLane.Input, cmds)
                if (!targetPaneId.isNullOrBlank()) {
                    val updatedAnsi = extractPaneAnsiFromResponse(out, targetPaneId)
                    if (updatedAnsi != null) {
                        updateFocusedPaneSurfaceFromInput(
                            machineId = machine.id,
                            paneId = targetPaneId,
                            paneAnsi = updatedAnsi,
                            confirmedSeq = inputSequence.get(),
                        )
                    }
                }
                out
            }.getOrElse { err ->
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "RealSSH",
                    message = "RPC ${request.method} error: ${err.message}",
                    isError = true,
                )
                null
            }
        }

        val ndjsonLine = HerdrRpcBuilder.toNdjsonLine(request).trim()
        val response = runCatching {
            executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Poll,
                commands = listOf(RemoteShellCommand.ApiBridgeLine(ndjsonLine)),
            )
        }.getOrElse { err ->
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "RPC ${request.method} error: ${err.message}",
                isError = true,
            )
            null
        }

        // Refresh snapshot and visible pane ANSI buffer after structural/focus RPCs
        runCatching { refreshSnapshotAndSurface(machine, route) }
        response
    }

    /**
     * Sets the selection of [machineId] from a focus [request] at once, so that a switch of workspace, tab, or pane
     * shows at once. The caller holds the frame lock of the machine, so that a poll cannot parse the snapshot during
     * the change. After a change, the polls that started before the change publish nothing.
     */
    private fun applySelection(machineId: String, sel: ClientSelectionState, request: ClientShellEndpointRequest) {
        when (request.method) {
            "workspace.focus" -> {
                val paramsObj = parseParamsObject(request.paramsJson)
                val wsId = paramsObj["workspace_id"]?.jsonPrimitive?.content
                if (!wsId.isNullOrBlank()) {
                    sel.focusedWorkspaceId = wsId
                    val currentSnap = endpointSnapshotBus.snapshotFor(machineId)
                    val ws = currentSnap?.workspaces?.firstOrNull { it.workspaceId == wsId }
                    val targetTabId = ws?.activeTabId
                        ?: currentSnap?.tabs?.firstOrNull { it.workspaceId == wsId }?.tabId
                    if (!targetTabId.isNullOrBlank()) {
                        sel.focusedTabId = targetTabId
                        val targetPaneId = currentSnap?.panes
                            ?.firstOrNull { it.tabId == targetTabId }?.paneId
                        if (!targetPaneId.isNullOrBlank()) {
                            sel.focusedPaneId = targetPaneId
                        }
                    }
                    discardRunningPolls(machineId)
                }
            }

            "tab.focus" -> {
                val paramsObj = parseParamsObject(request.paramsJson)
                val tabId = paramsObj["tab_id"]?.jsonPrimitive?.content
                if (!tabId.isNullOrBlank()) {
                    sel.focusedTabId = tabId
                    val currentSnap = endpointSnapshotBus.snapshotFor(machineId)
                    val tab = currentSnap?.tabs?.firstOrNull { it.tabId == tabId }
                    if (tab != null) {
                        sel.focusedWorkspaceId = tab.workspaceId
                    }
                    val targetPaneId = currentSnap?.panes?.firstOrNull { it.tabId == tabId }?.paneId
                    if (!targetPaneId.isNullOrBlank()) {
                        sel.focusedPaneId = targetPaneId
                    }
                    discardRunningPolls(machineId)
                }
            }

            "pane.focus", "agent.focus" -> {
                val paramsObj = parseParamsObject(request.paramsJson)
                val paneId = paramsObj["pane_id"]?.jsonPrimitive?.content
                if (!paneId.isNullOrBlank()) {
                    sel.focusedPaneId = paneId
                    val currentSnap = endpointSnapshotBus.snapshotFor(machineId)
                    val pane = currentSnap?.panes?.firstOrNull { it.paneId == paneId }
                    if (pane != null) {
                        sel.focusedWorkspaceId = pane.workspaceId
                        sel.focusedTabId = pane.tabId
                    }
                    discardRunningPolls(machineId)
                    applyOptimisticPaneFocus(machineId, paneId)
                }
            }
        }
    }

    /**
     * Sends a pane input event in these steps:
     * 1. When the pane is not at the bottom, it requests the bottom ([requestPaneScroll]).
     * 2. It publishes an optimistic local echo on [paneSurfaceBus], with no network I/O. The echo uses the frame lock
     *    of the machine, so it can wait for the frame builds of the same machine. See [frameLocks].
     * 3. It adds the event to the input queue of the machine, and does not wait. Thus fast typing does not wait for
     *    a round trip.
     * 4. The input flush sends the queued events in one batch on the persistent [WorkerLane.Input] channel
     *    (`pane.send_text`, `pane.send_keys`, or `pane.send_input`, and a `pane.read` of the pane). The poll uses the
     *    other channel, [WorkerLane.Poll].
     */
    suspend fun sendPaneInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean {
        val targetPaneId = paneId.takeIf { it.isNotBlank() }
            ?: selectionState[machine.id]?.focusedPaneId
            ?: endpointSnapshotBus.snapshotFor(machine.id)?.focusedPaneId
            ?: paneSurfaceBus.surfaceFor(machine.id)?.panes?.firstOrNull { it.focused }?.paneId
            ?: return false

        if (event is ClientPaneInputEvent.Mouse) {
            return true
        }

        // Herdr keeps the scroll position when input arrives. Go back to the bottom, like a terminal,
        // so that the user sees the prompt and the echo.
        if ((paneScrolls[machine.id]?.get(targetPaneId)?.offsetFromBottom ?: 0u) > 0u) {
            requestPaneScroll(machine, route, targetPaneId, 0)
        }

        val seq = inputSequence.incrementAndGet()

        // 1. Apply immediate optimistic local echo (<1ms) so typed characters appear on screen before network I/O
        val optimisticEdit = toOptimisticEdit(seq, targetPaneId, event)
        if (optimisticEdit != null) {
            val edits = optimisticEditsFor(machine.id)
            // In the frame lock, a frame of a different step shows the edit, or the edit goes on top of that frame.
            // The edit cannot show two times.
            withFrames(machine.id) {
                synchronized(edits) {
                    while (edits.size >= MAX_OPTIMISTIC_EDITS) {
                        edits.removeFirst()
                    }
                    edits.addLast(optimisticEdit)
                }
                val currentFrame = paneSurfaceBus.surfaceFor(machine.id)
                if (currentFrame != null) {
                    val updatedFrame = applyOptimisticEditToFrame(currentFrame, optimisticEdit)
                    framePublisher(machine.id, updatedFrame)
                }
            }
        } else if (isControlInterruptEvent(event)) {
            val edits = optimisticEditsFor(machine.id)
            synchronized(edits) {
                edits.clear()
            }
        }

        // 2. Enqueue for coalesced transmission on the dedicated persistent Input lane
        inputQueueFor(machine.id).add(
            PendingPaneInput(
                seq = seq,
                paneId = targetPaneId,
                event = event,
            )
        )
        triggerInputFlushLoop(machine, route)
        return true
    }

    /**
     * Suspends until all currently queued input events for [machineId] have been flushed over [WorkerLane.Input].
     */
    internal suspend fun awaitInputFlush(machineId: String) = withContext(Dispatchers.IO) {
        val targetSeq = inputSequence.get()
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val confirmed = lastInputConfirmedSeq[machineId] ?: 0L
            val queueEmpty = inputQueueFor(machineId).isEmpty()
            val mutexUnlocked = !laneMutexFor(machineId, WorkerLane.Input).isLocked
            if (confirmed >= targetSeq && queueEmpty && mutexUnlocked) {
                return@withContext
            }
            delay(5L)
        }
    }

    private fun triggerInputFlushLoop(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ) {
        val signal = inputFlushSignals.computeIfAbsent(machine.id) {
            Channel(Channel.CONFLATED)
        }
        val existingJob = inputFlushJobs[machine.id]
        if (existingJob == null || !existingJob.isActive) {
            synchronized(inputFlushJobs) {
                val current = inputFlushJobs[machine.id]
                if (current == null || !current.isActive) {
                    inputFlushJobs[machine.id] = scope.launch {
                        for (trigger in signal) {
                            drainAndFlushPendingInputs(machine, route)
                        }
                    }
                }
            }
        }
        signal.trySend(Unit)
    }

    private suspend fun drainAndFlushPendingInputs(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ) {
        val queue = inputQueueFor(machine.id)
        while (scope.isActive) {
            val batch = ArrayList<PendingPaneInput>(16)
            while (true) {
                val next = queue.poll() ?: break
                batch.add(next)
            }
            if (batch.isEmpty()) break

            val maxSeqInBatch = batch.maxOf { it.seq }
            val targetPaneId = batch.last().paneId
            val coalescedRpcCommands = buildCoalescedInputCommands(batch)
            if (coalescedRpcCommands.isEmpty()) continue

            val prevAnsi = cachedInputs[machine.id]?.ansiByPane?.get(targetPaneId)
            val readCommand = RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(targetPaneId))

            runCatching {
                val combinedCommands = coalescedRpcCommands + readCommand
                val rawResponse = executeBatchOnLane(
                    machine = machine,
                    route = route,
                    lane = WorkerLane.Input,
                    commands = combinedCommands,
                )

                val immediateAnsi = extractPaneAnsiFromResponse(rawResponse, targetPaneId)

                // If more keystrokes arrived while this batch was on the wire, immediately loop to send them
                if (queue.isNotEmpty()) {
                    if (immediateAnsi != null && immediateAnsi != prevAnsi) {
                        updateFocusedPaneSurfaceFromInput(
                            machineId = machine.id,
                            paneId = targetPaneId,
                            paneAnsi = immediateAnsi,
                            confirmedSeq = maxSeqInBatch,
                        )
                    }
                    return@runCatching
                }

                if (immediateAnsi != null && immediateAnsi != prevAnsi) {
                    updateFocusedPaneSurfaceFromInput(
                        machineId = machine.id,
                        paneId = targetPaneId,
                        paneAnsi = immediateAnsi,
                        confirmedSeq = maxSeqInBatch,
                    )
                } else {
                    // Remote PTY discipline or TUI may need a few milliseconds to echo back to the PTY master.
                    // Keep the optimistic echo visible and perform one fast follow-up pane.read if still idle.
                    delay(PTY_ECHO_SETTLE_DELAY_MS)
                    if (queue.isEmpty()) {
                        val followUpOut = executeBatchOnLane(
                            machine = machine,
                            route = route,
                            lane = WorkerLane.Input,
                            commands = listOf(readCommand),
                        )
                        val settledAnsi = extractPaneAnsiFromResponse(followUpOut, targetPaneId) ?: immediateAnsi
                        if (settledAnsi != null) {
                            updateFocusedPaneSurfaceFromInput(
                                machineId = machine.id,
                                paneId = targetPaneId,
                                paneAnsi = settledAnsi,
                                confirmedSeq = maxSeqInBatch,
                            )
                        } else {
                            retireOptimisticEditsUpTo(machine.id, maxSeqInBatch)
                        }
                    }
                }

                // Trigger an asynchronous non-blocking snapshot refresh only on Enter or Ctrl chords
                if (batch.any { shouldTriggerTopologyRefresh(it.event) }) {
                    scope.launch {
                        runCatching { refreshSnapshotAndSurface(machine, route) }
                    }
                }
            }.onFailure { err ->
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "RealSSH",
                    message = "Failed to send input to $targetPaneId: ${err.message}",
                    isError = true,
                )
            }
        }
    }

    /**
     * Coalesces consecutive [PendingPaneInput] events targeting the same pane into minimal JSON-RPC lines:
     * - Consecutive `TextCommit` / `Paste` events merge into a single `pane.send_text`.
     * - Consecutive `Key` events merge into a single `pane.send_keys` array.
     * - Accumulated text immediately followed by `Key` events on the same pane merges into a single `pane.send_input`.
     */
    internal fun buildCoalescedInputCommands(batch: List<PendingPaneInput>): List<RemoteShellCommand.ApiBridgeLine> {
        if (batch.isEmpty()) return emptyList()
        val result = ArrayList<RemoteShellCommand.ApiBridgeLine>(batch.size)

        var currentPaneId: String? = null
        val textBuffer = StringBuilder()
        val keyBuffer = ArrayList<String>(4)

        fun flushCurrent() {
            val paneId = currentPaneId ?: return
            val hasText = textBuffer.isNotEmpty()
            val hasKeys = keyBuffer.isNotEmpty()
            if (!hasText && !hasKeys) return

            val line = when {
                hasText && hasKeys -> {
                    val text = textBuffer.toString()
                    val keys = keyBuffer.toList()
                    buildJsonObject {
                        put("id", "in_${revisionCounter.incrementAndGet()}")
                        put("method", "pane.send_input")
                        put(
                            "params",
                            buildJsonObject {
                                put("pane_id", paneId)
                                put("text", text)
                                put(
                                    "keys",
                                    buildJsonArray {
                                        keys.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                            },
                        )
                    }.toString()
                }

                hasText -> {
                    val text = textBuffer.toString()
                    buildJsonObject {
                        put("id", "in_${revisionCounter.incrementAndGet()}")
                        put("method", "pane.send_text")
                        put(
                            "params",
                            buildJsonObject {
                                put("pane_id", paneId)
                                put("text", text)
                            },
                        )
                    }.toString()
                }

                else -> {
                    val keys = keyBuffer.toList()
                    buildJsonObject {
                        put("id", "key_${revisionCounter.incrementAndGet()}")
                        put("method", "pane.send_keys")
                        put(
                            "params",
                            buildJsonObject {
                                put("pane_id", paneId)
                                put(
                                    "keys",
                                    buildJsonArray {
                                        keys.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                            },
                        )
                    }.toString()
                }
            }
            result.add(RemoteShellCommand.ApiBridgeLine(line))
            textBuffer.setLength(0)
            keyBuffer.clear()
        }

        for (item in batch) {
            if (currentPaneId != null && currentPaneId != item.paneId) {
                flushCurrent()
            }
            currentPaneId = item.paneId

            when (val ev = item.event) {
                is ClientPaneInputEvent.TextCommit -> {
                    if (keyBuffer.isNotEmpty()) {
                        flushCurrent()
                        currentPaneId = item.paneId
                    }
                    textBuffer.append(ev.text)
                }

                is ClientPaneInputEvent.Paste -> {
                    if (keyBuffer.isNotEmpty()) {
                        flushCurrent()
                        currentPaneId = item.paneId
                    }
                    textBuffer.append(ev.text)
                }

                is ClientPaneInputEvent.Key -> {
                    keyBuffer.add(mapKeyEventToHerdrKeySpec(ev.event))
                }

                is ClientPaneInputEvent.Mouse -> Unit
            }
        }
        flushCurrent()
        return result
    }

    private fun toOptimisticEdit(
        seq: Long,
        paneId: String,
        event: ClientPaneInputEvent,
    ): OptimisticEdit? = when (event) {
        is ClientPaneInputEvent.TextCommit -> {
            if (event.text.isEmpty()) null else OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = event.text)
        }

        is ClientPaneInputEvent.Paste -> {
            if (event.text.isEmpty()) null else OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = event.text)
        }

        is ClientPaneInputEvent.Key -> {
            val ev = event.event
            if (ev.ctrl || ev.alt) {
                null
            } else {
                when (ev.code) {
                    ClientKeyCode.Backspace -> OptimisticEdit.Backspace(seq = seq, paneId = paneId)
                    ClientKeyCode.Enter -> OptimisticEdit.Newline(seq = seq, paneId = paneId)
                    ClientKeyCode.CharC -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "C" else "c")
                    ClientKeyCode.CharB -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "B" else "b")
                    ClientKeyCode.CharD -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "D" else "d")
                    ClientKeyCode.CharZ -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "Z" else "z")
                    ClientKeyCode.CharY -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "Y" else "y")
                    ClientKeyCode.CharN -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "N" else "n")
                    else -> null
                }
            }
        }

        is ClientPaneInputEvent.Mouse -> null
    }

    private fun isControlInterruptEvent(event: ClientPaneInputEvent): Boolean =
        event is ClientPaneInputEvent.Key && (event.event.ctrl || event.event.code == ClientKeyCode.Escape)

    private fun shouldTriggerTopologyRefresh(event: ClientPaneInputEvent): Boolean =
        when (event) {
            is ClientPaneInputEvent.Key -> event.event.code == ClientKeyCode.Enter || event.event.ctrl
            is ClientPaneInputEvent.TextCommit -> event.text.contains('\n') || event.text.contains('\r')
            is ClientPaneInputEvent.Paste -> event.text.contains('\n') || event.text.contains('\r')
            is ClientPaneInputEvent.Mouse -> false
        }

    private fun retireOptimisticEditsUpTo(machineId: String, confirmedSeq: Long) {
        if (confirmedSeq <= 0L) return
        lastInputConfirmedSeq.compute(machineId) { _, prev ->
            if (prev == null || confirmedSeq > prev) confirmedSeq else prev
        }
        val edits = optimisticEdits[machineId] ?: return
        synchronized(edits) {
            while (edits.isNotEmpty() && edits.first().seq <= confirmedSeq) {
                edits.removeFirst()
            }
        }
    }

    /**
     * Queues `pane.scroll` for [paneId] to [offsetFromBottom] and shows the new position at once.
     * One worker for each machine sends only the newest position of each pane, then reads that pane.
     */
    private fun requestPaneScroll(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        offsetFromBottom: Int,
    ) {
        val maxOffset = paneScrolls[machine.id]?.get(paneId)?.maxScrollback?.toInt() ?: 0
        val target = if (maxOffset > 0) offsetFromBottom.coerceIn(0, maxOffset) else offsetFromBottom.coerceAtLeast(0)
        withFrames(machine.id) { frames ->
            val request = PaneScrollRequest(paneId, target, paneScrollSequence.incrementAndGet())
            val requests = frames.scrollRequests.getOrPut(paneId) { PaneScrollRequests() }
            requests.queued = request
            requests.newest = request
            setLocalPaneScrollOffset(machine.id, paneId, target)
            republishCachedSurface(machine.id)
        }

        val signal = paneScrollSignals.computeIfAbsent(machine.id) { Channel(Channel.CONFLATED) }
        paneScrollJobs.compute(machine.id) { _, job ->
            job?.takeIf { it.isActive } ?: scope.launch {
                for (trigger in signal) {
                    while (isActive) {
                        val next = takeQueuedPaneScroll(machine.id) ?: break
                        sendPaneScroll(machine, route, next)
                    }
                }
            }
        }
        signal.trySend(Unit)
    }

    /** Removes and returns the oldest request of [machineId] that the scroll worker did not send, or null. */
    private fun takeQueuedPaneScroll(machineId: String): PaneScrollRequest? = withFrames(machineId) { frames ->
        val next = frames.scrollRequests.values.mapNotNull { it.queued }.minByOrNull { it.seq }
        next?.let { frames.scrollRequests[it.paneId]?.queued = null }
        next
    }

    private suspend fun sendPaneScroll(
        machine: HostMachine,
        route: ResolvedSshRoute,
        scroll: PaneScrollRequest,
    ) {
        val scrollLine = HerdrRpcBuilder.toNdjsonLine(
            HerdrRpcBuilder.paneScroll(paneId = scroll.paneId, offsetFromBottom = scroll.offsetFromBottom),
        ).trim()
        runCatching {
            val out = executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Input,
                commands = listOf(
                    RemoteShellCommand.ApiBridgeLine(scrollLine),
                    RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(scroll.paneId)),
                ),
            )
            val paneAnsi = extractPaneAnsiFromResponse(out, scroll.paneId)
            // In the lock, a new request or a poll cannot occur between these steps.
            withFrames(machine.id) { frames ->
                // [disconnect] removed the requests of the machine, so the answer has no use.
                val requests = frames.scrollRequests[scroll.paneId] ?: return@withFrames
                // A late answer of an older worker must not replace a newer answer.
                if (scroll.seq > (requests.answered?.seq ?: 0L)) {
                    requests.answered = scroll
                    requests.answerHasText = paneAnsi != null
                }
                // A newer request of this pane replaces this one. Keep the newer position on the screen.
                if (requests.queued == null) {
                    setLocalPaneScrollOffset(machine.id, scroll.paneId, scroll.offsetFromBottom)
                }
                if (paneAnsi != null) {
                    updateFocusedPaneSurfaceFromInput(
                        machineId = machine.id,
                        paneId = scroll.paneId,
                        paneAnsi = paneAnsi,
                        confirmedSeq = 0L,
                    )
                }
            }
        }.onFailure { err ->
            // [disconnect] cancels the worker. That is not a failure of the request.
            if (err is CancellationException) throw err
            withFrames(machine.id) { frames ->
                val requests = frames.scrollRequests[scroll.paneId] ?: return@withFrames
                if (scroll.seq > (requests.answered?.seq ?: 0L)) {
                    requests.answered = scroll
                    requests.answerHasText = false
                }
                // Herdr can have a different position, so a poll shows the position of Herdr for this pane.
                if (requests.newest == scroll) requests.newest = null
            }
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "pane.scroll for ${scroll.paneId} failed: ${err.message}",
                isError = true,
            )
        }
    }

    private fun setLocalPaneScrollOffset(machineId: String, paneId: String, offsetFromBottom: Int) {
        paneScrolls.compute(machineId) { _, existing ->
            val current = existing.orEmpty()
            val previous = current[paneId] ?: PaneSurfaceScroll()
            current + (paneId to previous.copy(offsetFromBottom = offsetFromBottom.coerceAtLeast(0).toUInt()))
        }
    }

    /** Publishes a new frame of [machineId] from [cachedInputs]. The caller holds the frame lock of the machine. */
    private fun republishCachedSurface(machineId: String) {
        val cached = cachedInputs[machineId] ?: return
        framePublisher(
            machineId,
            buildCompositeSurfaceFrame(
                machineId = machineId,
                snapshot = cached.snapshot,
                layout = cached.layout,
                ansiByPane = cached.ansiByPane,
            ),
        )
    }

    /** Reads `panes[].scroll` (`offset_from_bottom`, `max_offset_from_bottom`, `viewport_rows`) from a snapshot. */
    private fun parsePaneScrolls(snapshotObj: JsonObject): Map<String, PaneSurfaceScroll> {
        val panesArray = snapshotObj["panes"] as? JsonArray ?: return emptyMap()
        return panesArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["pane_id"] ?: obj["id"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val scrollObj = obj["scroll"] as? JsonObject ?: return@mapNotNull null
            fun field(name: String): Long =
                scrollObj[name]?.jsonPrimitive?.longOrNull?.coerceIn(0L, UInt.MAX_VALUE.toLong()) ?: 0L
            id to PaneSurfaceScroll(
                offsetFromBottom = field("offset_from_bottom").toUInt(),
                maxScrollback = field("max_offset_from_bottom").toUInt(),
                viewportRows = field("viewport_rows").coerceAtMost(UShort.MAX_VALUE.toLong()).toUShort(),
            )
        }.toMap()
    }

    /**
     * Puts the pane text [paneAnsi] of [paneId] in [cachedInputs], and publishes the new frame of [machineId]. A
     * [confirmedSeq] above zero also confirms the input events up to that number. The input flush, the input RPCs,
     * and the scroll worker use it. It does all steps in the frame lock of the machine, so that a poll or a scroll
     * request cannot occur between the build and the publish of the frame.
     */
    private fun updateFocusedPaneSurfaceFromInput(
        machineId: String,
        paneId: String,
        paneAnsi: String,
        confirmedSeq: Long,
    ) {
        withFrames(machineId) {
            if (confirmedSeq > 0L) {
                lastInputConfirmedSeq.compute(machineId) { _, prev ->
                    if (prev == null || confirmedSeq > prev) confirmedSeq else prev
                }
                retireOptimisticEditsUpTo(machineId, confirmedSeq)
            }

            val updatedCache = cachedInputs.compute(machineId) { _, existing ->
                if (existing == null) {
                    null
                } else {
                    existing.copy(ansiByPane = existing.ansiByPane + (paneId to paneAnsi))
                }
            } ?: return

            val frame = buildCompositeSurfaceFrame(
                machineId = machineId,
                snapshot = updatedCache.snapshot,
                layout = updatedCache.layout,
                ansiByPane = updatedCache.ansiByPane,
            )
            framePublisher(machineId, frame)
        }
    }

    /**
     * Shows [paneId] as the focused pane at once, on [endpointSnapshotBus] and on [paneSurfaceBus]. It reads and
     * publishes the two buses in the frame lock of the machine, so that a poll cannot publish between these steps.
     */
    private fun applyOptimisticPaneFocus(machineId: String, paneId: String) {
        withFrames(machineId) {
            val currentSnapshot = endpointSnapshotBus.snapshotFor(machineId)
            if (currentSnapshot != null && currentSnapshot.focusedPaneId != paneId) {
                endpointSnapshotBus.publish(
                    machineId,
                    currentSnapshot.copy(
                        revision = revisionCounter.incrementAndGet().toULong(),
                        focusedPaneId = paneId,
                        panes = currentSnapshot.panes.map { it.copy(focused = it.paneId == paneId) },
                        agents = currentSnapshot.agents.map { it.copy(focused = it.paneId == paneId) },
                    ),
                )
            }
            val currentSurface = paneSurfaceBus.surfaceFor(machineId)
            if (currentSurface != null) {
                framePublisher(
                    machineId,
                    currentSurface.copy(
                        revision = revisionCounter.incrementAndGet().toULong(),
                        panes = currentSurface.panes.map { it.copy(focused = it.paneId == paneId) },
                    ),
                )
            }
        }
    }

    internal fun applyOptimisticEditToFrame(
        frame: PaneSurfaceFrame,
        edit: OptimisticEdit,
    ): PaneSurfaceFrame {
        val gridCols = frame.frame.width.toInt()
        val gridRows = frame.frame.height.toInt()
        if (gridCols <= 0 || gridRows <= 0 || frame.frame.cells.size < gridCols * gridRows) {
            return frame
        }

        val targetPane = frame.panes.firstOrNull { it.paneId == edit.paneId }
            ?: frame.panes.firstOrNull { it.focused }
            ?: frame.panes.firstOrNull()
        val minCol = (targetPane?.innerRect?.x?.toInt() ?: 0).coerceIn(0, gridCols - 1)
        val minRow = (targetPane?.innerRect?.y?.toInt() ?: 0).coerceIn(0, gridRows - 1)
        val maxCol = if (targetPane != null) {
            (minCol + targetPane.innerRect.width.toInt() - 1).coerceIn(minCol, gridCols - 1)
        } else {
            gridCols - 1
        }
        val maxRow = if (targetPane != null) {
            (minRow + targetPane.innerRect.height.toInt() - 1).coerceIn(minRow, gridRows - 1)
        } else {
            gridRows - 1
        }

        val cur = frame.frame.cursor
        var cursorCol = (cur?.x?.toInt() ?: minCol).coerceIn(minCol, maxCol)
        var cursorRow = (cur?.y?.toInt() ?: minRow).coerceIn(minRow, maxRow)
        val cells = frame.frame.cells.toMutableList()

        fun clearPredictedEchoOnRow(row: Int) {
            for (c in minCol..maxCol) {
                val idx = row * gridCols + c
                val existing = cells[idx]
                if (existing.isPredictedEcho) {
                    val cleanMod = (existing.modifier.toInt() and CellData.MOD_PREDICTED_ECHO.toInt().inv()).toUShort()
                    cells[idx] = existing.copy(modifier = cleanMod)
                }
            }
        }

        when (edit) {
            is OptimisticEdit.InsertText -> {
                for (ch in edit.text) {
                    when {
                        ch == '\n' || ch == '\r' -> {
                            clearPredictedEchoOnRow(cursorRow)
                            cursorCol = minCol
                            cursorRow = (cursorRow + 1).coerceAtMost(maxRow)
                        }

                        ch == '\b' || ch == '\u007f' -> {
                            if (cursorCol > minCol) {
                                cursorCol--
                                cells[cursorRow * gridCols + cursorCol] = BLANK_CELL
                            }
                        }

                        !ch.isISOControl() -> {
                            val idx = cursorRow * gridCols + cursorCol
                            val existing = cells[idx]
                            cells[idx] = CellData(
                                symbol = codepointToSymbol(ch.code),
                                fg = existing.fg,
                                bg = existing.bg,
                                modifier = CellData.MOD_PREDICTED_ECHO,
                            )
                            if (cursorCol < maxCol) {
                                cursorCol++
                            } else if (cursorRow < maxRow) {
                                cursorCol = minCol
                                cursorRow++
                            }
                        }
                    }
                }
            }

            is OptimisticEdit.Backspace -> {
                if (cursorCol > minCol) {
                    cursorCol--
                    cells[cursorRow * gridCols + cursorCol] = BLANK_CELL
                }
            }

            is OptimisticEdit.Newline -> {
                clearPredictedEchoOnRow(cursorRow)
                cursorCol = minCol
                cursorRow = (cursorRow + 1).coerceAtMost(maxRow)
            }
        }

        return frame.copy(
            revision = revisionCounter.incrementAndGet().toULong(),
            frame = frame.frame.copy(
                cells = cells,
                cursor = CursorState(
                    x = cursorCol.toUShort(),
                    y = cursorRow.toUShort(),
                    shape = cur?.shape ?: CursorShape.Block,
                    visible = true,
                ),
            ),
        )
    }

    private fun buildPaneReadJsonLine(paneId: String): String =
        buildJsonObject {
            put("id", "read_$paneId")
            put("method", "pane.read")
            put(
                "params",
                buildJsonObject {
                    put("pane_id", paneId)
                    put("source", "visible")
                    put("format", "ansi")
                },
            )
        }.toString()

    private fun extractPaneAnsiFromResponse(rawResponse: String, targetPaneId: String): String? {
        var found: String? = null
        rawResponse.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val readObj = obj["result"]?.jsonObject?.get("read")?.jsonObject ?: return@forEach
                    val pId = readObj["pane_id"]?.jsonPrimitive?.content
                    val text = readObj["text"]?.jsonPrimitive?.content
                    if (pId == targetPaneId && text != null) {
                        found = text
                    }
                }
            }
        return found
    }

    /**
     * Writes an error to the debug log when the snapshot batch of [machine] gave no snapshot in [output].
     *
     * The snapshot command sends its standard error to [output] (`2>&1`), because Herdr writes each error answer only
     * to standard error. The error of the log tells the first cause that applies:
     * - a session name that Herdr does not accept. Herdr then writes a line of text, not a JSON answer.
     * - the `error.message` of a JSON answer in [output], for example for `server_not_running`.
     * - the last line of [output] that is not JSON, for example `sh: 1: herdr: not found`.
     *
     * The cause has at most [MAX_LOGGED_CAUSE_LENGTH] characters.
     *
     * The client writes only the first error of each series of polls with no snapshot. A poll with a snapshot, a new
     * [connectAndVerify], or a [disconnect] ends the series. Thus a host that stays in this state does not fill the
     * debug log.
     */
    private fun recordMissingSnapshot(machine: HostMachine, output: String) {
        if (!machinesWithoutSnapshot.add(machine.id)) return
        val sessionName = machine.herdrSessionName?.trim()?.takeIf { it.isNotEmpty() }
        val cause = (sessionName?.takeIf { !HostMachine.isValidHerdrSessionName(it) }
            ?.let { "Herdr does not accept the session name \"$it\". Change the name in the machine settings." }
            ?: herdrErrorMessage(output)
            ?: lastTextLine(output)
            ?: "Herdr gave no output.").take(MAX_LOGGED_CAUSE_LENGTH)
        sshDebugLog.record(
            machineId = machine.id,
            tag = "RealSSH",
            message = "No snapshot from `${HerdrCommand.of(machine)} api snapshot`: $cause",
            isError = true,
        )
    }

    /** Returns the `error.message` of the first Herdr answer in [output] that has an error, or null. */
    private fun herdrErrorMessage(output: String): String? =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .firstNotNullOfOrNull { line ->
                runCatching {
                    val error = json.parseToJsonElement(line).jsonObject["error"]?.jsonObject
                    error?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
            }

    /** Returns the last line of [output] that has text and is not a JSON object, or null. */
    private fun lastTextLine(output: String): String? =
        output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && !(it.startsWith("{") && it.endsWith("}")) }

    private fun parsePaneReadLinesInto(
        rawResponse: String,
        targetMap: MutableMap<String, String>,
    ) {
        rawResponse.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val readObj = obj["result"]?.jsonObject?.get("read")?.jsonObject
                    val pId = readObj?.get("pane_id")?.jsonPrimitive?.content
                    val text = readObj?.get("text")?.jsonPrimitive?.content
                    if (!pId.isNullOrBlank() && text != null) {
                        targetMap[pId] = text
                    }
                }
            }
    }

    private fun mapKeyEventToHerdrKeySpec(ev: ClientKeyEvent): String {
        val base = when (ev.code) {
            ClientKeyCode.Enter -> "Enter"
            ClientKeyCode.Escape -> "Esc"
            ClientKeyCode.Tab -> "Tab"
            ClientKeyCode.Backspace -> "Backspace"
            ClientKeyCode.Up -> "Up"
            ClientKeyCode.Down -> "Down"
            ClientKeyCode.Left -> "Left"
            ClientKeyCode.Right -> "Right"
            ClientKeyCode.Home -> "Home"
            ClientKeyCode.End -> "End"
            ClientKeyCode.PageUp -> "PageUp"
            ClientKeyCode.PageDown -> "PageDown"
            ClientKeyCode.Delete -> "Delete"
            ClientKeyCode.CharC -> "c"
            ClientKeyCode.CharB -> "b"
            ClientKeyCode.CharD -> "d"
            ClientKeyCode.CharZ -> "z"
            ClientKeyCode.CharY -> "y"
            ClientKeyCode.CharN -> "n"
        }
        return buildString {
            if (ev.ctrl) append("ctrl+")
            if (ev.alt) append("alt+")
            if (ev.shift && base.length > 1) append("shift+")
            append(base)
        }
    }

    /**
     * Returns the live session of [machine].
     *
     * A machine with a [SessionSupervisor] gets a session only from its supervisor. When its session is closed, this
     * function tells the supervisor and throws a [SessionUnavailableException]. A machine at a connect step gets a
     * new session. For all other machines, for example after [disconnect], this function throws a
     * [SessionUnavailableException] and opens nothing.
     */
    private suspend fun getOrOpenSession(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Session {
        liveSession(machine.id)?.let { return it }
        val supervisor = supervisors[machine.id]
        if (supervisor != null) {
            supervisor.sessionLost()
            throw SessionUnavailableException(machine.name)
        }
        if (machine.id !in connectSteps) throw SessionUnavailableException(machine.name)
        return openSessionLocked(machine, route, keepLiveSession = true)
    }

    /**
     * Waits for the session of a machine with a [SessionSupervisor] before a command. It waits only for the
     * attempt that runs now ([SessionSupervisor.awaitCurrentAttempt]). Throws a [SessionUnavailableException] when
     * no session is live after that attempt. A machine with no supervisor does not wait.
     */
    private suspend fun awaitSession(machine: HostMachine) {
        val supervisor = supervisors[machine.id] ?: return
        if (liveSession(machine.id) != null) return
        supervisor.sessionLost()
        if (!supervisor.awaitCurrentAttempt()) throw SessionUnavailableException(machine.name)
    }

    /**
     * One attempt of the reconnect loop of [machine]. It closes the old sessions, opens new sessions, and returns the
     * kind of the result. The attempt reads the password again, because the user can change it.
     */
    private suspend fun reconnectAttempt(machine: HostMachine, route: ResolvedSshRoute): SshAuthResult =
        withContext(Dispatchers.IO) {
            try {
                openSessionLocked(machine, route, keepLiveSession = false)
                // The supervisor stopped while the session opened, for example because the user disconnected.
                if (!isActive) closeSessions(machine.id)
                ensureActive()
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "RealSSH",
                    message = "Reconnected to ${describeRoute(route)}",
                )
                SshAuthResult.Success
            } catch (error: CancellationException) {
                // A cancelled loop ends here. A cancellation from inside the attempt is a failed attempt.
                if (!isActive) throw error
                SshAuthResult.Failed(error)
            } catch (error: Exception) {
                RealSshBackend.toAuthResult(error)
            }
        }

    /**
     * Opens new sessions for [machine] under the session lock of the machine, and returns the target session. The old
     * sessions and workers close first. When [keepLiveSession] is true and the machine has a live session, this
     * function returns that session and opens nothing.
     *
     * A password machine with no stored password gets the password from the user first ([loginPassword]). When the
     * user wants to save it, the function saves it after the login passes ([savePassword]). When a
     * `keyboard-interactive` question went to the user during the login, the function does not save it, because the
     * login can have passed with a later answer and not with the typed password.
     */
    private suspend fun openSessionLocked(
        machine: HostMachine,
        route: ResolvedSshRoute,
        keepLiveSession: Boolean,
    ): Session =
        sessionMutexFor(machine.id).withLock {
            if (keepLiveSession) liveSession(machine.id)?.let { return@withLock it }
            inputWorkers.remove(machine.id)?.close()
            pollWorkers.remove(machine.id)?.close()
            // Close the old sessions. When the bastion session is dead, the target session is dead too.
            closeSessions(machine.id)
            // The host key check reads only the loaded entries.
            knownHostsStore.awaitLoaded()
            JschAndroidCrypto.installGlobalDefaults()
            val jsch = JSch()
            val preferredAuths = when (val auth = machine.authMethod) {
                is SshAuthMethod.Password -> "password,keyboard-interactive,publickey"
                is SshAuthMethod.KeystoreKey -> {
                    jsch.addIdentity(
                        AndroidKeystoreJschIdentity(
                            keystoreKeyManager = keystoreKeyManagerProvider(),
                            keyAlias = auth.keyAlias,
                            algorithm = auth.algorithm,
                        ),
                        null,
                    )
                    "publickey,password,keyboard-interactive"
                }
                is SshAuthMethod.HardwareSecurityKey -> {
                    // Without the signer, this build does not support the method. Stop before the network connection.
                    val signer = hardwareKeySigner
                        ?: throw UnsupportedOperationException(SshTransportManager.HARDWARE_KEY_NOT_SUPPORTED)
                    if (!auth.isEnrolled) throw HardwareKeyException(HardwareKeyException.Reason.NotEnrolled)
                    jsch.addIdentity(
                        HardwareKeyJschIdentity(
                            machineId = machine.id,
                            key = auth,
                            signer = signer,
                            callerJob = currentCoroutineContext()[Job],
                        ),
                        null,
                    )
                    "publickey,password,keyboard-interactive"
                }
            }
            val passwordAuth = machine.authMethod as? SshAuthMethod.Password
            val loginPassword = passwordAuth?.let { loginPassword(machine, route, it) }
            val password = loginPassword?.bytes
            try {
                val logins = SessionLogins(
                    jsch = jsch,
                    machine = machine,
                    password = password,
                    preferredAuths = preferredAuths,
                    callerJob = currentCoroutineContext()[Job],
                )
                // The bastion uses the same password or key as the machine.
                val bastion = if (route.viaBastion) connectBastion(logins, route) else null
                val session = try {
                    connectTarget(logins, route, bastion)
                } catch (error: Throwable) {
                    bastion?.let { runCatching { it.disconnect() } }
                    throw error
                }
                bastion?.let { bastionSessions[machine.id] = it }
                sessionIds[session] = sessionIdCounter.getAndIncrement()
                sessions[machine.id] = session
                if (passwordAuth != null && loginPassword?.save == true) {
                    if (logins.askedUser) {
                        // A later answer of the user can be the correct password. Then the typed password can be
                        // wrong, and the app must not keep it.
                        sshDebugLog.record(
                            machineId = machine.id,
                            tag = "RealSSH",
                            message = "The login asked the user more questions, so the app did not save the " +
                                "typed password of ${machine.name}. Edit the machine to save the password.",
                        )
                    } else {
                        savePassword(machine, passwordAuth, loginPassword.bytes)
                    }
                }
                session
            } finally {
                // JSch keeps its own copy, and clears it after the login.
                password?.fill(0)
            }
        }

    /**
     * Returns the open session of [machineId], or null. A session through a bastion is open only while its bastion
     * session is open too, because the bastion session carries all of its data.
     */
    private fun liveSession(machineId: String): Session? {
        val session = sessions[machineId]?.takeIf { it.isConnected } ?: return null
        val bastion = bastionSessions[machineId]
        return session.takeIf { bastion == null || bastion.isConnected }
    }

    /** Closes the session of [machineId] first, and then its bastion session. */
    private fun closeSessions(machineId: String) {
        sessions.remove(machineId)?.let { session ->
            sessionIds.remove(session)
            runCatching { session.disconnect() }
        }
        bastionSessions.remove(machineId)?.let { runCatching { it.disconnect() } }
    }

    /**
     * The values that the bastion session and the target session of one login share. [password] is null for a key
     * machine. [callerJob] is the job of the login, so that a cancel stops the wait for the answers of the user.
     */
    private class SessionLogins(
        val jsch: JSch,
        val machine: HostMachine,
        val password: ByteArray?,
        val preferredAuths: String,
        val callerJob: Job?,
    ) {
        /**
         * True after a `keyboard-interactive` question of the bastion or the target went to the user. Then the login
         * did not pass with the password alone, so the client does not save a typed password.
         */
        @Volatile
        var askedUser: Boolean = false
    }

    /**
     * Returns a new session as [username] to [host] and [port], with the settings that the bastion session and the
     * target session use. The session gets a [KeyboardInteractiveUserInfo] for the `host:port` of the session.
     */
    private fun newSession(
        logins: SessionLogins,
        username: String,
        host: String,
        port: Int,
    ): Session {
        val session = logins.jsch.getSession(username, host, port)
        logins.password?.let(session::setPassword)
        session.userInfo = KeyboardInteractiveUserInfo(
            machineId = logins.machine.id,
            machineName = displayNameOf(logins.machine),
            hostPort = KnownHost.formatHostPort(host, port),
            password = logins.password,
            callerJob = logins.callerJob,
            sshDebugLog = sshDebugLog,
            ask = { prompt ->
                logins.askedUser = true
                userPrompter(prompt)
            },
        )
        val props = Properties().apply {
            put("PreferredAuthentications", logins.preferredAuths)
            put("Compression", "none")
        }
        session.setConfig(props)
        runCatching {
            session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
            session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
        }
        JschAndroidCrypto.configureSession(session)
        if (logins.machine.authMethod is SshAuthMethod.HardwareSecurityKey) {
            JschAndroidCrypto.enableSecurityKeyAlgorithms(session)
        }
        return session
    }

    /**
     * Logs in to the bastion of [route] as [ResolvedSshRoute.entryUsername]. The host key check uses the `host:port`
     * of the bastion, so an unknown bastion key gives a [HostKeyRejectedException] for the bastion. The questions of
     * the bastion go to the user with the `host:port` of the bastion.
     *
     * Each other JSch error becomes a [BastionConnectException] that names the bastion.
     */
    private fun connectBastion(
        logins: SessionLogins,
        route: ResolvedSshRoute,
    ): Session {
        val bastion = newSession(
            logins = logins,
            username = route.entryUsername,
            host = route.entryHost,
            port = route.entryPort,
        )
        bastion.setSocketFactory(socketFactory)
        try {
            knownHostsRepository.connect(bastion, SOCKET_CONNECT_TIMEOUT_MS)
        } catch (error: JSchException) {
            throw BastionConnectException(route.bastionAddress, error)
        }
        return bastion
    }

    /**
     * Logs in to the target of [route] as [ResolvedSshRoute.targetUsername]. With a [bastion], the session sends its
     * data over a `direct-tcpip` channel of [bastion] ([BastionChannelProxy]). In the two cases the session has the
     * `host:port` of the target, so the host key check and the questions to the user use the target.
     */
    private fun connectTarget(
        logins: SessionLogins,
        route: ResolvedSshRoute,
        bastion: Session?,
    ): Session {
        val session = newSession(
            logins = logins,
            username = route.targetUsername,
            host = route.targetHost,
            port = route.targetPort,
        )
        if (bastion == null) {
            session.setSocketFactory(socketFactory)
            knownHostsRepository.connect(session, SOCKET_CONNECT_TIMEOUT_MS)
        } else {
            val proxy = BastionChannelProxy(bastion, route.bastionAddress)
            session.setProxy(proxy)
            knownHostsRepository.connect(session, SOCKET_CONNECT_TIMEOUT_MS)
            // A socket gets the session timeout after the login. Give the same read timeout to the channel.
            proxy.readTimeoutMs = session.timeout
        }
        return session
    }

    /** The password of one login. [save] is true when the user typed it and wants to keep it on this device. */
    private class LoginPassword(val bytes: ByteArray, val save: Boolean)

    /**
     * Returns the password of [machine] for one login over [route]. Clear [LoginPassword.bytes] with zeros after use.
     *
     * When this device has no password for [machine], the function asks the user through [userPrompter], with an
     * offer to save the password. With no UI, it throws a [MissingSecretException]. When the user cancels or does not
     * answer in the time limit, it throws a [UserInputException] for the target of [route].
     */
    private suspend fun loginPassword(
        machine: HostMachine,
        route: ResolvedSshRoute,
        auth: SshAuthMethod.Password,
    ): LoginPassword {
        resolvePassword(machine, auth)?.let { return LoginPassword(it, save = false) }
        val hostPort = route.targetAddress
        val outcome = userPrompter(
            KeyboardInteractivePrompt(
                machineId = machine.id,
                machineName = displayNameOf(machine),
                hostPort = hostPort,
                name = "",
                instruction = "",
                questions = listOf(KeyboardInteractiveQuestion(text = PASSWORD_QUESTION, echo = false)),
                passwordMissing = true,
            ),
        )
        val typed = (outcome as? KeyboardInteractiveOutcome.Answered)?.answers?.singleOrNull()
        if (outcome is KeyboardInteractiveOutcome.Answered && typed != null) {
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "The user typed the password of ${machine.name} for this connection.",
            )
            return LoginPassword(typed.encodeToByteArray(), save = outcome.savePassword)
        }
        val error = when (outcome) {
            KeyboardInteractiveOutcome.NoUi -> MissingSecretException(machine.name)
            // An answer with a different number of answers is an error of the UI.
            is KeyboardInteractiveOutcome.Answered ->
                UserInputException.of(hostPort, KeyboardInteractiveOutcome.Cancelled)
            else -> UserInputException.of(hostPort, outcome)
        }
        sshDebugLog.record(machineId = machine.id, tag = "RealSSH", message = error.message.orEmpty(), isError = true)
        throw error
    }

    /**
     * Returns the stored password of [machine], or null when this device has no password for it. Clear the result
     * with zeros after use.
     *
     * A [SecretRef] value gives the password from [secretStore]. A value with no prefix is a plain-text password that
     * the load migration did not move. The client uses it as it is. An empty value, a reference that is not valid, and
     * a reference with no secret give null. [CredentialValidator] gives [CredentialState.PasswordMissing] for the same
     * values.
     */
    private suspend fun resolvePassword(machine: HostMachine, auth: SshAuthMethod.Password): ByteArray? {
        val saved = savedPasswordRefs[machine.id]?.takeIf { (oldValue, _) -> oldValue == auth.encryptedPasswordRef }
        val value = saved?.second ?: auth.encryptedPasswordRef
        if (value.isEmpty()) return null
        if (!SecretRef.hasPrefix(value)) {
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "The password of ${machine.name} is not in the secret store. The next start moves it.",
            )
            return value.encodeToByteArray()
        }
        val ref = SecretRef.parse(value) ?: return null
        return secretStore.get(ref)
    }

    /**
     * Keeps the typed [password] of [machine] in [secretStore] after a login that passed with that password. The
     * secret uses the reference of [auth] when it is valid. Else it gets a new reference, and [machineStore] gets the
     * new reference for the machine. When the user changed or removed the machine during the login, the function
     * removes the new secret again. An error does not stop the session. The debug log tells it, and the next connect
     * asks again.
     */
    private suspend fun savePassword(machine: HostMachine, auth: SshAuthMethod.Password, password: ByteArray) {
        val oldValue = auth.encryptedPasswordRef
        val existingRef = SecretRef.parse(oldValue)
        val ref = existingRef ?: SecretRef.newPasswordRef()
        try {
            secretStore.put(ref, password)
            if (existingRef == null) {
                val newValue = SecretRef.format(ref)
                if (!updatePasswordRef(machine.id, oldValue, newValue)) {
                    // No machine refers to the new secret, so remove it.
                    secretStore.remove(ref)
                    sshDebugLog.record(
                        machineId = machine.id,
                        tag = "RealSSH",
                        message = "${machine.name} changed during the login, so the app did not save the password.",
                    )
                    return
                }
                savedPasswordRefs[machine.id] = oldValue to newValue
            }
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "Saved the password of ${machine.name} on this device.",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The error comes from the device key store or the file system. It does not contain the password.
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "Could not save the password of ${machine.name}: ${error.message ?: error::class.simpleName}",
                isError = true,
            )
        }
    }

    /**
     * Puts the password reference [newValue] into the machine [machineId] of [machineStore]. It changes the machine
     * only while the machine still has [oldValue], so that a newer edit of the user stays.
     *
     * Returns false when the machine is gone or has a different password value. With no [machineStore], it returns
     * true, and only [savedPasswordRefs] keeps the new reference.
     */
    private suspend fun updatePasswordRef(machineId: String, oldValue: String, newValue: String): Boolean {
        val store = machineStore ?: return true
        val current = store.getMachine(machineId) ?: return false
        val auth = current.authMethod as? SshAuthMethod.Password ?: return false
        if (auth.encryptedPasswordRef != oldValue) return false
        store.upsertMachine(current.copy(authMethod = auth.copy(encryptedPasswordRef = newValue)))
        return true
    }

    /** Returns the name of [machine] that the prompts show. */
    private fun displayNameOf(machine: HostMachine): String = machine.name.ifBlank { machine.displayAddress }

    private fun workerMapFor(lane: WorkerLane): ConcurrentHashMap<String, PersistentShellWorker> =
        when (lane) {
            WorkerLane.Input -> inputWorkers
            WorkerLane.Poll -> pollWorkers
        }

    private fun getOrOpenWorkerLocked(
        machineId: String,
        lane: WorkerLane,
        session: Session,
    ): PersistentShellWorker {
        val map = workerMapFor(lane)
        val existing = map[machineId]
        if (existing != null && existing.isAlive) {
            return existing
        }
        existing?.close()
        val channel = session.openChannel("exec") as ChannelExec
        val initCmd = "export PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:/usr/local/bin:\$PATH\"; exec sh"
        channel.setCommand(initCmd)
        val rawIn = channel.inputStream
        val rawOut = channel.outputStream
        // No code reads the standard error of a worker. A buffer would grow with each command that fails.
        channel.setErrStream(DiscardOutputStream)
        channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
        val worker = PersistentShellWorker(
            channel = channel,
            stdin = rawOut,
            stdout = BufferedReader(InputStreamReader(rawIn, Charsets.UTF_8), 32 * 1024),
        )
        map[machineId] = worker
        return worker
    }

    /**
     * Runs [commands] on the worker of [lane] and returns their output.
     *
     * [onRoundTrip] gets the time of the batch on a worker that ran a batch already. The time does not include the
     * wait for the lane lock, for a reconnect, or for a new worker. The first batch of a new worker gives no time,
     * because it can include the start of the remote shell. A batch that needs a new worker after a failure gives no
     * time. See [timeRoundTrip].
     *
     * [onLaneLocked] runs when the batch has the lane lock, before the batch goes to the worker. The lane runs one batch
     * at a time, so the order of these calls is the order of the batches on the lane. [remoteBatchExecutor] has no lane
     * lock, so with it [onLaneLocked] runs just before the call.
     */
    private suspend fun executeBatchOnLane(
        machine: HostMachine,
        route: ResolvedSshRoute,
        lane: WorkerLane,
        commands: List<RemoteShellCommand>,
        onRoundTrip: ((Duration) -> Unit)? = null,
        onLaneLocked: (() -> Unit)? = null,
    ): String {
        if (commands.isEmpty()) return ""
        val customExecutor = remoteBatchExecutor
        if (customExecutor != null) {
            onLaneLocked?.invoke()
            return timeRoundTrip(machine.id, onRoundTrip) { customExecutor(machine, route, lane, commands) }
        }
        // Wait for a reconnect outside the lane lock, so that a long reconnect does not block the other users of
        // the lane.
        awaitSession(machine)
        // The worker, the new worker after a failure, and the one-shot fallback all use this one value.
        val herdr = HerdrCommand.of(machine)
        return laneMutexFor(machine.id, lane).withLock {
            onLaneLocked?.invoke()
            val session = getOrOpenSession(machine, route)
            runCatching {
                val worker = getOrOpenWorkerLocked(machine.id, lane, session)
                val timing = onRoundTrip.takeIf { worker.ranBatch }
                timeRoundTrip(machine.id, timing) { runCommandsOnWorker(worker, commands, herdr) }
                    .also { worker.ranBatch = true }
            }.recoverCatching {
                // If the persistent shell channel dropped, discard it and fall back / reopen cleanly
                workerMapFor(lane).remove(machine.id)?.close()
                val freshSession = getOrOpenSession(machine, route)
                runCatching {
                    val freshWorker = getOrOpenWorkerLocked(machine.id, lane, freshSession)
                    runCommandsOnWorker(freshWorker, commands, herdr).also { freshWorker.ranBatch = true }
                }.getOrElse {
                    execBatchOneShot(freshSession, commands, herdr)
                }
            }.getOrThrow()
        }
    }

    /**
     * Runs [batch] and gives its time to [onRoundTrip]. It gives no time when [batch] fails. It also gives no time
     * when the supervisor of [machineId] reconnected or the session changed while [batch] ran, because then the
     * time includes more than one round trip.
     */
    private suspend fun timeRoundTrip(
        machineId: String,
        onRoundTrip: ((Duration) -> Unit)?,
        batch: suspend () -> String,
    ): String {
        if (onRoundTrip == null) return batch()
        val before = steadySessionMark(machineId)
        val start = timeSource.markNow()
        val output = batch()
        val elapsed = start.elapsedNow()
        if (before != null && before == steadySessionMark(machineId)) onRoundTrip(elapsed)
        return output
    }

    /**
     * Returns the live session of [machineId] in a [SessionMark], or null while the supervisor of the machine is not
     * in [SessionSupervisor.State.Live]. Two equal marks show that no reconnect occurred between them.
     */
    private fun steadySessionMark(machineId: String): SessionMark? {
        val state = supervisors[machineId]?.state?.value
        if (state != null && state != SessionSupervisor.State.Live) return null
        return SessionMark(liveSessionId(machineId))
    }

    /** The live session of a machine at one time, or no session. See [steadySessionMark]. */
    private data class SessionMark(val sessionId: Long?)

    private fun runCommandsOnWorker(
        worker: PersistentShellWorker,
        commands: List<RemoteShellCommand>,
        herdr: String,
    ): String {
        val batchId = shellBatchCounter.incrementAndGet()
        val (script, sentinel) = buildShellBatchScript(batchId, commands, herdr)
        worker.stdin.write(script.toByteArray(Charsets.UTF_8))
        worker.stdin.flush()

        val out = StringBuilder(256)
        while (true) {
            val line = worker.stdout.readLine()
                ?: throw IOException("Persistent SSH shell stream closed before sentinel")
            val trimmed = line.trim()
            if (trimmed == sentinel) {
                break
            }
            if (trimmed.endsWith(sentinel)) {
                val prefix = line.substringBeforeLast(sentinel)
                if (prefix.isNotEmpty()) {
                    out.append(prefix).append('\n')
                }
                break
            }
            out.append(line).append('\n')
        }
        return out.toString()
    }

    private fun execBatchOneShot(
        session: Session,
        commands: List<RemoteShellCommand>,
        herdr: String,
    ): String {
        val body = buildOneShotBatchScript(shellBatchCounter.incrementAndGet(), commands, herdr)
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(body)
        val stdout = channel.inputStream
        channel.setErrStream(ByteArrayOutputStream())
        channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
        return try {
            stdout.readBytes().toString(Charsets.UTF_8)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    /**
     * Builds the script of [execBatchOneShot]. Each `herdr` call of a [RemoteShellCommand.ApiBridgeLine] starts with
     * [herdr], the result of [HerdrCommand.of] for the machine.
     */
    internal fun buildOneShotBatchScript(
        batchId: Long,
        commands: List<RemoteShellCommand>,
        herdr: String,
    ): String {
        return buildString(commands.size * 160 + 96) {
            append("export PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:/usr/local/bin:\$PATH\"\n")
            commands.forEachIndexed { index, cmd ->
                when (cmd) {
                    is RemoteShellCommand.Exec -> {
                        append(cmd.command.trim()).append('\n')
                    }
                    is RemoteShellCommand.ApiBridgeLine -> {
                        val eofMarker = "__BISON_EOF_${batchId}_${index}__"
                        append(herdr).append(" remote-api-bridge <<'")
                            .append(eofMarker)
                            .append("'\n")
                            .append(cmd.ndjsonLine.trim())
                            .append('\n')
                            .append(eofMarker)
                            .append('\n')
                    }
                }
            }
        }
    }

    /**
     * Builds the script of one batch on a persistent worker, and the sentinel line at its end. Each `herdr` call of a
     * [RemoteShellCommand.ApiBridgeLine] starts with [herdr], the result of [HerdrCommand.of] for the machine.
     */
    internal fun buildShellBatchScript(
        batchId: Long,
        commands: List<RemoteShellCommand>,
        herdr: String,
    ): Pair<String, String> {
        val sentinel = "__BISON_DONE_${batchId}__"
        val script = buildString(commands.size * 160 + 64) {
            commands.forEachIndexed { index, cmd ->
                when (cmd) {
                    is RemoteShellCommand.Exec -> {
                        append(cmd.command.trim())
                        append('\n')
                    }

                    is RemoteShellCommand.ApiBridgeLine -> {
                        val eofMarker = "__BISON_EOF_${batchId}_${index}__"
                        append(herdr)
                        append(" remote-api-bridge <<'")
                        append(eofMarker)
                        append("'\n")
                        append(cmd.ndjsonLine.trim())
                        append('\n')
                        append(eofMarker)
                        append('\n')
                    }
                }
            }
            append("printf '\\n")
            append(sentinel)
            append("\\n'\n")
        }
        return script to sentinel
    }

    private data class ParsedSnapshotBundle(
        val snapshot: ClientShellSnapshot,
        val layoutsByTab: Map<String, PaneLayoutSnapshot>,
    )

    private fun parseSnapshotObject(
        machineId: String,
        snapshotObj: JsonObject,
        sel: ClientSelectionState,
    ): ParsedSnapshotBundle {
        val serverFocusedWsId = snapshotObj["focused_workspace_id"]?.jsonPrimitive?.content
        val serverFocusedTabId = snapshotObj["focused_tab_id"]?.jsonPrimitive?.content
        val serverFocusedPaneId = snapshotObj["focused_pane_id"]?.jsonPrimitive?.content

        val workspacesArray = snapshotObj["workspaces"] as? JsonArray ?: JsonArray(emptyList())
        val tabsArray = snapshotObj["tabs"] as? JsonArray ?: JsonArray(emptyList())
        val panesArray = snapshotObj["panes"] as? JsonArray ?: JsonArray(emptyList())
        val agentsArray = snapshotObj["agents"] as? JsonArray ?: JsonArray(emptyList())
        val layoutsArray = snapshotObj["layouts"] as? JsonArray ?: JsonArray(emptyList())

        val rawWorkspaces = workspacesArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["workspace_id"] ?: obj["id"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val activeTabId = obj["active_tab_id"]?.jsonPrimitive?.content
            val label = obj["label"]?.jsonPrimitive?.content ?: id
            val branch = obj["branch"]?.jsonPrimitive?.content
            val statusStr = (obj["agent_status"] ?: obj["state"])?.jsonPrimitive?.content
            ClientShellWorkspace(
                workspaceId = id,
                activeTabId = activeTabId,
                label = label,
                branch = branch,
                focused = obj["focused"]?.jsonPrimitive?.booleanOrNull ?: (id == serverFocusedWsId),
                agentStatus = parseAgentStatus(statusStr),
            )
        }

        val effectiveWsId = sel.focusedWorkspaceId?.takeIf { id -> rawWorkspaces.any { it.workspaceId == id } }
            ?: serverFocusedWsId
            ?: rawWorkspaces.firstOrNull()?.workspaceId
        sel.focusedWorkspaceId = effectiveWsId

        val rawTabs = tabsArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["tab_id"] ?: obj["id"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content ?: effectiveWsId.orEmpty()
            val number = obj["number"]?.jsonPrimitive?.intOrNull ?: 1
            val label = obj["label"]?.jsonPrimitive?.content ?: id
            val statusStr = (obj["agent_status"] ?: obj["state"])?.jsonPrimitive?.content
            ClientShellTab(
                tabId = id,
                workspaceId = wsId,
                number = number,
                label = label,
                focused = obj["focused"]?.jsonPrimitive?.booleanOrNull ?: (id == serverFocusedTabId),
                agentStatus = parseAgentStatus(statusStr),
            )
        }

        val wsTabs = rawTabs.filter { it.workspaceId == effectiveWsId }
        val activeWorkspaceObj = rawWorkspaces.firstOrNull { it.workspaceId == effectiveWsId }
        val effectiveTabId = sel.focusedTabId?.takeIf { id -> wsTabs.any { it.tabId == id } }
            ?: activeWorkspaceObj?.activeTabId?.takeIf { id -> wsTabs.any { it.tabId == id } }
            ?: serverFocusedTabId?.takeIf { id -> wsTabs.any { it.tabId == id } }
            ?: wsTabs.firstOrNull()?.tabId
            ?: rawTabs.firstOrNull()?.tabId
        sel.focusedTabId = effectiveTabId

        val rawPanes = panesArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["pane_id"] ?: obj["id"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content ?: effectiveWsId.orEmpty()
            val tId = obj["tab_id"]?.jsonPrimitive?.content ?: effectiveTabId.orEmpty()
            val label = obj["label"]?.jsonPrimitive?.content ?: id
            val cwd = obj["cwd"]?.jsonPrimitive?.content.orEmpty()
            ClientShellPane(
                paneId = id,
                workspaceId = wsId,
                tabId = tId,
                label = label,
                cwd = cwd,
                focused = obj["focused"]?.jsonPrimitive?.booleanOrNull ?: (id == serverFocusedPaneId),
            )
        }

        val tabPanes = rawPanes.filter { it.tabId == effectiveTabId }
        val effectivePaneId = sel.focusedPaneId?.takeIf { id -> tabPanes.any { it.paneId == id } }
            ?: serverFocusedPaneId?.takeIf { id -> tabPanes.any { it.paneId == id } }
            ?: tabPanes.firstOrNull()?.paneId
            ?: rawPanes.firstOrNull()?.paneId
        sel.focusedPaneId = effectivePaneId

        val agents = agentsArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val pId = obj["pane_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val tId = obj["tab_id"]?.jsonPrimitive?.content.orEmpty()
            val name = obj["name"]?.jsonPrimitive?.content
            val agent = obj["agent"]?.jsonPrimitive?.content.orEmpty()
            val displayAgent = obj["display_agent"]?.jsonPrimitive?.content ?: name ?: agent
            val title = obj["title"]?.jsonPrimitive?.content
            val terminalTitle = obj["terminal_title"]?.jsonPrimitive?.content
            val terminalTitleStripped = obj["terminal_title_stripped"]?.jsonPrimitive?.content
            val statusStr = (obj["agent_status"] ?: obj["state"])?.jsonPrimitive?.content
            ClientShellAgent(
                paneId = pId,
                workspaceId = wsId,
                tabId = tId,
                name = name,
                displayAgent = displayAgent,
                agent = agent,
                title = title,
                terminalTitle = terminalTitle,
                terminalTitleStripped = terminalTitleStripped,
                agentStatus = parseAgentStatus(statusStr),
                focused = pId == effectivePaneId,
            )
        }

        // Propagate agent statuses up to workspaces and tabs so top bar pills show attention dots
        val updatedPanes = rawPanes.map { p ->
            p.copy(focused = p.paneId == effectivePaneId)
        }
        val updatedTabs = rawTabs.map { t ->
            val topTabAgentStatus = agents
                .filter { it.tabId == t.tabId }
                .minByOrNull { it.agentStatus.priorityOrder }
                ?.agentStatus
            t.copy(
                focused = t.tabId == effectiveTabId,
                agentStatus = topTabAgentStatus?.takeIf { it != AgentStatus.Unknown } ?: t.agentStatus,
            )
        }
        val updatedWorkspaces = rawWorkspaces.map { ws ->
            val topWsAgentStatus = agents
                .filter { it.workspaceId == ws.workspaceId }
                .minByOrNull { it.agentStatus.priorityOrder }
                ?.agentStatus
            ws.copy(
                focused = ws.workspaceId == effectiveWsId,
                agentStatus = topWsAgentStatus?.takeIf { it != AgentStatus.Unknown } ?: ws.agentStatus,
            )
        }

        val layoutsByTab = mutableMapOf<String, PaneLayoutSnapshot>()
        layoutsArray.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val tabId = obj["tab_id"]?.jsonPrimitive?.content ?: return@forEach
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content ?: effectiveWsId.orEmpty()
            val zoomed = obj["zoomed"]?.jsonPrimitive?.booleanOrNull ?: false
            val lPanes = (obj["panes"] as? JsonArray)?.mapNotNull { pEl ->
                val pObj = pEl as? JsonObject ?: return@mapNotNull null
                val pId = pObj["pane_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val rectObj = pObj["rect"] as? JsonObject ?: return@mapNotNull null
                PaneLayoutPane(
                    paneId = pId,
                    rect = parseSurfaceRect(rectObj),
                    focused = pId == effectivePaneId,
                )
            }.orEmpty()
            val lSplits = (obj["splits"] as? JsonArray)?.mapNotNull { sEl ->
                val sObj = sEl as? JsonObject ?: return@mapNotNull null
                val sId = sObj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val dirStr = sObj["direction"]?.jsonPrimitive?.content ?: "vertical"
                val ratio = sObj["ratio"]?.jsonPrimitive?.floatOrNull ?: 0.5f
                val rectObj = sObj["rect"] as? JsonObject ?: return@mapNotNull null
                PaneLayoutSplit(
                    id = sId,
                    direction = if (dirStr.equals("horizontal", ignoreCase = true)) {
                        PaneSurfaceSplitDirection.Horizontal
                    } else {
                        PaneSurfaceSplitDirection.Vertical
                    },
                    ratio = ratio,
                    rect = parseSurfaceRect(rectObj),
                )
            }.orEmpty()
            layoutsByTab[tabId] = PaneLayoutSnapshot(
                workspaceId = wsId,
                tabId = tabId,
                zoomed = zoomed,
                panes = lPanes,
                splits = lSplits,
            )
        }

        val snapshot = ClientShellSnapshot(
            bootId = "herdr-$machineId",
            revision = revisionCounter.incrementAndGet().toULong(),
            focusedWorkspaceId = effectiveWsId,
            focusedTabId = effectiveTabId,
            focusedPaneId = effectivePaneId,
            workspaces = updatedWorkspaces,
            tabs = updatedTabs,
            panes = updatedPanes,
            agents = agents,
        )
        return ParsedSnapshotBundle(snapshot = snapshot, layoutsByTab = layoutsByTab)
    }

    private fun buildCompositeSurfaceFrame(
        machineId: String,
        snapshot: ClientShellSnapshot,
        layout: PaneLayoutSnapshot?,
        ansiByPane: Map<String, String>,
    ): PaneSurfaceFrame {
        val rawLayoutPanes = layout?.panes?.takeIf { it.isNotEmpty() } ?: run {
            val fallbackPaneId = snapshot.focusedPaneId ?: snapshot.panes.firstOrNull()?.paneId ?: "w1:p1"
            listOf(
                PaneLayoutPane(
                    paneId = fallbackPaneId,
                    rect = SurfaceRect(0u, 0u, 120u, 40u),
                    focused = true,
                ),
            )
        }

        val rawTotalCols = rawLayoutPanes.maxOf { (it.rect.x + it.rect.width).toInt() }.coerceIn(20, 300)
        val rawTotalRows = rawLayoutPanes.maxOf { (it.rect.y + it.rect.height).toInt() }.coerceIn(8, 150)
        val customViewport = viewportSizes[machineId]
        val totalCols = customViewport?.first ?: rawTotalCols
        val totalRows = customViewport?.second ?: rawTotalRows

        fun scaleRect(r: SurfaceRect): SurfaceRect {
            if (totalCols == rawTotalCols && totalRows == rawTotalRows) return r
            val sx = ((r.x.toInt() * totalCols) / rawTotalCols).coerceIn(0, totalCols - 1)
            val sy = ((r.y.toInt() * totalRows) / rawTotalRows).coerceIn(0, totalRows - 1)
            val ex = (((r.x.toInt() + r.width.toInt()) * totalCols) / rawTotalCols).coerceIn(sx + 1, totalCols)
            val ey = (((r.y.toInt() + r.height.toInt()) * totalRows) / rawTotalRows).coerceIn(sy + 1, totalRows)
            return SurfaceRect(
                x = sx.toUShort(),
                y = sy.toUShort(),
                width = (ex - sx).toUShort(),
                height = (ey - sy).toUShort(),
            )
        }

        val layoutPanes = rawLayoutPanes.map { lp ->
            lp.copy(rect = scaleRect(lp.rect))
        }
        val scaledLayout = layout?.copy(
            panes = layoutPanes,
            splits = layout.splits.map { sp -> sp.copy(rect = scaleRect(sp.rect)) },
        )

        val grid = Array(totalCols * totalRows) { BLANK_CELL }

        var focusedCursor: CursorState? = null
        val surfacePanes = layoutPanes.map { lp ->
            val paneMeta = snapshot.panes.firstOrNull { it.paneId == lp.paneId }
            val agentMeta = snapshot.agents.firstOrNull { it.paneId == lp.paneId }
            val ansiText = ansiByPane[lp.paneId].orEmpty()
            val isFocused = lp.paneId == snapshot.focusedPaneId || lp.focused

            val cursorPos = renderAnsiIntoRect(
                ansiText = ansiText,
                rect = lp.rect,
                grid = grid,
                gridCols = totalCols,
                gridRows = totalRows,
            )
            if (isFocused) {
                focusedCursor = CursorState(
                    x = cursorPos.first.toUShort(),
                    y = cursorPos.second.toUShort(),
                    shape = CursorShape.Block,
                    visible = true,
                )
            }

            PaneSurfacePane(
                paneId = lp.paneId,
                rect = lp.rect,
                innerRect = lp.rect,
                focused = isFocused,
                zoomed = scaledLayout?.zoomed == true,
                title = agentMeta?.displayAgent?.takeIf { it.isNotBlank() }
                    ?: paneMeta?.label
                    ?: lp.paneId,
                agentStatus = agentMeta?.agentStatus ?: AgentStatus.Unknown,
                scroll = paneScrolls[machineId]?.get(lp.paneId)?.copy(viewportRows = lp.rect.height)
                    ?: PaneSurfaceScroll(viewportRows = lp.rect.height),
            )
        }

        val splits = if (scaledLayout != null) deriveSplitsFromLayout(scaledLayout) else emptyList()

        val baseFrame = PaneSurfaceFrame(
            bootId = snapshot.bootId,
            revision = revisionCounter.incrementAndGet().toULong(),
            frame = FrameData(
                width = totalCols.toUShort(),
                height = totalRows.toUShort(),
                cells = grid.toList(),
                cursor = focusedCursor,
            ),
            panes = surfacePanes,
            splits = splits,
        )

        // Overlay any unconfirmed optimistic local echo edits (<1.5s TTL) so polls/resizes never drop in-flight keys
        val editsDeque = optimisticEdits[machineId] ?: return baseFrame
        val activeEdits = synchronized(editsDeque) {
            val now = System.currentTimeMillis()
            while (editsDeque.isNotEmpty() && now - editsDeque.first().createdAtMs > OPTIMISTIC_ECHO_TTL_MS) {
                editsDeque.removeFirst()
            }
            if (editsDeque.isEmpty()) emptyList() else editsDeque.toList()
        }
        return activeEdits.fold(baseFrame) { acc, edit ->
            applyOptimisticEditToFrame(acc, edit)
        }
    }

    /**
     * Parses ANSI SGR and cursor escape sequences in [ansiText], wraps lines cleanly to [rect.width]
     * (clamping horizontal box-drawing divider lines to 1 row), and populates [grid] inside [rect],
     * returning the final `(col, row)` cursor coordinates.
     */
    private fun renderAnsiIntoRect(
        ansiText: String,
        rect: SurfaceRect,
        grid: Array<CellData>,
        gridCols: Int,
        gridRows: Int,
    ): Pair<Int, Int> {
        val startX = rect.x.toInt().coerceIn(0, max(0, gridCols - 1))
        val startY = rect.y.toInt().coerceIn(0, max(0, gridRows - 1))
        val maxW = rect.width.toInt().coerceAtLeast(1)
        val maxH = rect.height.toInt().coerceAtLeast(1)
        val endX = min(gridCols, startX + maxW)
        val endY = min(gridRows, startY + maxH)
        val usableW = (endX - startX).coerceAtLeast(1)
        val usableH = (endY - startY).coerceAtLeast(1)

        val rawLines = ansiText.replace("\r\n", "\n").split('\n')

        var fg: UInt = 0u
        var bg: UInt = 0u
        var mod: UShort = 0u

        val wrappedRows = ArrayList<List<CellData>>(rawLines.size + 16)

        for (line in rawLines) {
            val lineCells = ArrayList<CellData>(min(line.length.coerceAtLeast(16), 256))
            var col = 0
            var i = 0
            val maxLineCols = 512

            fun ensureCol(targetCol: Int) {
                while (lineCells.size < targetCol && lineCells.size < maxLineCols) {
                    lineCells.add(BLANK_CELL)
                }
            }

            while (i < line.length) {
                val ch = line[i]
                if (ch == '\u001b') {
                    if (i + 1 < line.length && line[i + 1] == '[') {
                        var j = i + 2
                        while (j < line.length && line[j].code !in 0x40..0x7E) {
                            j++
                        }
                        if (j < line.length) {
                            val finalChar = line[j]
                            val paramsStr = line.substring(i + 2, j)
                            when (finalChar) {
                                'm' -> {
                                    val sgrState = applySgrCodes(paramsStr, fg, bg, mod)
                                    fg = sgrState.fg
                                    bg = sgrState.bg
                                    mod = sgrState.mod
                                }
                                'C' -> {
                                    val n = paramsStr.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                    col = (col + n).coerceAtMost(maxLineCols)
                                    ensureCol(col)
                                }
                                'D' -> {
                                    val n = paramsStr.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                    col = (col - n).coerceAtLeast(0)
                                }
                                'G' -> {
                                    val n = paramsStr.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                    col = (n - 1).coerceIn(0, maxLineCols)
                                    ensureCol(col)
                                }
                                'K' -> {
                                    val mode = paramsStr.toIntOrNull() ?: 0
                                    if (mode == 0 && col < lineCells.size) {
                                        while (lineCells.size > col) {
                                            lineCells.removeAt(lineCells.lastIndex)
                                        }
                                    } else if (mode == 2) {
                                        lineCells.clear()
                                    }
                                }
                            }
                            i = j + 1
                            continue
                        }
                    } else if (i + 1 < line.length && line[i + 1] == ']') {
                        var j = i + 2
                        while (j < line.length && line[j] != '\u0007') {
                            if (line[j] == '\u001b' && j + 1 < line.length && line[j + 1] == '\\') {
                                j++
                                break
                            }
                            j++
                        }
                        i = (j + 1).coerceAtMost(line.length)
                        continue
                    }
                    i++
                    continue
                }

                if (ch == '\r') {
                    col = 0
                    i++
                    continue
                }
                if (ch == '\t') {
                    val nextStop = ((col / 4) + 1) * 4
                    col = nextStop.coerceAtMost(maxLineCols)
                    ensureCol(col)
                    i++
                    continue
                }
                if (ch == '\b') {
                    col = max(0, col - 1)
                    i++
                    continue
                }

                val cp = Character.codePointAt(line, i)
                val charCount = Character.charCount(cp)
                if (cp < 32 || cp == 0x7F) {
                    i += charCount
                    continue
                }

                val charType = Character.getType(cp)
                val isZeroWidth = cp in 0xFE00..0xFE0F ||
                    cp == 0x200B ||
                    cp == 0x200C ||
                    cp == 0x200D ||
                    charType == Character.NON_SPACING_MARK.toInt() ||
                    charType == Character.ENCLOSING_MARK.toInt() ||
                    charType == Character.FORMAT.toInt()
                if (isZeroWidth) {
                    i += charCount
                    continue
                }

                if (col < maxLineCols) {
                    ensureCol(col)
                    val cell = CellData(
                        symbol = codepointToSymbol(cp),
                        fg = fg,
                        bg = bg,
                        modifier = mod,
                    )
                    if (col < lineCells.size) {
                        lineCells[col] = cell
                    } else {
                        lineCells.add(cell)
                    }
                    col++
                }
                i += charCount
            }

            // Trim trailing unstyled spaces so padding doesn't wrap into blank rows
            while (lineCells.isNotEmpty()) {
                val tail = lineCells.last()
                if ((tail.symbol.isEmpty() || tail.symbol == " ") && tail.bg == 0u && tail.modifier == 0u.toUShort()) {
                    lineCells.removeAt(lineCells.lastIndex)
                } else {
                    break
                }
            }

            if (lineCells.size <= usableW) {
                wrappedRows.add(lineCells)
            } else {
                // Check if this row is predominantly a horizontal rule (──────)
                var nonSpaceCount = 0
                var hLineCount = 0
                for (c in lineCells) {
                    val s = c.symbol
                    if (s.isNotEmpty() && s != " ") {
                        nonSpaceCount++
                        val code = s[0].code
                        if (code in 0x2500..0x2509 || code == 0x254C || code == 0x254D || code == 0x2550 || code == '-'.code || code == '='.code) {
                            hLineCount++
                        }
                    }
                }
                val isHorizontalDivider = nonSpaceCount >= 8 && hLineCount * 4 >= nonSpaceCount * 3
                if (isHorizontalDivider) {
                    wrappedRows.add(lineCells.subList(0, usableW))
                } else {
                    var offset = 0
                    while (offset < lineCells.size) {
                        val end = min(lineCells.size, offset + usableW)
                        wrappedRows.add(lineCells.subList(offset, end))
                        offset = end
                    }
                }
            }
        }

        // Trim trailing empty rows at the very bottom if they exceed usableH, then take the last usableH rows
        while (wrappedRows.size > usableH && wrappedRows.last().isEmpty()) {
            wrappedRows.removeAt(wrappedRows.lastIndex)
        }
        val visibleRows = if (wrappedRows.size > usableH) {
            wrappedRows.takeLast(usableH)
        } else {
            wrappedRows
        }

        var lastCol = startX
        var lastRow = startY

        for (rowIdx in visibleRows.indices) {
            val row = startY + rowIdx
            if (row >= endY) break
            val rowCells = visibleRows[rowIdx]
            for (colIdx in rowCells.indices) {
                val col = startX + colIdx
                if (col >= endX) break
                val cell = rowCells[colIdx]
                grid[row * gridCols + col] = cell
                if (cell.symbol.isNotEmpty() && cell.symbol != " ") {
                    lastCol = col
                    lastRow = row
                }
            }
        }
        return min(endX - 1, lastCol + 1) to lastRow
    }

    private fun codepointToSymbol(cp: Int): String =
        if (cp in 0..<128) ASCII_SYMBOLS[cp] else String(Character.toChars(cp))

    private fun applySgrCodes(
        paramsStr: String,
        currentFg: UInt,
        currentBg: UInt,
        currentMod: UShort,
    ): SgrState {
        if (paramsStr.isEmpty()) {
            return RESET_SGR_STATE
        }
        val rawTokens = paramsStr.split(';')
        var fg = currentFg
        var bg = currentBg
        var mod = currentMod.toInt()
        var idx = 0
        while (idx < rawTokens.size) {
            val token = rawTokens[idx]
            if (token.contains(':')) {
                val sub = token.split(':')
                val mainCode = sub.firstOrNull()?.toIntOrNull() ?: -1
                if (mainCode == 4) {
                    when (sub.getOrNull(1)?.toIntOrNull() ?: 1) {
                        0 -> mod = mod and (0x0008 or 0x0010).inv()
                        3 -> mod = mod or 0x0010
                        else -> mod = mod or 0x0008
                    }
                }
                idx++
                continue
            }
            when (val c = if (token.isEmpty()) 0 else (token.toIntOrNull() ?: -1)) {
                0 -> {
                    fg = 0u
                    bg = 0u
                    mod = 0
                }
                1 -> mod = mod or 0x0001
                2 -> mod = mod or 0x0002
                3 -> mod = mod or 0x0004
                4 -> mod = mod or 0x0008
                7 -> mod = mod or 0x0040
                9 -> mod = mod or 0x0100
                22 -> mod = mod and (0x0001 or 0x0002).inv()
                23 -> mod = mod and 0x0004.inv()
                24 -> mod = mod and (0x0008 or 0x0010).inv()
                27 -> mod = mod and 0x0040.inv()
                29 -> mod = mod and 0x0100.inv()
                in 30..37 -> fg = 0x01_00_00_00u or (c - 30).toUInt()
                39 -> fg = 0u
                in 40..47 -> bg = 0x01_00_00_00u or (c - 40).toUInt()
                49 -> bg = 0u
                in 90..97 -> fg = 0x01_00_00_00u or (c - 90 + 8).toUInt()
                in 100..107 -> bg = 0x01_00_00_00u or (c - 100 + 8).toUInt()
                38 -> {
                    val mode = rawTokens.getOrNull(idx + 1)?.toIntOrNull()
                    if (mode == 5 && idx + 2 < rawTokens.size) {
                        val n = (rawTokens[idx + 2].toIntOrNull() ?: 0).coerceIn(0, 255)
                        fg = 0x01_00_00_00u or n.toUInt()
                        idx += 2
                    } else if (mode == 2 && idx + 4 < rawTokens.size) {
                        val r = (rawTokens[idx + 2].toIntOrNull() ?: 0).coerceIn(0, 255)
                        val g = (rawTokens[idx + 3].toIntOrNull() ?: 0).coerceIn(0, 255)
                        val b = (rawTokens[idx + 4].toIntOrNull() ?: 0).coerceIn(0, 255)
                        fg = 0x02_00_00_00u or (r.toUInt() shl 16) or (g.toUInt() shl 8) or b.toUInt()
                        idx += 4
                    }
                }
                48 -> {
                    val mode = rawTokens.getOrNull(idx + 1)?.toIntOrNull()
                    if (mode == 5 && idx + 2 < rawTokens.size) {
                        val n = (rawTokens[idx + 2].toIntOrNull() ?: 0).coerceIn(0, 255)
                        bg = 0x01_00_00_00u or n.toUInt()
                        idx += 2
                    } else if (mode == 2 && idx + 4 < rawTokens.size) {
                        val r = (rawTokens[idx + 2].toIntOrNull() ?: 0).coerceIn(0, 255)
                        val g = (rawTokens[idx + 3].toIntOrNull() ?: 0).coerceIn(0, 255)
                        val b = (rawTokens[idx + 4].toIntOrNull() ?: 0).coerceIn(0, 255)
                        bg = 0x02_00_00_00u or (r.toUInt() shl 16) or (g.toUInt() shl 8) or b.toUInt()
                        idx += 4
                    }
                }
                58 -> {
                    // Underline color: consume its arguments so they aren't misinterpreted
                    val mode = rawTokens.getOrNull(idx + 1)?.toIntOrNull()
                    if (mode == 5 && idx + 2 < rawTokens.size) {
                        idx += 2
                    } else if (mode == 2 && idx + 4 < rawTokens.size) {
                        idx += 4
                    }
                }
            }
            idx++
        }
        return SgrState(fg, bg, mod.toUShort())
    }

    private fun stripAnsiCodes(text: String): String =
        text.replace(CSI_REGEX, "")
            .replace(OSC_REGEX, "")

    private fun parseSurfaceRect(rectObj: JsonObject): SurfaceRect =
        SurfaceRect(
            x = (rectObj["x"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0).toUShort(),
            y = (rectObj["y"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0).toUShort(),
            width = (rectObj["width"]?.jsonPrimitive?.intOrNull ?: 80).coerceAtLeast(1).toUShort(),
            height = (rectObj["height"]?.jsonPrimitive?.intOrNull ?: 24).coerceAtLeast(1).toUShort(),
        )

    private fun parseAgentStatus(raw: String?): AgentStatus =
        when (raw?.trim()?.lowercase()) {
            "blocked" -> AgentStatus.Blocked
            "working", "running" -> AgentStatus.Working
            "done", "completed" -> AgentStatus.Done
            "idle" -> AgentStatus.Idle
            else -> AgentStatus.Unknown
        }

    companion object {
        private const val SOCKET_CONNECT_TIMEOUT_MS = 6_000

        /** The text of the question for a missing password. The UI shows its own text for this question. */
        private const val PASSWORD_QUESTION = "Password"
        private const val SERVER_ALIVE_INTERVAL_MS = 10_000
        private const val SERVER_ALIVE_COUNT_MAX = 3
        private const val CHANNEL_CONNECT_TIMEOUT_MS = 5_000

        /** The maximum length of a cause from the host in the debug log. See [recordMissingSnapshot]. */
        private const val MAX_LOGGED_CAUSE_LENGTH = 512
        private const val IPTOS_LOWDELAY = 0x10
        private const val LIVE_POLL_INTERVAL_MS = 400L

        /** The debug log tag of [recordTransport]. */
        internal const val TRANSPORT_LOG_TAG = "Transport"
        private const val LIVENESS_POLL_INTERVAL_MS = 10L
        private const val PTY_ECHO_SETTLE_DELAY_MS = 18L
        private const val OPTIMISTIC_ECHO_TTL_MS = 1_500L
        private const val MAX_OPTIMISTIC_EDITS = 128
        private const val PANE_SCROLL_ACCEPTED_RESPONSE = """{"id":"pane_scroll","result":{"type":"pane_scroll_queued"}}"""

        private val BLANK_CELL = CellData()
        private val RESET_SGR_STATE = SgrState(0u, 0u, 0u)
        private val ASCII_SYMBOLS = Array(128) { it.toChar().toString() }
        private val CSI_REGEX = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]")
        private val OSC_REGEX = Regex("\u001b\\][^\u0007]*\u0007")
    }
}
