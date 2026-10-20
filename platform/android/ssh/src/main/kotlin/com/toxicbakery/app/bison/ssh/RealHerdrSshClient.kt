package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.toxicbakery.app.bison.model.HostMachine
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
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Live SSH2 client (powered by JSch) that connects to unmodified upstream Herdr servers,
 * fetches real `herdr api snapshot` topology (workspaces, tabs, panes, coding agents, BSP layouts),
 * streams visible ANSI pane buffers via `herdr remote-api-bridge` (`pane.read`), and dispatches
 * real interactive keystrokes (`pane.send_text`, `pane.send_keys`, `pane.send_input`), prompts,
 * and workspace/tab/pane focus mutations.
 */
internal class RealHerdrSshClient(
    private val sshDebugLog: SshDebugLog,
    private val endpointSnapshotBus: EndpointSnapshotBus,
    private val paneSurfaceBus: PaneSurfaceBus,
    private val semanticNotificationBus: SemanticNotificationBus,
) {
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

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val pollJobs = ConcurrentHashMap<String, Job>()
    private val selectionState = ConcurrentHashMap<String, ClientSelectionState>()
    private val cachedInputs = ConcurrentHashMap<String, CachedSurfaceInputs>()
    private val viewportSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val revisionCounter = AtomicLong(10L)

    fun isSimulatedHost(machine: HostMachine): Boolean {
        val host = machine.hostname.trim().lowercase()
        val bastion = machine.proxyJump?.bastionHost?.trim()?.lowercase().orEmpty()
        if (host.isEmpty()) return true
        if (host.endsWith(".local") || host.endsWith(".example.com") || host.endsWith(".invalid")) return true
        if (host.endsWith(".ts.net") || host.startsWith("100.64.")) return true
        if (bastion.endsWith(".example.com") || bastion.endsWith(".invalid") || bastion.startsWith("100.64.")) return true
        return false
    }

    private fun mutexFor(machineId: String): Mutex =
        sessionLocks.computeIfAbsent(machineId) { Mutex() }

    suspend fun connectAndVerify(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Boolean = withContext(Dispatchers.IO) {
        mutexFor(machine.id).withLock {
            runCatching {
                val session = getOrOpenSessionLocked(machine, route)
                val out = execCommandOnSession(session, "herdr --version || true")
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "RealSSH",
                    message = "Connected to ${route.entryHost}:${route.entryPort} (${out.trim().ifBlank { "SSH ok" }})",
                )
                true
            }.getOrElse { err ->
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "RealSSH",
                    message = "SSH connection failed to ${route.entryHost}:${route.entryPort}: ${err.message}",
                    isError = true,
                )
                false
            }
        }
    }

    suspend fun checkMoshServerAvailable(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Boolean = withContext(Dispatchers.IO) {
        mutexFor(machine.id).withLock {
            runCatching {
                val session = getOrOpenSessionLocked(machine, route)
                val moshBin = machine.moshServerPath?.takeIf { it.isNotBlank() } ?: "mosh-server"
                val out = execCommandOnSession(session, "command -v $moshBin")
                out.trim().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    fun startLivePolling(
        machine: HostMachine,
        route: ResolvedSshRoute,
        isSurfaceActive: () -> Boolean,
    ) {
        pollJobs.remove(machine.id)?.cancel()
        val job = scope.launch {
            while (isActive) {
                if (isSurfaceActive()) {
                    runCatching {
                        refreshSnapshotAndSurface(machine, route)
                    }.onFailure { err ->
                        sshDebugLog.record(
                            machineId = machine.id,
                            tag = "RealSSH",
                            message = "Live poll warning: ${err.message}",
                            isError = true,
                        )
                    }
                }
                delay(400L)
            }
        }
        pollJobs[machine.id] = job
    }

    fun disconnect(machineId: String) {
        pollJobs.remove(machineId)?.cancel()
        selectionState.remove(machineId)
        cachedInputs.remove(machineId)
        viewportSizes.remove(machineId)
        sessions.remove(machineId)?.let { session ->
            runCatching { session.disconnect() }
        }
    }

    fun resizeViewport(
        machineId: String,
        cols: UShort,
        rows: UShort,
    ) {
        val targetCols = cols.toInt().coerceIn(20, 300)
        val targetRows = rows.toInt().coerceIn(8, 150)
        val prev = viewportSizes.put(machineId, targetCols to targetRows)
        if (prev == (targetCols to targetRows)) return
        val cached = cachedInputs[machineId] ?: return
        val frame = buildCompositeSurfaceFrame(
            machineId = machineId,
            snapshot = cached.snapshot,
            layout = cached.layout,
            ansiByPane = cached.ansiByPane,
        )
        paneSurfaceBus.publish(machineId, frame)
    }

    suspend fun refreshSnapshotAndSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Boolean = withContext(Dispatchers.IO) {
        val rawSnapshotJson = mutexFor(machine.id).withLock {
            val session = getOrOpenSessionLocked(machine, route)
            execCommandOnSession(session, "herdr api snapshot")
        }
        val rootObj = runCatching {
            val firstJsonLine = rawSnapshotJson.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("{") && it.endsWith("}") }
                ?: rawSnapshotJson.trim()
            json.parseToJsonElement(firstJsonLine).jsonObject
        }.getOrNull() ?: return@withContext false

        val snapshotObj = rootObj["result"]?.jsonObject?.get("snapshot")?.jsonObject
            ?: return@withContext false

        val sel = selectionState.computeIfAbsent(machine.id) { ClientSelectionState() }
        val parsed = parseSnapshotObject(machine.id, snapshotObj, sel)
        endpointSnapshotBus.publish(machine.id, parsed.snapshot)

        // Read visible ANSI output for all panes in the focused tab
        val activeTabId = parsed.snapshot.focusedTabId
        val activeLayout = parsed.layoutsByTab[activeTabId]
            ?: parsed.layoutsByTab.values.firstOrNull()
        val visiblePaneIds = activeLayout?.panes?.map { it.paneId }
            ?.takeIf { it.isNotEmpty() }
            ?: parsed.snapshot.panes
                .filter { activeTabId == null || it.tabId == activeTabId }
                .map { it.paneId }
                .ifEmpty { listOfNotNull(parsed.snapshot.focusedPaneId) }

        if (visiblePaneIds.isEmpty()) return@withContext true

        val ndjsonBatch = visiblePaneIds.joinToString("\n") { paneId ->
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
        }

        val batchResponse = mutexFor(machine.id).withLock {
            val session = getOrOpenSessionLocked(machine, route)
            execApiBridgeBatchLocked(session, ndjsonBatch)
        }

        val ansiByPane = mutableMapOf<String, String>()
        batchResponse.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val readObj = obj["result"]?.jsonObject?.get("read")?.jsonObject
                    val pId = readObj?.get("pane_id")?.jsonPrimitive?.content
                    val text = readObj?.get("text")?.jsonPrimitive?.content
                    if (!pId.isNullOrBlank() && text != null) {
                        ansiByPane[pId] = text
                    }
                }
            }

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
        paneSurfaceBus.publish(machine.id, frame)
        true
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

        // Track client selection overrides immediately so switching workspaces/tabs/panes is instant
        when (request.method) {
            "workspace.focus" -> {
                val paramsObj = parseParamsObject(request.paramsJson)
                val wsId = paramsObj["workspace_id"]?.jsonPrimitive?.content
                if (!wsId.isNullOrBlank()) {
                    sel.focusedWorkspaceId = wsId
                    val currentSnap = endpointSnapshotBus.snapshotFor(machine.id)
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
                }
            }

            "tab.focus" -> {
                val paramsObj = parseParamsObject(request.paramsJson)
                val tabId = paramsObj["tab_id"]?.jsonPrimitive?.content
                if (!tabId.isNullOrBlank()) {
                    sel.focusedTabId = tabId
                    val currentSnap = endpointSnapshotBus.snapshotFor(machine.id)
                    val tab = currentSnap?.tabs?.firstOrNull { it.tabId == tabId }
                    if (tab != null) {
                        sel.focusedWorkspaceId = tab.workspaceId
                    }
                    val targetPaneId = currentSnap?.panes?.firstOrNull { it.tabId == tabId }?.paneId
                    if (!targetPaneId.isNullOrBlank()) {
                        sel.focusedPaneId = targetPaneId
                    }
                }
            }

            "pane.focus", "agent.focus" -> {
                val paramsObj = parseParamsObject(request.paramsJson)
                val paneId = paramsObj["pane_id"]?.jsonPrimitive?.content
                if (!paneId.isNullOrBlank()) {
                    sel.focusedPaneId = paneId
                    val currentSnap = endpointSnapshotBus.snapshotFor(machine.id)
                    val pane = currentSnap?.panes?.firstOrNull { it.paneId == paneId }
                    if (pane != null) {
                        sel.focusedWorkspaceId = pane.workspaceId
                        sel.focusedTabId = pane.tabId
                    }
                }
            }
        }

        val ndjsonLine = HerdrRpcBuilder.toNdjsonLine(request).trim()
        val response = runCatching {
            mutexFor(machine.id).withLock {
                val session = getOrOpenSessionLocked(machine, route)
                execApiBridgeBatchLocked(session, ndjsonLine)
            }
        }.getOrElse { err ->
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "RPC ${request.method} error: ${err.message}",
                isError = true,
            )
            null
        }

        // Immediately refresh snapshot and visible pane ANSI buffer after any RPC
        runCatching { refreshSnapshotAndSurface(machine, route) }
        response
    }

    suspend fun sendPaneInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        val targetPaneId = paneId.takeIf { it.isNotBlank() }
            ?: selectionState[machine.id]?.focusedPaneId
            ?: endpointSnapshotBus.snapshotFor(machine.id)?.focusedPaneId
            ?: return@withContext false

        val rpcLine = when (event) {
            is ClientPaneInputEvent.TextCommit -> {
                buildJsonObject {
                    put("id", "in_${revisionCounter.incrementAndGet()}")
                    put("method", "pane.send_text")
                    put(
                        "params",
                        buildJsonObject {
                            put("pane_id", targetPaneId)
                            put("text", event.text)
                        },
                    )
                }.toString()
            }

            is ClientPaneInputEvent.Paste -> {
                buildJsonObject {
                    put("id", "paste_${revisionCounter.incrementAndGet()}")
                    put("method", "pane.send_text")
                    put(
                        "params",
                        buildJsonObject {
                            put("pane_id", targetPaneId)
                            put("text", event.text)
                        },
                    )
                }.toString()
            }

            is ClientPaneInputEvent.Key -> {
                val keySpec = mapKeyEventToHerdrKeySpec(event.event)
                buildJsonObject {
                    put("id", "key_${revisionCounter.incrementAndGet()}")
                    put("method", "pane.send_keys")
                    put(
                        "params",
                        buildJsonObject {
                            put("pane_id", targetPaneId)
                            put("keys", buildJsonArray { add(JsonPrimitive(keySpec)) })
                        },
                    )
                }.toString()
            }

            is ClientPaneInputEvent.Mouse -> return@withContext true
        }

        val ok = runCatching {
            mutexFor(machine.id).withLock {
                val session = getOrOpenSessionLocked(machine, route)
                execApiBridgeBatchLocked(session, rpcLine)
            }
            true
        }.getOrElse { err ->
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "Failed to send input to $targetPaneId: ${err.message}",
                isError = true,
            )
            false
        }

        if (ok) {
            runCatching { refreshSnapshotAndSurface(machine, route) }
        }
        ok
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

    private fun getOrOpenSessionLocked(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Session {
        val existing = sessions[machine.id]
        if (existing != null && existing.isConnected) {
            return existing
        }
        val jsch = JSch()
        val session = jsch.getSession(
            route.entryUsername.ifBlank { machine.username },
            route.entryHost,
            route.entryPort,
        )
        when (val auth = machine.authMethod) {
            is SshAuthMethod.Password -> {
                session.setPassword(auth.encryptedPasswordRef)
            }
            else -> {
                // Publickey or keyboard-interactive fallback
            }
        }
        val props = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "password,keyboard-interactive,publickey")
        }
        session.setConfig(props)
        session.connect(6_000)
        sessions[machine.id] = session
        return session
    }

    private fun execCommandOnSession(
        session: Session,
        command: String,
    ): String {
        val channel = session.openChannel("exec") as ChannelExec
        val fullCmd = "export PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:/usr/local/bin:\$PATH\"; $command"
        channel.setCommand(fullCmd)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        channel.outputStream = out
        channel.setErrStream(err)
        channel.connect(5_000)
        val deadline = System.currentTimeMillis() + 6_000L
        while (!channel.isClosed && System.currentTimeMillis() < deadline) {
            Thread.sleep(12L)
        }
        channel.disconnect()
        return out.toString(Charsets.UTF_8.name())
    }

    private fun execApiBridgeBatchLocked(
        session: Session,
        ndjsonPayload: String,
    ): String {
        val channel = session.openChannel("exec") as ChannelExec
        val fullCmd = "export PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:/usr/local/bin:\$PATH\"; herdr remote-api-bridge"
        channel.setCommand(fullCmd)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        channel.outputStream = out
        channel.setErrStream(err)
        val stdinBytes = (ndjsonPayload.trimEnd() + "\n").toByteArray(Charsets.UTF_8)
        channel.setInputStream(stdinBytes.inputStream())
        channel.connect(5_000)
        val deadline = System.currentTimeMillis() + 6_000L
        while (!channel.isClosed && System.currentTimeMillis() < deadline) {
            Thread.sleep(12L)
        }
        channel.disconnect()
        return out.toString(Charsets.UTF_8.name())
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
                scroll = PaneSurfaceScroll(
                    viewportRows = lp.rect.height,
                ),
            )
        }

        val splits = if (scaledLayout != null) deriveSplitsFromLayout(scaledLayout) else emptyList()

        return PaneSurfaceFrame(
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
        private val BLANK_CELL = CellData()
        private val RESET_SGR_STATE = SgrState(0u, 0u, 0u)
        private val ASCII_SYMBOLS = Array(128) { it.toChar().toString() }
        private val CSI_REGEX = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]")
        private val OSC_REGEX = Regex("\u001b\\][^\u0007]*\u0007")
    }
}
