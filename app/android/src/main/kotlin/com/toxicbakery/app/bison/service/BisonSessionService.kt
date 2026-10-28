package com.toxicbakery.app.bison.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.toxicbakery.app.bison.activity.MainActivity
import com.toxicbakery.app.bison.activity.StartedActivityCounter
import com.toxicbakery.app.bison.debug.DebugServer
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientKeyEvent
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.SemanticNotification
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.service_notification_action_disconnect_all
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.ssh.SshTransportManager
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance

/**
 * Persistent foreground service keeping SSH/Mosh sessions alive across activity backgrounding,
 * supervising `ConnectivityManager.NetworkCallback` fast-roaming, collecting [SshRequestManager.bus],
 * and posting actionable notifications for `Blocked` and `Done` coding agents.
 *
 * The service stops itself when [ServiceStopPolicy] finds no connected machine, no connection in
 * progress, and no started activity for 30 s. [MainActivity] starts the service again in `onStart`.
 */
class BisonSessionService : Service(), DIAware {

    override val di: DI by closestDI()

    private val debugServer: DebugServer by instance()
    private val machineStore: MachineStore by instance()
    private val sshRequestManager: SshRequestManager by instance()
    private val sshStateManager: SshStateManager by instance()
    private val sshTransportManager: SshTransportManager by instance()
    private val endpointSnapshotBus: EndpointSnapshotBus by instance()
    private val semanticNotificationBus: SemanticNotificationBus by instance()
    private val startedActivityCounter: StartedActivityCounter by instance()
    private val json: Json by instance()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopPolicy = ServiceStopPolicy()
    private var networkCallbackRegistered = false

    // Only the main thread reads and writes these fields.
    private var stopPolicyJob: Job? = null
    private var lastStartId = 0
    private var lastActiveAgentCount = 0
    private var disconnectAllLabel: String? = null

    private val networkEvents = NetworkEventQueue()

    /**
     * Sends the changes of the default network to [networkEvents]. [collectNetworkEvents] gives them to
     * [sshTransportManager] one at a time. The manager checks each machine a maximum of one time each second, so the
     * two events after a change cause one check.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            log.d { "Default network available ($network); check the sessions" }
            networkEvents.send(NetworkEventQueue.Event.Available)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            log.d { "Network link properties changed (${linkProperties.interfaceName}); check the sessions" }
            networkEvents.send(NetworkEventQueue.Event.LinkPropertiesChanged)
        }

        override fun onLost(network: Network) {
            log.d { "Default network lost ($network); close the SSH sockets" }
            networkEvents.send(NetworkEventQueue.Event.Lost)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
        collectNetworkEvents()
        registerNetworkCallback()
        collectSshRequests()
        observeSessionStatusForForegroundNotification()
        observeSemanticNotifications()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Each startForegroundService call needs a startForeground call. This is also true for a
        // start that arrives after stopIdleService removed the foreground state.
        promoteToForeground(
            connectedCount = sshStateManager.connectedMachines.value.size,
            transportSummary = resolveTransportSummary(sshStateManager.activeTransportBackbone.value),
            activeAgentCount = lastActiveAgentCount,
        )
        handleNotificationActionIntent(intent)
        armStopPolicy()
        // A restart with no session has no work, so the system must not restart the service.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterNetworkCallback()
        serviceScope.cancel()
        // An update after stopForeground can post the session notification again as a normal
        // notification. The updates run on the main thread, so no update comes after this line.
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(FOREGROUND_NOTIFICATION_ID)
        super.onDestroy()
    }

    /**
     * Starts a new idle timer. Each start command starts a new timer, so a stop attempt that a new
     * start cancelled does not leave the service without a timer.
     */
    private fun armStopPolicy() {
        stopPolicyJob?.cancel()
        stopPolicyJob = serviceScope.launch(Dispatchers.Main) {
            stopPolicy.awaitIdleTimeout(
                connectedMachines = sshStateManager.connectedMachines,
                connectionProgress = sshStateManager.connectionProgress,
                anyActivityStarted = startedActivityCounter.anyStarted,
            )
            stopIdleService()
        }
    }

    private fun stopIdleService() {
        log.d { "The service is idle for the full timeout; BisonSessionService stops itself" }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // stopSelfResult does not stop the service when a newer start command exists. That start
        // command calls startForeground again and starts a new idle timer.
        if (!stopSelfResult(lastStartId)) {
            log.d { "A newer start command exists; BisonSessionService continues" }
        }
    }

