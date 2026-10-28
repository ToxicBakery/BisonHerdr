package com.toxicbakery.app.bison.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Tests [AgentStatusTransitions] and [HerdrRpcBuilder.agentStatusSubscribe]. */
class AgentStatusTransitionsTest {

    private fun agent(paneId: String, status: AgentStatus, name: String? = null) = ClientShellAgent(
        paneId = paneId,
        workspaceId = "w1",
        name = name,
        displayAgent = "claude",
        agent = "claude",
        agentStatus = status,
    )

    /** Gives each snapshot of [snapshots] to [AgentStatusTransitions.fromSnapshot], and returns the alerts of each. */
    private fun alertsOf(vararg snapshots: List<ClientShellAgent>): List<List<String>> {
        var known: Map<String, ClientShellAgent>? = null
        return snapshots.map { agents ->
            val update = AgentStatusTransitions.fromSnapshot(known, agents)
            known = update.known
            update.alerts.map { "${it.paneId}=${it.agentStatus.wireName}" }
        }
    }

    @Test
    fun theFirstSnapshotGivesNoAlert() {
        val alerts = alertsOf(listOf(agent("w1:p1", AgentStatus.Blocked), agent("w1:p2", AgentStatus.Done)))
        assertEquals(listOf(emptyList()), alerts)
    }

    @Test
    fun aChangeToBlockedOrDoneGivesOneAlert() {
        val alerts = alertsOf(
            listOf(agent("w1:p1", AgentStatus.Working), agent("w1:p2", AgentStatus.Working)),
            listOf(agent("w1:p1", AgentStatus.Blocked), agent("w1:p2", AgentStatus.Done)),
        )
        assertEquals(listOf(emptyList(), listOf("w1:p1=blocked", "w1:p2=done")), alerts)
    }

    @Test
    fun theSameStatusInTheNextSnapshotGivesNoAlert() {
        val alerts = alertsOf(
            listOf(agent("w1:p1", AgentStatus.Working)),
            listOf(agent("w1:p1", AgentStatus.Blocked)),
            listOf(agent("w1:p1", AgentStatus.Blocked)),
            listOf(agent("w1:p1", AgentStatus.Blocked)),
        )
        assertEquals(listOf(emptyList(), listOf("w1:p1=blocked"), emptyList(), emptyList()), alerts)
    }

    @Test
    fun aChangeToAStatusThatDoesNotNeedTheUserGivesNoAlert() {
        val alerts = alertsOf(
            listOf(agent("w1:p1", AgentStatus.Blocked)),
            listOf(agent("w1:p1", AgentStatus.Working)),
            listOf(agent("w1:p1", AgentStatus.Idle)),
            listOf(agent("w1:p1", AgentStatus.Unknown)),
        )
        assertEquals(listOf(emptyList<String>(), emptyList(), emptyList(), emptyList()), alerts)
    }

    @Test
    fun eachNewTransitionGivesANewAlert() {
        val alerts = alertsOf(
            listOf(agent("w1:p1", AgentStatus.Working)),
            listOf(agent("w1:p1", AgentStatus.Blocked)),
            listOf(agent("w1:p1", AgentStatus.Working)),
            listOf(agent("w1:p1", AgentStatus.Blocked)),
            listOf(agent("w1:p1", AgentStatus.Done)),
        )
        assertEquals(
            listOf(emptyList(), listOf("w1:p1=blocked"), emptyList(), listOf("w1:p1=blocked"), listOf("w1:p1=done")),
            alerts,
        )
    }

    @Test
    fun aNewAgentAfterTheFirstSnapshotGetsAnAlertForAFirstStatusThatNeedsTheUser() {
        val working = agent("w1:p1", AgentStatus.Working)
        val blocked = agent("w1:p2", AgentStatus.Blocked)
        val alerts = alertsOf(
            listOf(working),
            listOf(working, blocked),
            listOf(working, blocked, agent("w1:p3", AgentStatus.Idle)),
        )
        assertEquals(listOf(emptyList(), listOf("w1:p2=blocked"), emptyList()), alerts)
    }

