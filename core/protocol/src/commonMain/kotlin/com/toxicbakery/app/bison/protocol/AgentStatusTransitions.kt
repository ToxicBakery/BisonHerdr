package com.toxicbakery.app.bison.protocol

/**
 * The result of [AgentStatusTransitions]: the agents that the client knows now, and the alerts of the step.
 */
data class AgentStatusUpdate(
    /** The last known agent of each pane, by pane id. The client keeps it for the next step. */
    val known: Map<String, ClientShellAgent>,
    /** The agents that went to a status that needs the user in this step, in the order of the input. */
    val alerts: List<ClientShellAgent>,
)

/**
 * Finds the status transitions of the coding agents of one machine (issue #53).
 *
 * The client keeps the last known agent of each pane. It gives each new snapshot to [fromSnapshot] and each status
 * event to [fromEvent], and keeps [AgentStatusUpdate.known] for the next step. An agent gets an alert only when its
 * status changes to a status that needs the user ([AgentStatus.requiresAttention]). Thus each transition gives one
 * alert. The same status in the next snapshot or event gives no alert.
 */
object AgentStatusTransitions {

    /**
     * Compares the [agents] of a new snapshot with the [known] agents.
     *
     * - When [known] is null, this snapshot is the first snapshot after a connect. It gives no alert, because the
     *   client does not know when the status started. The user sees the status in the app.
     * - An agent of a pane that is not in [known] is a new agent. It gets an alert when its first status needs the
     *   user.
     * - A pane that is not in [agents] has no agent now, so the result does not keep it.
     */
    fun fromSnapshot(known: Map<String, ClientShellAgent>?, agents: List<ClientShellAgent>): AgentStatusUpdate {
        val next = agents.associateBy { it.paneId }
        if (known == null) return AgentStatusUpdate(known = next, alerts = emptyList())
        val alerts = next.values.filter { agent -> isTransition(known[agent.paneId], agent.agentStatus) }
        return AgentStatusUpdate(known = next, alerts = alerts)
    }

    /**
     * Applies the status [event] of one pane to the [known] agents. The other panes keep their agents. A field that
     * the event does not have comes from the known agent of the pane, for example [ClientShellAgent.name].
     */
    fun fromEvent(known: Map<String, ClientShellAgent>, event: ClientShellAgent): AgentStatusUpdate {
        val previous = known[event.paneId]
        val merged = previous?.copy(
            agentStatus = event.agentStatus,
            agent = event.agent.ifBlank { previous.agent },
            displayAgent = event.displayAgent.ifBlank { previous.displayAgent },
            title = event.title ?: previous.title,
        ) ?: event
        val alerts = if (isTransition(previous, event.agentStatus)) listOf(merged) else emptyList()
        return AgentStatusUpdate(known = known + (event.paneId to merged), alerts = alerts)
    }

    /** Returns true when [status] needs the user, and [previous] had a different status or no status. */
    private fun isTransition(previous: ClientShellAgent?, status: AgentStatus): Boolean =
        status.requiresAttention && previous?.agentStatus != status
}
