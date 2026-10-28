package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.SemanticNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reactive bus holding the latest [ClientShellSnapshot] (`shell.snapshot.v1`) per machine.
 */
class EndpointSnapshotBus {
    private val _snapshots = MutableStateFlow<Map<String, ClientShellSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, ClientShellSnapshot>> = _snapshots.asStateFlow()

    fun publish(machineId: String, snapshot: ClientShellSnapshot) {
        _snapshots.update { it + (machineId to snapshot) }
    }

    fun snapshotFor(machineId: String): ClientShellSnapshot? =
        _snapshots.value[machineId]

    fun clear(machineId: String) {
        _snapshots.update { it - machineId }
    }
}

/**
 * Reactive bus holding the last committed [PaneSurfaceFrame] (`shell.surface.v1`) per machine.
 *
 * Retains the last committed frame during network roaming and background surface parking so the
 * tablet viewport never blanks out.
 */
class PaneSurfaceBus {
    private val _surfaces = MutableStateFlow<Map<String, PaneSurfaceFrame>>(emptyMap())
    val surfaces: StateFlow<Map<String, PaneSurfaceFrame>> = _surfaces.asStateFlow()

    fun publish(machineId: String, frame: PaneSurfaceFrame) {
        _surfaces.update { it + (machineId to frame) }
    }

    fun surfaceFor(machineId: String): PaneSurfaceFrame? =
        _surfaces.value[machineId]

    fun clear(machineId: String) {
        _surfaces.update { it - machineId }
    }
}

/**
 * Reactive bus for [SemanticNotification] events and live bottom-buffer agent snippets (`"$machineId:$paneId"`).
 *
 * The bus also keeps the agents that need the user now ([agentsNeedingUser]). An agent alert of a pane that is not in
 * this map is old: the agent left `Blocked` or `Done`, the agent stopped, or the machine disconnected.
 */
class SemanticNotificationBus {
    private val _notifications = MutableSharedFlow<Pair<String, SemanticNotification>>(extraBufferCapacity = 64)
    val notifications: SharedFlow<Pair<String, SemanticNotification>> = _notifications.asSharedFlow()

    private val _recentNotifications = MutableStateFlow<List<Pair<String, SemanticNotification>>>(emptyList())
    val recentNotifications: StateFlow<List<Pair<String, SemanticNotification>>> = _recentNotifications.asStateFlow()

    private val _agentSnippets = MutableStateFlow<Map<String, String>>(emptyMap())
    val agentSnippets: StateFlow<Map<String, String>> = _agentSnippets.asStateFlow()

    private val _agentsNeedingUser = MutableStateFlow<Map<String, Map<String, AgentStatus>>>(emptyMap())

    /**
     * The status of each agent that needs the user ([AgentStatus.requiresAttention]), by machine id and then by pane
     * id. A notification with such a status adds its pane. [setAgentsNeedingUser] replaces the panes of a machine.
     */
    val agentsNeedingUser: StateFlow<Map<String, Map<String, AgentStatus>>> = _agentsNeedingUser.asStateFlow()

    /**
     * Replaces the agents of [machineId] that need the user with [statuses]. An empty map removes the machine. The SSH
     * client calls it when its known agents change, and with an empty map at a disconnect.
     */
    fun setAgentsNeedingUser(machineId: String, statuses: Map<String, AgentStatus>) {
        val needing = statuses.filterValues { it.requiresAttention }
        _agentsNeedingUser.update { current ->
            if (needing.isEmpty()) current - machineId else current + (machineId to needing)
        }
    }

    /** Returns the status of the agent in [paneId] on [machineId] when it needs the user, or null. */
    fun statusNeedingUser(machineId: String, paneId: String): AgentStatus? =
        _agentsNeedingUser.value[machineId]?.get(paneId)

    suspend fun publishNotification(machineId: String, notification: SemanticNotification) {
        recordNotification(machineId, notification)
        _notifications.emit(machineId to notification)
    }

    fun tryPublishNotification(machineId: String, notification: SemanticNotification): Boolean {
        recordNotification(machineId, notification)
        return _notifications.tryEmit(machineId to notification)
    }

    fun publish(machineId: String, notification: SemanticNotification) {
        tryPublishNotification(machineId, notification)
    }

    fun updateAgentSnippet(machineId: String, paneId: String, snippet: String) {
        val key = snippetKey(machineId, paneId)
        _agentSnippets.update { it + (key to snippet) }
    }

    fun snippetFor(machineId: String, paneId: String): String? =
        _agentSnippets.value[snippetKey(machineId, paneId)]

    fun clearMachine(machineId: String) {
        _agentsNeedingUser.update { it - machineId }
        _recentNotifications.update { list -> list.filterNot { it.first == machineId } }
        _agentSnippets.update { map -> map.filterKeys { !it.startsWith("$machineId:") } }
    }

    private fun recordNotification(machineId: String, notification: SemanticNotification) {
        val paneId = notification.paneId
        val status = notification.agentStatus
        if (paneId != null && status != null && status.requiresAttention) {
            _agentsNeedingUser.update { current ->
                current + (machineId to (current[machineId].orEmpty() + (paneId to status)))
            }
        }
        _recentNotifications.update { current ->
            (listOf(machineId to notification) + current).take(MAX_RECENT_NOTIFICATIONS)
        }
    }

    companion object {
        const val MAX_RECENT_NOTIFICATIONS: Int = 64

        fun snippetKey(machineId: String, paneId: String): String = "$machineId:$paneId"
    }
}
