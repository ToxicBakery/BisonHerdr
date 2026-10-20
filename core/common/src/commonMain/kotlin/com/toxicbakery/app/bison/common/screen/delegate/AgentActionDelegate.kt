package com.toxicbakery.app.bison.common.screen.delegate

import androidx.compose.runtime.Immutable
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientKeyEvent
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.SemanticNotification
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Federated coding agent entry aggregated across all connected machines and sorted by priority
 * (`Blocked` -> `Done` -> `Working` -> `Idle` -> `Unknown`).
 */
@Immutable
data class FederatedAgentItem(
    val machineId: String,
    val machineName: String,
    val agent: ClientShellAgent,
    val snippet: String? = null,
) {
    val displayTitle: String
        get() = agent.displayAgent.ifBlank {
            agent.name ?: agent.agent.ifBlank { "Agent (${agent.paneId})" }
        }
}

interface AgentActionDelegate {
    val agentSnippets: StateFlow<Map<String, String>>
    val recentNotifications: StateFlow<List<Pair<String, SemanticNotification>>>

    fun aggregateFederatedAgents(
        snapshots: Map<String, ClientShellSnapshot>,
        machineNames: Map<String, String> = emptyMap(),
        snippets: Map<String, String> = agentSnippets.value,
    ): List<FederatedAgentItem>

    suspend fun approveAgent(machineId: String, paneId: String, agentName: String? = null)
    suspend fun rejectAgent(machineId: String, paneId: String, agentName: String? = null)
    suspend fun focusAgentPane(machineId: String, paneId: String, agentName: String? = null)
    suspend fun requestAgentSnippet(
        machineId: String,
        paneId: String,
        agentName: String? = null,
        agentStatus: AgentStatus = AgentStatus.Working,
    )
    suspend fun submitPrompt(
        machineId: String,
        paneId: String,
        agentName: String?,
        prompt: String,
    )
    suspend fun sendKeyEvent(machineId: String, paneId: String, keyEvent: ClientKeyEvent)
    suspend fun sendKeyChord(machineId: String, paneId: String, chord: String)
    suspend fun sendLiteralText(machineId: String, paneId: String, text: String)
}

class AgentActionDelegateImpl(
    private val sshRequestManager: SshRequestManager,
    private val semanticNotificationBus: SemanticNotificationBus,
    @Suppress("unused")
    private val endpointSnapshotBus: EndpointSnapshotBus? = null,
) : AgentActionDelegate {

    override val agentSnippets: StateFlow<Map<String, String>> = semanticNotificationBus.agentSnippets
    override val recentNotifications: StateFlow<List<Pair<String, SemanticNotification>>> =
        semanticNotificationBus.recentNotifications

    override fun aggregateFederatedAgents(
        snapshots: Map<String, ClientShellSnapshot>,
        machineNames: Map<String, String>,
        snippets: Map<String, String>,
    ): List<FederatedAgentItem> = snapshots
        .flatMap { (machineId, snapshot) ->
            val hostLabel = machineNames[machineId] ?: machineId
            snapshot.agents.map { agent ->
                val key = SemanticNotificationBus.snippetKey(machineId, agent.paneId)
                val fallbackSnippet = agent.title ?: agent.terminalTitleStripped ?: agent.terminalTitle
                FederatedAgentItem(
                    machineId = machineId,
                    machineName = hostLabel,
                    agent = agent,
                    snippet = snippets[key] ?: fallbackSnippet,
                )
            }
        }
        .sortedWith(
            compareBy<FederatedAgentItem> { it.agent.agentStatus.priorityOrder }
                .thenByDescending { it.agent.focused }
                .thenBy { it.machineId }
                .thenBy { it.agent.paneId }
        )

    override suspend fun approveAgent(machineId: String, paneId: String, agentName: String?) {
        sshRequestManager.emit(
            SshRequest.SendPaneInput(
                machineId = machineId,
                paneId = paneId,
                event = ClientPaneInputEvent.Key(ClientKeyEvent(code = ClientKeyCode.Enter)),
            )
        )
        sshRequestManager.emit(
            SshRequest.SendEndpointCommand(
                machineId = machineId,
                request = HerdrRpcBuilder.agentSendKeys(
                    paneId = paneId,
                    name = agentName,
                    keys = listOf("Enter"),
                ),
            )
        )
    }

    override suspend fun rejectAgent(machineId: String, paneId: String, agentName: String?) {
        sshRequestManager.emit(
            SshRequest.SendPaneInput(
                machineId = machineId,
                paneId = paneId,
                event = ClientPaneInputEvent.Key(ClientKeyEvent(code = ClientKeyCode.Escape)),
            )
        )
        sshRequestManager.emit(
            SshRequest.SendEndpointCommand(
                machineId = machineId,
                request = HerdrRpcBuilder.agentSendKeys(
                    paneId = paneId,
                    name = agentName,
                    keys = listOf("Esc"),
                ),
            )
        )
    }

    override suspend fun focusAgentPane(machineId: String, paneId: String, agentName: String?) {
        sshRequestManager.emit(
            SshRequest.SendEndpointCommand(
                machineId = machineId,
                request = HerdrRpcBuilder.agentFocus(paneId = paneId, name = agentName),
            )
        )
    }

    override suspend fun requestAgentSnippet(
        machineId: String,
        paneId: String,
        agentName: String?,
        agentStatus: AgentStatus,
    ) {
        sshRequestManager.emit(
            SshRequest.SendEndpointCommand(
                machineId = machineId,
                request = HerdrRpcBuilder.agentRead(
                    paneId = paneId,
                    name = agentName,
                    agentStatus = agentStatus,
                ),
            )
        )
    }

    override suspend fun submitPrompt(
        machineId: String,
        paneId: String,
        agentName: String?,
        prompt: String,
    ) {
        if (prompt.isBlank()) return
        sshRequestManager.emit(
            SshRequest.SubmitAgentPrompt(
                machineId = machineId,
                paneId = paneId,
                agentName = agentName,
                prompt = prompt,
            )
        )
    }

    override suspend fun sendKeyEvent(machineId: String, paneId: String, keyEvent: ClientKeyEvent) {
        sshRequestManager.emit(
            SshRequest.SendPaneInput(
                machineId = machineId,
                paneId = paneId,
                event = ClientPaneInputEvent.Key(keyEvent),
            )
        )
    }

    override suspend fun sendKeyChord(machineId: String, paneId: String, chord: String) {
        sshRequestManager.emit(
            SshRequest.SendEndpointCommand(
                machineId = machineId,
                request = HerdrRpcBuilder.paneSendKeys(paneId = paneId, keys = listOf(chord)),
            )
        )
    }

    override suspend fun sendLiteralText(machineId: String, paneId: String, text: String) {
        sshRequestManager.emit(
            SshRequest.SendPaneInput(
                machineId = machineId,
                paneId = paneId,
                event = ClientPaneInputEvent.TextCommit(text),
            )
        )
    }
}
