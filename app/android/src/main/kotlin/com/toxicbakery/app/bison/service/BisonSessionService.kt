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
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.SemanticNotification
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.agent_alert_action_failed_text
import com.toxicbakery.app.bison.resources.agent_alert_action_failed_title
import com.toxicbakery.app.bison.resources.agent_alert_action_no_answer
import com.toxicbakery.app.bison.resources.agent_alert_action_not_needed
import com.toxicbakery.app.bison.resources.agent_alert_action_not_connected
import com.toxicbakery.app.bison.resources.agent_alert_blocked_title
import com.toxicbakery.app.bison.resources.agent_alert_done_title
import com.toxicbakery.app.bison.resources.service_notification_action_disconnect_all
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.LocalNetworkAccess
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
 * Foreground service that keeps the SSH sessions open while the activity is in the background. It collects
 * [SshRequestManager.bus]. It gives each change of the default network to [SshTransportManager], which checks the
 * sessions and connects again when a check fails.
 *
 * The service posts an alert with actions for each `Blocked` or `Done` event of [SemanticNotificationBus]. The real
 * SSH client sends one event for each agent that goes to `Blocked` or `Done` on a real host, also while the app is
 * in the background (issue #53). The demo server also sends these events. The alert actions (Approve, Reject, and
 * Reply) go to the SSH control lane of the machine as Herdr requests ([AgentAlertAction]), one at a time. A request
 * that finds a closed session waits for one reconnect attempt. After a success, the service removes the alert. After a
 * failure, it replaces the alert with a notification that shows the cause. The service also removes an alert when its
 * agent leaves `Blocked` or `Done`, when the agent stops, and at a disconnect ([AgentAlertBook]). An action for such an
 * agent sends nothing.
 *
 * The service stops itself when [ServiceStopPolicy] finds no connected machine, no connection in
 * progress, and no started activity for 30 s. [MainActivity] starts the service again in `onStart`.
 */
class BisonSessionService : Service(), DIAware {

    override val di: DI by closestDI()

    private val debugServer: DebugServer by instance()
    private val localNetworkAccess: LocalNetworkAccess by instance()
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

    /** The actions of the agent alerts in the order of the taps. [collectAgentAlertActions] runs them one at a time. */
    private val agentAlertActions = Channel<AgentAlertWork>(Channel.UNLIMITED)

    /** The posted agent alerts. Only the main thread uses it. */
    private val agentAlerts = AgentAlertBook()

    /**
     * An [action] of the alert of the agent [agentLabel] on [machineId]. [sequence] is the sequence of the alert, or
     * null for an intent with no sequence.
     */
    private data class AgentAlertWork(
        val machineId: String,
        val agentLabel: String?,
        val sequence: Long?,
        val action: AgentAlertAction,
    )

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
        collectAgentAlertActions()
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
                        localNetworkAccess.cancelRequestBeforeQueue(request)
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