    @Test
    fun aPaneWithNoAgentIsRemovedAndAnAgentThatComesBackIsNew() {
        val first = AgentStatusTransitions.fromSnapshot(null, listOf(agent("w1:p1", AgentStatus.Blocked)))
        val gone = AgentStatusTransitions.fromSnapshot(first.known, emptyList())
        assertEquals(emptyMap(), gone.known)

        val back = AgentStatusTransitions.fromSnapshot(gone.known, listOf(agent("w1:p1", AgentStatus.Blocked)))
        assertEquals(listOf("w1:p1"), back.alerts.map { it.paneId })
    }

    @Test
    fun aStatusEventGivesOneAlertForEachTransition() {
        val known = AgentStatusTransitions.fromSnapshot(
            null,
            listOf(agent("w1:p1", AgentStatus.Working, name = "reviewer"), agent("w1:p2", AgentStatus.Idle)),
        ).known

        val blocked = AgentStatusTransitions.fromEvent(
            known,
            ClientShellAgent(
                paneId = "w1:p1",
                agent = "claude",
                agentStatus = AgentStatus.Blocked,
                title = "Fix the build",
            ),
        )
        val alert = blocked.alerts.single()
        assertEquals(AgentStatus.Blocked, alert.agentStatus)
        // The event has no name, so the alert keeps the name of the snapshot.
        assertEquals("reviewer", alert.name)
        assertEquals("Fix the build", alert.title)
        assertEquals(AgentStatus.Idle, blocked.known.getValue("w1:p2").agentStatus)

        val again = AgentStatusTransitions.fromEvent(
            blocked.known,
            ClientShellAgent(paneId = "w1:p1", agentStatus = AgentStatus.Blocked),
        )
        assertTrue(again.alerts.isEmpty())
        assertEquals("claude", again.known.getValue("w1:p1").agent)

        // A snapshot after the event knows the status of the event, so it gives no second alert.
        val snapshot = AgentStatusTransitions.fromSnapshot(
            again.known,
            listOf(agent("w1:p1", AgentStatus.Blocked), agent("w1:p2", AgentStatus.Idle)),
        )
        assertTrue(snapshot.alerts.isEmpty())
    }

    @Test
    fun aStatusEventOfAnUnknownPaneIsANewAgent() {
        val update = AgentStatusTransitions.fromEvent(
            emptyMap(),
            ClientShellAgent(paneId = "w2:p1", agent = "codex", agentStatus = AgentStatus.Done),
        )
        assertEquals(listOf("w2:p1"), update.alerts.map { it.paneId })
        assertEquals(setOf("w2:p1"), update.known.keys)
    }

    @Test
    fun theSubscribeRequestNamesEachPaneAndTheAgentDetectedEvent() {
        val request = HerdrRpcBuilder.agentStatusSubscribe(listOf("w1:p1", "w2:p3"), requestId = "watch")
        val line = Json.parseToJsonElement(HerdrRpcBuilder.toNdjsonLine(request)).jsonObject
        assertEquals("watch", line.getValue("id").jsonPrimitive.content)
        assertEquals("events.subscribe", line.getValue("method").jsonPrimitive.content)
        val subscriptions = line.getValue("params").jsonObject.getValue("subscriptions").jsonArray.map { element ->
            val obj = element.jsonObject
            listOfNotNull(obj.getValue("type").jsonPrimitive.content, obj["pane_id"]?.jsonPrimitive?.content)
                .joinToString(" ")
        }
        assertEquals(
            listOf(
                "pane.agent_detected",
                "pane.agent_status_changed w1:p1",
                "pane.agent_status_changed w2:p3",
            ),
            subscriptions,
        )
    }
}
