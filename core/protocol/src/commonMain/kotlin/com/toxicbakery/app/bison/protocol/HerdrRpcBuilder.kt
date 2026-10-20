package com.toxicbakery.app.bison.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Type-safe builders for Herdr JSON-RPC operations over both `ClientShellEndpointRequest`
 * (`herdr-client.sock`) and NDJSON API streams (`herdr.sock` / `herdr remote-api-bridge`).
 */
object HerdrRpcBuilder {

    private val compactJson = Json { encodeDefaults = true }

    fun clientShellSurfaceSet(
        active: Boolean,
        requestId: String = "surface_lease",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "client_shell.surface.set",
        params = buildJsonObject {
            put("active", active)
        },
    )

    fun workspaceFocus(
        workspaceId: String,
        requestId: String = "workspace_focus",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "workspace.focus",
        params = buildJsonObject {
            put("workspace_id", workspaceId)
        },
    )

    fun workspaceCreate(
        label: String? = null,
        cwd: String? = null,
        focus: Boolean = true,
        requestId: String = "workspace_create",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "workspace.create",
        params = buildJsonObject {
            label?.let { put("label", it) }
            cwd?.let { put("cwd", it) }
            put("focus", focus)
        },
    )

    fun worktreeCreate(
        branch: String,
        workspaceId: String? = null,
        focus: Boolean = true,
        requestId: String = "worktree_create",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "worktree.create",
        params = buildJsonObject {
            put("branch", branch)
            workspaceId?.let { put("workspace_id", it) }
            put("focus", focus)
        },
    )

    fun tabFocus(
        tabId: String,
        requestId: String = "tab_focus",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "tab.focus",
        params = buildJsonObject {
            put("tab_id", tabId)
        },
    )

    fun tabCreate(
        workspaceId: String? = null,
        label: String? = null,
        cwd: String? = null,
        focus: Boolean = true,
        requestId: String = "tab_create",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "tab.create",
        params = buildJsonObject {
            workspaceId?.let { put("workspace_id", it) }
            label?.let { put("label", it) }
            cwd?.let { put("cwd", it) }
            put("focus", focus)
        },
    )

    fun paneFocus(
        paneId: String,
        requestId: String = "pane_focus",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.focus",
        params = buildJsonObject {
            put("pane_id", paneId)
        },
    )

    fun paneSplit(
        paneId: String? = null,
        direction: String = "right",
        ratio: Float = 0.5f,
        focus: Boolean = true,
        requestId: String = "pane_split",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.split",
        params = buildJsonObject {
            paneId?.let { put("pane_id", it) }
            put("direction", direction)
            put("ratio", ratio)
            put("focus", focus)
        },
    )

    fun paneZoom(
        paneId: String? = null,
        mode: String = "toggle",
        requestId: String = "pane_zoom",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.zoom",
        params = buildJsonObject {
            paneId?.let { put("pane_id", it) }
            put("mode", mode)
        },
    )

    fun paneScroll(
        paneId: String,
        offsetFromBottom: Int,
        requestId: String = "pane_scroll",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.scroll",
        params = buildJsonObject {
            put("pane_id", paneId)
            put("offset_from_bottom", offsetFromBottom.coerceAtLeast(0))
        },
    )

    fun paneSendKeys(
        paneId: String,
        keys: List<String>,
        requestId: String = "pane_send_keys",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.send_keys",
        params = buildJsonObject {
            put("pane_id", paneId)
            put(
                "keys",
                buildJsonArray {
                    keys.forEach { add(JsonPrimitive(it)) }
                },
            )
        },
    )

