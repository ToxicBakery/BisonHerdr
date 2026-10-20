package com.toxicbakery.app.bison.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Semantic coding agent status reported by Herdr's screen detector or agent hooks.
 *
 * Unknown values from newer servers automatically decode to [Unknown] for forward compatibility.
 */
@Serializable(with = AgentStatusSerializer::class)
enum class AgentStatus(
    val wireName: String,
    val priorityOrder: Int,
) {
    @SerialName("blocked")
    Blocked(wireName = "blocked", priorityOrder = 0),

    @SerialName("done")
    Done(wireName = "done", priorityOrder = 1),

    @SerialName("working")
    Working(wireName = "working", priorityOrder = 2),

    @SerialName("idle")
    Idle(wireName = "idle", priorityOrder = 3),

    @SerialName("unknown")
    Unknown(wireName = "unknown", priorityOrder = 4);

    val requiresAttention: Boolean
        get() = this == Blocked || this == Done

    companion object {
        fun fromWireName(value: String?): AgentStatus =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: Unknown
    }
}

object AgentStatusSerializer : KSerializer<AgentStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.toxicbakery.app.bison.protocol.AgentStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AgentStatus) {
        encoder.encodeString(value.wireName)
    }

    override fun deserialize(decoder: Decoder): AgentStatus =
        AgentStatus.fromWireName(decoder.decodeString())
}

/**
 * Client-Owned Shell state snapshot (`shell.snapshot.v1`).
 */
@Serializable
data class ClientShellSnapshot(
    @SerialName("boot_id") val bootId: String = "boot-default",
    val revision: ULong = 0u,
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val workspaces: List<ClientShellWorkspace> = emptyList(),
    val tabs: List<ClientShellTab> = emptyList(),
    val panes: List<ClientShellPane> = emptyList(),
    val agents: List<ClientShellAgent> = emptyList(),
    val commands: List<ClientShellCommand> = emptyList(),
    @SerialName("config_diagnostic") val configDiagnostic: String? = null,
    @SerialName("worktree_directory") val worktreeDirectory: String? = null,
    @SerialName("agent_view_label") val agentViewLabel: String? = null,
    @SerialName("agent_order") val agentOrder: List<String> = emptyList(),
) {
    /** Agents sorted by attention priority (`Blocked` -> `Done` -> `Working` -> `Idle` -> `Unknown`). */
    val prioritizedAgents: List<ClientShellAgent>
        get() = agents.sortedWith(AGENT_PRIORITY_COMPARATOR)

    companion object {
        private val AGENT_PRIORITY_COMPARATOR: Comparator<ClientShellAgent> =
            compareBy<ClientShellAgent> { it.agentStatus.priorityOrder }
                .thenByDescending { it.focused }
                .thenBy { it.paneId }
    }
}

@Serializable
data class ClientShellWorktreeInfo(
    val key: String = "",
    val label: String = "",
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean = false,
)

@Serializable
data class ClientShellWorkspace(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("active_tab_id") val activeTabId: String? = null,
    @SerialName("new_workspace_cwd") val newWorkspaceCwd: String = "",
    val number: Int = 1,
    val label: String,
    @SerialName("custom_label") val customLabel: Boolean = false,
    val branch: String? = null,
    @SerialName("git_ahead_behind") val gitAheadBehind: List<Int>? = null,
    val tokens: List<List<String>> = emptyList(),
    val worktree: ClientShellWorktreeInfo? = null,
    val focused: Boolean = false,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
) {
    val id: String get() = workspaceId
}

@Serializable
data class ClientShellTab(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 1,
    val label: String,
    @SerialName("custom_label") val customLabel: Boolean = false,
    val zoomed: Boolean = false,
    val focused: Boolean = false,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
) {
    val id: String get() = tabId
}

@Serializable
data class ClientShellPane(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val label: String = "",
    val cwd: String = "",
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    val focused: Boolean = false,
    @SerialName("right_click_passthrough") val rightClickPassthrough: Boolean = false,
) {
    val id: String get() = paneId
}

@Serializable
data class ClientShellAgent(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    val name: String? = null,
    @SerialName("display_agent") val displayAgent: String = "",
    val agent: String = "",
    val title: String? = null,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    @SerialName("state_change_seq") val stateChangeSeq: ULong = 0u,
    @SerialName("state_labels") val stateLabels: List<List<String>> = emptyList(),
    val tokens: List<List<String>> = emptyList(),
    val focused: Boolean = false,
)

@Serializable
data class ClientShellCommand(
    @SerialName("command_id") val commandId: String,
    @SerialName("binding_label") val bindingLabel: String? = null,
    @SerialName("binding_labels") val bindingLabels: List<String> = emptyList(),
    val action: String = "",
    val description: String = "",
)

/**
 * Semantic toast or agent attention notification emitted by Herdr's control stream.
 */
@Serializable
data class SemanticNotification(
    @SerialName("boot_id") val bootId: String = "boot-default",
    val sequence: ULong = 0u,
    val title: String,
    val body: String = "",
    @SerialName("pane_id") val paneId: String? = null,
    @SerialName("agent_name") val agentName: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus? = null,
)