    /**
     * Posts an alert for each `Blocked` or `Done` event, and removes each alert that is old ([AgentAlertBook]). Both
     * collectors run on the main thread, the same thread as the actions.
     */
    private fun observeSemanticNotifications() {
        serviceScope.launch(Dispatchers.Main) {
            semanticNotificationBus.notifications.collect { (machineId, notification) ->
                val status = notification.agentStatus
                if (status == AgentStatus.Blocked || status == AgentStatus.Done) {
                    postAgentAlertNotification(machineId, notification)
                }
            }
        }
        serviceScope.launch(Dispatchers.Main) {
            semanticNotificationBus.agentsNeedingUser.collect { needing ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                agentAlerts.removeOld(needing).forEach { id -> manager?.cancel(id) }
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
        val agentLabel = intent.getStringExtra(EXTRA_AGENT_NAME)
        val sequence = intent.getLongExtra(EXTRA_ALERT_SEQUENCE, NO_SEQUENCE).takeIf { it != NO_SEQUENCE }

        val alertAction = when (action) {
            ACTION_AGENT_APPROVE -> AgentAlertAction.Approve(paneId)
            ACTION_AGENT_REJECT -> AgentAlertAction.Reject(paneId)
            ACTION_AGENT_INLINE_REPLY -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()
                if (replyText.isNullOrEmpty()) {
                    // The shade shows a progress mark after a reply until the alert changes. Remove the alert.
                    cancelAgentAlert(machineId, paneId, sequence)
                    return
                }
                AgentAlertAction.Reply(paneId, replyText)
            }

            else -> return
        }
        agentAlertActions.trySend(AgentAlertWork(machineId, agentLabel, sequence, alertAction))
    }

    /** Runs the actions of [agentAlertActions] one at a time, in the order of the taps, on the main thread. */
    private fun collectAgentAlertActions() {
        serviceScope.launch(Dispatchers.Main) {
            for (work in agentAlertActions) {
                runCatching { runAgentAlertAction(work) }.onFailure { error ->
                    rethrowIfCancelled(error)
                    log.e(error) { "Could not run the agent alert action $work" }
                }
            }
        }
    }

    /**
     * Sends the requests of [work] to the machine, and waits for the answers. After a success, it removes the alert.
     * After a failure, it replaces the alert with a notification that shows the cause.
     *
     * It sends nothing to a machine that is not connected, for example after a disconnect. It also sends nothing when
     * the agent does not need the user now ([SemanticNotificationBus.statusNeedingUser]). Then the pane can have a
     * different program, for example a shell, and the keys or the text must not go to it.
     */
    private suspend fun runAgentAlertAction(work: AgentAlertWork) {
        val paneId = work.action.paneId
        val refusal = when {
            !sshStateManager.isMachineConnected(work.machineId) ->
                getString(Res.string.agent_alert_action_not_connected)

            semanticNotificationBus.statusNeedingUser(work.machineId, paneId) == null ->
                getString(Res.string.agent_alert_action_not_needed)

            else -> null
        }
        if (refusal != null) {
            postAgentAlertFailure(work, refusal)
            return
        }
        val result = withContext(Dispatchers.IO) {
            work.action.perform { request -> sendAgentAlertRequest(work.machineId, request) }
        }
        when (result) {
            AgentAlertAction.Result.Sent -> cancelAgentAlert(work.machineId, paneId, work.sequence)
            is AgentAlertAction.Result.Failed ->
                postAgentAlertFailure(work, result.cause ?: getString(Res.string.agent_alert_action_no_answer))
        }
    }

    /** Sends [request] to the demo server or to the SSH transport, and returns the answer. */
    private suspend fun sendAgentAlertRequest(machineId: String, request: ClientShellEndpointRequest): String? {
        val demoAnswer = if (debugServer.isEnabled) {
            debugServer.handleEndpointCommand(machineId, request.method, request.paramsJson)
        } else {
            null
        }
        return demoAnswer ?: sshTransportManager.sendEndpointCommand(machineId, request)
    }

    /**
     * Removes the alert of [paneId] on [machineId] when the notification still shows the alert with [sequence]. A
     * newer alert of the pane stays.
     */
    private fun cancelAgentAlert(machineId: String, paneId: String, sequence: Long?) {
        val id = agentAlertNotificationId(machineId, paneId)
        if (!agentAlerts.isCurrent(id, sequence)) return
        agentAlerts.remove(id)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(id)
    }

    /**
     * Shows a notification that tells the user that the action of [work] failed, and why. It replaces the alert of
     * the action. When a newer alert of the pane exists, the newer alert stays, and the failure gets its own
     * notification.
     */
    private suspend fun postAgentAlertFailure(work: AgentAlertWork, cause: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val paneId = work.action.paneId
        val title = getString(Res.string.agent_alert_action_failed_title, work.agentLabel ?: paneId)
        val text = getString(Res.string.agent_alert_action_failed_text, machineLabel(work.machineId), cause)
        log.w { "The agent alert action ${work.action::class.simpleName} for $paneId failed: $cause" }
        val builder = NotificationCompat.Builder(this, CHANNEL_AGENT_ALERTS_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(launchAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        val alertId = agentAlertNotificationId(work.machineId, paneId)
        val replacesAlert = !agentAlerts.has(alertId) || agentAlerts.isCurrent(alertId, work.sequence)
        if (replacesAlert) agentAlerts.remove(alertId)
        val id = if (replacesAlert) alertId else alertId xor FAILURE_NOTIFICATION_ID_BIT
        runCatching {
            manager.notify(id, builder.build())
        }
    }

    /** Returns the name of [machineId] that the user gave, or the id when the store has no name. */
    private suspend fun machineLabel(machineId: String): String {
        machineStore.awaitLoaded()
        return machineStore.getMachine(machineId)?.name?.takeIf { it.isNotBlank() } ?: machineId
    }

    /** Returns an intent that opens the app. */
    private fun launchAppPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun disconnectAllMachines() {
        // A connect that waits for a user authentication, for keyboard-interactive answers, or for the local network
        // permission holds the control queue. End the wait first.
        sshStateManager.cancelAllUserAuth()
        localNetworkAccess.cancelAll()
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
        return if (hasMosh) "Mosh UDP" else "SSH"
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
        val contentPendingIntent = launchAppPendingIntent()
        val builder = NotificationCompat.Builder(this, CHANNEL_SESSION_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("BisonHerdr: $connectedCount machines ($transportSummary)")
            .setContentText("$activeAgentCount coding agents active")
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

    /**
     * Posts the alert of [notification]. The title tells the status, the machine, and the agent. The text is the body
     * of [notification], or the machine and the pane. A new alert of the same pane replaces the old alert. Each action
     * of the alert has the sequence of [notification]. When the agent does not need the user any more, the service
     * posts no alert.
     */
    private suspend fun postAgentAlertNotification(
        machineId: String,
        notification: SemanticNotification,
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val paneId = notification.paneId ?: "w1:p1"
        val agentName = notification.agentName?.takeIf { it.isNotBlank() } ?: notification.title
        val notificationId = agentAlertNotificationId(machineId, paneId)
        val machineLabel = machineLabel(machineId)
        val titleResource = if (notification.agentStatus == AgentStatus.Done) {
            Res.string.agent_alert_done_title
        } else {
            Res.string.agent_alert_blocked_title
        }
        val title = getString(titleResource, machineLabel, agentName)
        val sequence = notification.sequence.toLong()

        val approveIntent = Intent(this, BisonSessionService::class.java).apply {
            action = ACTION_AGENT_APPROVE
            putExtra(EXTRA_MACHINE_ID, machineId)
            putExtra(EXTRA_PANE_ID, paneId)
            putExtra(EXTRA_AGENT_NAME, agentName)
            putExtra(EXTRA_ALERT_SEQUENCE, sequence)
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
            putExtra(EXTRA_ALERT_SEQUENCE, sequence)
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
            putExtra(EXTRA_ALERT_SEQUENCE, sequence)
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
            .setContentTitle(title)
            .setContentText(notification.body.ifBlank { "$machineLabel · $paneId" })
            .setContentIntent(launchAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.checkbox_on_background, "Approve (Enter)", approvePendingIntent)
            .addAction(android.R.drawable.ic_delete, "Reject (Esc)", rejectPendingIntent)
            .addAction(replyAction)

        // No suspension occurs from this check to the record, so the collector of old alerts sees the alert.
        if (semanticNotificationBus.statusNeedingUser(machineId, paneId) == null) return
        runCatching {
            manager.notify(notificationId, builder.build())
        }
        agentAlerts.posted(notificationId, AgentAlertBook.Alert(machineId, paneId, sequence))
    }

    private fun ensureNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val sessionChannel = NotificationChannel(
            CHANNEL_SESSION_ID,
            "BisonHerdr Active Sessions",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent SSH terminal sessions"
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
        /** Returns the id of the alert of [paneId] on [machineId]. A new alert of a pane replaces its old alert. */
        private fun agentAlertNotificationId(machineId: String, paneId: String): Int =
            (machineId.hashCode() * 31 + paneId.hashCode()) and 0x7FFFFFFF

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
        const val EXTRA_ALERT_SEQUENCE: String = "extra_alert_sequence"

        /** The value of [EXTRA_ALERT_SEQUENCE] in an intent with no sequence. */
        private const val NO_SEQUENCE: Long = -1L

        /** A failure notification that must not replace a newer alert gets the alert id with this bit changed. */
        private const val FAILURE_NOTIFICATION_ID_BIT: Int = 0x40000000
        const val KEY_TEXT_REPLY: String = "key_text_reply"

        private val log = Arbor.tag("BisonSessionService")
    }
}