    fun paneSendInput(
        paneId: String,
        text: String,
        keys: List<String> = listOf("Enter"),
        requestId: String = "pane_send_input",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.send_input",
        params = buildJsonObject {
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

    fun paneRead(
        paneId: String,
        source: String = "visible",
        requestId: String = "pane_read",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.read",
        params = buildJsonObject {
            put("pane_id", paneId)
            put("source", source)
        },
    )

    fun paneLayout(
        tabId: String? = null,
        requestId: String = "pane_layout",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.layout",
        params = buildJsonObject {
            tabId?.let { put("tab_id", it) }
        },
    )

    fun paneSelectionRead(
        paneId: String,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        contentRevision: ULong? = null,
        requestId: String = "pane_selection_read",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.selection.read",
        params = buildJsonObject {
            put("pane_id", paneId)
            put(
                "anchor",
                buildJsonObject {
                    put("row", startRow)
                    put("col", startCol)
                },
            )
            put(
                "cursor",
                buildJsonObject {
                    put("row", endRow)
                    put("col", endCol)
                },
            )
            contentRevision?.let { put("content_revision", it.toLong()) }
        },
    )

    fun paneCopySearch(
        paneId: String,
        query: String,
        requestId: String = "pane_copy_search",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "pane.copy_search",
        params = buildJsonObject {
            put("pane_id", paneId)
            put("query", query)
        },
    )

    fun layoutSetSplitRatio(
        path: List<Boolean>,
        ratio: Float,
        tabId: String? = null,
        requestId: String = "layout_set_split_ratio",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "layout.set_split_ratio",
        params = buildJsonObject {
            tabId?.let { put("tab_id", it) }
            put(
                "path",
                buildJsonArray {
                    path.forEach { add(JsonPrimitive(it)) }
                },
            )
            put("ratio", ratio.coerceIn(0.1f, 0.9f))
        },
    )

    fun agentFocus(
        paneId: String? = null,
        name: String? = null,
        requestId: String = "agent_focus",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "agent.focus",
        params = buildJsonObject {
            paneId?.let { put("pane_id", it) }
            name?.let { put("name", it) }
        },
    )

    fun agentSendKeys(
        paneId: String? = null,
        name: String? = null,
        keys: List<String>,
        requestId: String = "agent_send_keys",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "agent.send_keys",
        params = buildJsonObject {
            paneId?.let { put("pane_id", it) }
            name?.let { put("name", it) }
            put(
                "keys",
                buildJsonArray {
                    keys.forEach { add(JsonPrimitive(it)) }
                },
            )
        },
    )

    fun agentPrompt(
        prompt: String,
        paneId: String? = null,
        name: String? = null,
        until: AgentStatus? = null,
        timeoutMs: Long? = null,
        requestId: String = "agent_prompt",
    ): ClientShellEndpointRequest = buildRequest(
        id = requestId,
        method = "agent.prompt",
        params = buildJsonObject {
            put("prompt", prompt)
            paneId?.let { put("pane_id", it) }
            name?.let { put("name", it) }
            until?.let { put("until", it.wireName) }
            timeoutMs?.let { put("timeout_ms", it) }
        },
    )

    /**
     * Builds an `agent.read` request.
     *
     * Uses `source = "visible"` automatically when [agentStatus] is [AgentStatus.Working] or
     * [AgentStatus.Blocked] so alternate-screen coding agent TUIs never fail with `agent_not_idle`.
     */
    fun agentRead(
        paneId: String? = null,
        name: String? = null,
        agentStatus: AgentStatus = AgentStatus.Working,
        sourceOverride: String? = null,
        requestId: String = "agent_read",
    ): ClientShellEndpointRequest {
        val resolvedSource = sourceOverride ?: when (agentStatus) {
            AgentStatus.Working, AgentStatus.Blocked -> "visible"
            else -> "recent"
        }
        return buildRequest(
            id = requestId,
            method = "agent.read",
            params = buildJsonObject {
                paneId?.let { put("pane_id", it) }
                name?.let { put("name", it) }
                put("source", resolvedSource)
            },
        )
    }

    /**
     * Formats [request] as a single newline-delimited JSON line for `herdr.sock` (`herdr remote-api-bridge`).
     */
    fun toNdjsonLine(request: ClientShellEndpointRequest): String =
        """{"id":${compactJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(request.id))},"method":${compactJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(request.method))},"params":${request.paramsJson}}""" + "\n"

    private fun buildRequest(
        id: String,
        method: String,
        params: JsonObject,
    ): ClientShellEndpointRequest = ClientShellEndpointRequest(
        id = id,
        method = method,
        paramsJson = compactJson.encodeToString(JsonObject.serializer(), params),
    )
}