    /** Gives the network events to [sshTransportManager], one at a time and in order. */
    private fun collectNetworkEvents() {
        serviceScope.launch {
            networkEvents.run(
                handle = { event ->
                    when (event) {
                        NetworkEventQueue.Event.Available,
                        NetworkEventQueue.Event.LinkPropertiesChanged,
                        -> sshTransportManager.onNetworkAvailable()

                        NetworkEventQueue.Event.Lost -> sshTransportManager.onNetworkLost()
                    }
                },
                onError = { event, error -> log.w(error) { "Could not handle the network event $event" } },
            )
        }
    }

    private fun collectSshRequests() {
        val fastInteractiveQueue = Channel<SshRequest>(Channel.UNLIMITED)
        val controlQueue = Channel<SshRequest>(Channel.UNLIMITED)

        serviceScope.launch {
            for (request in fastInteractiveQueue) {
                runCatching {
                    dispatchSshRequest(request)
                }.onFailure { error ->
                    rethrowIfCancelled(error)
                    log.e(error) { "Failed to handle fast interactive SshRequest: $request" }
                }
            }
        }

        serviceScope.launch {
            for (request in controlQueue) {
                // A failed or cancelled Connect must not stay at a connect step.
                sshStateManager.runControlRequest(request) { dispatchSshRequest(it) }?.let { error ->
                    log.e(error) { "Failed to handle control SshRequest: $request" }
                }
            }
        }

        serviceScope.launch {
            sshRequestManager.bus.collect { request ->
                when (request) {
                    is SshRequest.SendPaneInput,
                    is SshRequest.ResizeShell -> fastInteractiveQueue.trySend(request)
                    else -> {
                        sshStateManager.cancelUserAuthBeforeQueue(request)
                        controlQueue.trySend(request)
                    }
                }
            }
        }
    }

    private suspend fun dispatchSshRequest(request: SshRequest) {
        if (debugServer.isEnabled) {
            val handled = when (request) {
                is SshRequest.Connect -> debugServer.handleConnect(request.machineId)
                is SshRequest.Disconnect -> debugServer.handleDisconnect(request.machineId)
                is SshRequest.SetSurfaceLease -> debugServer.handleSetSurfaceLease(request.machineId, request.active)
                is SshRequest.SendEndpointCommand ->
                    debugServer.handleEndpointCommand(
                        machineId = request.machineId,
                        method = request.request.method,
                        paramsJson = request.request.paramsJson,
                    ) != null

                is SshRequest.SendPaneInput ->
                    debugServer.handlePaneInput(
                        machineId = request.machineId,
                        paneId = request.paneId,
                        inputJson = json.encodeToString(ClientPaneInputEvent.serializer(), request.event),
                    )

                is SshRequest.SubmitAgentPrompt -> {
                    val rpc = if (!request.agentName.isNullOrBlank()) {
                        HerdrRpcBuilder.agentPrompt(
                            prompt = request.prompt,
                            paneId = request.paneId,
                            name = request.agentName,
                        )
                    } else {
                        HerdrRpcBuilder.paneSendInput(
                            paneId = request.paneId,
                            text = request.prompt,
                            keys = listOf("Enter"),
                        )
                    }
                    debugServer.handleEndpointCommand(
                        machineId = request.machineId,
                        method = rpc.method,
                        paramsJson = rpc.paramsJson,
                    ) != null
                }

                is SshRequest.ResizeShell ->
                    debugServer.handleResizeShell(request.machineId, request.cols, request.rows)

                is SshRequest.TriggerNetworkRoam -> false
                is SshRequest.PromptHardwareKeyTouch -> false
            }
            if (handled) return
        }

        machineStore.awaitLoaded()
        val targetMachineId = when (request) {
            is SshRequest.Connect -> request.machineId
            is SshRequest.Disconnect -> request.machineId
            is SshRequest.SetSurfaceLease -> request.machineId
            is SshRequest.SendEndpointCommand -> request.machineId
            is SshRequest.SendPaneInput -> request.machineId
            is SshRequest.SubmitAgentPrompt -> request.machineId
            is SshRequest.ResizeShell -> request.machineId
            is SshRequest.TriggerNetworkRoam -> request.machineId
            is SshRequest.PromptHardwareKeyTouch -> request.machineId
        }
        val machine = targetMachineId?.let { machineStore.getMachine(it) }
        sshTransportManager.handleRequest(request, machine)
    }

