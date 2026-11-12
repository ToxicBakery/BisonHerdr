package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.protocol.AgentStatus

/**
 * Keeps the agent alerts that [BisonSessionService] posted, by notification id, and tells when an alert is old
 * (issue #53). Only the main thread uses it.
 *
 * An alert is old when its pane is not in the agents that need the user
 * ([com.toxicbakery.app.bison.ssh.SemanticNotificationBus.agentsNeedingUser]): the agent left `Blocked` or `Done`, the
 * agent stopped, or the machine disconnected. Each alert has the sequence number of its notification. An action
 * removes only the alert with the sequence of the action, so that it cannot remove a newer alert of the same pane.
 */
internal class AgentAlertBook {

    /** One posted alert. */
    data class Alert(val machineId: String, val paneId: String, val sequence: Long)

    private val alerts = HashMap<Int, Alert>()

    /** Records that the notification [id] shows [alert]. It replaces an older alert of the same id. */
    fun posted(id: Int, alert: Alert) {
        alerts[id] = alert
    }

    /** Returns true when the notification [id] shows an alert. */
    fun has(id: Int): Boolean = id in alerts

    /** Returns true when the notification [id] shows the alert with [sequence]. A null [sequence] is never current. */
    fun isCurrent(id: Int, sequence: Long?): Boolean = sequence != null && alerts[id]?.sequence == sequence

    /** Forgets the alert of the notification [id]. */
    fun remove(id: Int) {
        alerts.remove(id)
    }

    /**
     * Forgets each alert whose pane is not in [needing] (by machine id and then by pane id), and returns the ids of
     * these alerts, so that the service removes their notifications.
     */
    fun removeOld(needing: Map<String, Map<String, AgentStatus>>): List<Int> {
        val old = alerts.filterValues { alert -> needing[alert.machineId]?.containsKey(alert.paneId) != true }.keys
        old.forEach(alerts::remove)
        return old.toList()
    }
}