    private fun observeSessionStatusForForegroundNotification() {
        // Collect on the main thread, the same thread as stopIdleService and onDestroy. A background
        // thread calculates the counts.
        serviceScope.launch(Dispatchers.Main) {
            disconnectAllLabel = runCatching {
                withContext(Dispatchers.IO) {
                    getString(Res.string.service_notification_action_disconnect_all)
                }
            }.onFailure { error ->
                rethrowIfCancelled(error)
                log.w(error) { "Could not load the Disconnect All label" }
            }.getOrNull()
            combine(
                sshStateManager.connectedMachines,
                sshStateManager.activeTransportBackbone,
                endpointSnapshotBus.snapshots,
            ) { connected, backbones, snapshots ->
                val activeAgents = snapshots.values.sumOf { snapshot ->
                    snapshot.agents.count { agent ->
                        agent.agentStatus == AgentStatus.Working || agent.agentStatus == AgentStatus.Blocked
                    }
                }
                Triple(connected.size, resolveTransportSummary(backbones), activeAgents)
            }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { (connectedCount, transportSummary, activeAgents) ->
                    lastActiveAgentCount = activeAgents
                    updateForegroundNotification(connectedCount, transportSummary, activeAgents)
                }
        }
    }

    private fun observeSemanticNotifications() {
        serviceScope.launch {
            semanticNotificationBus.notifications.collect { (machineId, notification) ->
                val status = notification.agentStatus
                if (status == AgentStatus.Blocked || status == AgentStatus.Done) {
                    postAgentAlertNotification(machineId, notification)
                }
            }
        }
    }

    private fun handleNotificationActionIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_DISCONNECT_ALL) {
            disconnectAllMachines()
            return
        }
        val machineId = intent.getStringExtra(EXTRA_MACHINE_ID) ?: return
        val paneId = intent.getStringExtra(EXTRA_PANE_ID) ?: return
        val agentName = intent.getStringExtra(EXTRA_AGENT_NAME)

        when (action) {
            ACTION_AGENT_APPROVE -> {
                serviceScope.launch {
                    sshRequestManager.emit(
                        SshRequest.SendPaneInput(
                            machineId = machineId,
                            paneId = paneId,
                            event = ClientPaneInputEvent.Key(
                                ClientKeyEvent(code = ClientKeyCode.Enter)
                            ),
                        )
                    )
                }
            }

            ACTION_AGENT_REJECT -> {
                serviceScope.launch {
                    sshRequestManager.emit(
                        SshRequest.SendPaneInput(
                            machineId = machineId,
                            paneId = paneId,
                            event = ClientPaneInputEvent.Key(
                                ClientKeyEvent(code = ClientKeyCode.Escape)
                            ),
                        )
                    )
                }
            }

            ACTION_AGENT_INLINE_REPLY -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()
                if (!replyText.isNullOrEmpty()) {
                    serviceScope.launch {
                        sshRequestManager.emit(
                            SshRequest.SubmitAgentPrompt(
                                machineId = machineId,
                                paneId = paneId,
                                agentName = agentName,
                                prompt = replyText,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun disconnectAllMachines() {
        // A connect that waits for a user authentication or for keyboard-interactive answers holds the control
        // queue. End the wait first.
        sshStateManager.cancelAllUserAuth()
        val machineIds = sshStateManager.connectedMachines.value
        log.d { "Disconnect All: send Disconnect for ${machineIds.size} machines" }
        serviceScope.launch {
            machineIds.forEach { machineId ->
                sshRequestManager.emit(SshRequest.Disconnect(machineId))
            }
        }
    }

    private fun resolveTransportSummary(backbones: Map<String, ActiveBackbone>): String {
        if (backbones.isEmpty()) return "Idle"
        val hasMosh = backbones.values.any { it is ActiveBackbone.MoshUdp }
        return if (hasMosh) "Mosh UDP" else "SSH Endpoint"
    }

    private fun promoteToForeground(
        connectedCount: Int,
        transportSummary: String,
        activeAgentCount: Int,
    ) {
        val notification = buildForegroundNotification(connectedCount, transportSummary, activeAgentCount)
        runCatching {
            // The manifest declares only the specialUse type. A type that the manifest does not
            // declare makes startForeground throw. API 33 and lower have no specialUse type.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
        }.onFailure { error ->
            log.w(error) { "Unable to promote BisonSessionService to foreground" }
        }
    }

    private fun updateForegroundNotification(
        connectedCount: Int,
        transportSummary: String,
        activeAgentCount: Int,
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = buildForegroundNotification(connectedCount, transportSummary, activeAgentCount)
        runCatching {
            manager.notify(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(
        connectedCount: Int,
        transportSummary: String,
        activeAgentCount: Int,
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_SESSION_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("BisonHerdr: $connectedCount machines ($transportSummary)")
            .setContentText("$activeAgentCount coding agents active · Zero-drop roaming armed")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        val disconnectAllLabel = disconnectAllLabel
        if (connectedCount > 0 && disconnectAllLabel != null) {
            val disconnectAllIntent = Intent(this, BisonSessionService::class.java).apply {
                action = ACTION_DISCONNECT_ALL
            }
            val disconnectAllPendingIntent = PendingIntent.getService(
                this,
                0,
                disconnectAllIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                disconnectAllLabel,
                disconnectAllPendingIntent,
            )
        }
        return builder.build()
    }

    private fun postAgentAlertNotification(
        machineId: String,
        notification: SemanticNotification,
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val paneId = notification.paneId ?: "w1:p1"
        val agentName = notification.agentName
        val notificationId = (machineId.hashCode() * 31 + paneId.hashCode()) and 0x7FFFFFFF

        val approveIntent = Intent(this, BisonSessionService::class.java).apply {
            action = ACTION_AGENT_APPROVE
            putExtra(EXTRA_MACHINE_ID, machineId)
            putExtra(EXTRA_PANE_ID, paneId)
            putExtra(EXTRA_AGENT_NAME, agentName)
        }
        val approvePendingIntent = PendingIntent.getService(
            this,
            notificationId + 1,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val rejectIntent = Intent(this, BisonSessionService::class.java).apply {
            action = ACTION_AGENT_REJECT
            putExtra(EXTRA_MACHINE_ID, machineId)
            putExtra(EXTRA_PANE_ID, paneId)
            putExtra(EXTRA_AGENT_NAME, agentName)
        }
        val rejectPendingIntent = PendingIntent.getService(
            this,
            notificationId + 2,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val replyIntent = Intent(this, BisonSessionService::class.java).apply {
            action = ACTION_AGENT_INLINE_REPLY
            putExtra(EXTRA_MACHINE_ID, machineId)
            putExtra(EXTRA_PANE_ID, paneId)
            putExtra(EXTRA_AGENT_NAME, agentName)
        }
        val replyPendingIntent = PendingIntent.getService(
            this,
            notificationId + 3,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = androidx.core.app.RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply with Prompt")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent,
        ).addRemoteInput(remoteInput).build()

        val builder = NotificationCompat.Builder(this, CHANNEL_AGENT_ALERTS_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(notification.title)
            .setContentText(notification.body.ifBlank { "$machineId · $paneId" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.checkbox_on_background, "Approve (Enter)", approvePendingIntent)
            .addAction(android.R.drawable.ic_delete, "Reject (Esc)", rejectPendingIntent)
            .addAction(replyAction)

        runCatching {
            manager.notify(notificationId, builder.build())
        }
    }

    private fun ensureNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val sessionChannel = NotificationChannel(
            CHANNEL_SESSION_ID,
            "BisonHerdr Active Sessions",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent SSH and Mosh UDP terminal sessions"
        }
        val agentAlertsChannel = NotificationChannel(
            CHANNEL_AGENT_ALERTS_ID,
            "Coding Agent Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Actionable alerts when coding agents become Blocked or Done"
        }
        manager.createNotificationChannel(sessionChannel)
        manager.createNotificationChannel(agentAlertsChannel)
    }

    private fun registerNetworkCallback() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { error ->
            log.w(error) { "Failed to register ConnectivityManager.NetworkCallback" }
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
    }

    companion object {
        const val CHANNEL_SESSION_ID: String = "bison_active_sessions"
        const val CHANNEL_AGENT_ALERTS_ID: String = "bison_agent_alerts"
        const val FOREGROUND_NOTIFICATION_ID: Int = 1001

        const val ACTION_AGENT_APPROVE: String = "com.toxicbakery.app.bison.action.AGENT_APPROVE"
        const val ACTION_AGENT_REJECT: String = "com.toxicbakery.app.bison.action.AGENT_REJECT"
        const val ACTION_AGENT_INLINE_REPLY: String = "com.toxicbakery.app.bison.action.AGENT_INLINE_REPLY"
        const val ACTION_DISCONNECT_ALL: String = "com.toxicbakery.app.bison.action.DISCONNECT_ALL"

        const val EXTRA_MACHINE_ID: String = "extra_machine_id"
        const val EXTRA_PANE_ID: String = "extra_pane_id"
        const val EXTRA_AGENT_NAME: String = "extra_agent_name"
        const val KEY_TEXT_REPLY: String = "key_text_reply"

        private val log = Arbor.tag("BisonSessionService")
    }
}
