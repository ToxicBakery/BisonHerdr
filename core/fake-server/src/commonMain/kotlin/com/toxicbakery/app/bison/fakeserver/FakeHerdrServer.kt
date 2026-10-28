package com.toxicbakery.app.bison.fakeserver

import com.toxicbakery.app.bison.debug.DebugServer
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import com.toxicbakery.app.bison.protocol.ClientShellCommand
import com.toxicbakery.app.bison.protocol.ClientShellPane
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.protocol.ClientShellTab
import com.toxicbakery.app.bison.protocol.ClientShellWorkspace
import com.toxicbakery.app.bison.protocol.ClientShellWorktreeInfo
import com.toxicbakery.app.bison.protocol.CursorShape
import com.toxicbakery.app.bison.protocol.CursorState
import com.toxicbakery.app.bison.protocol.FrameData
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.PaneSurfacePane
import com.toxicbakery.app.bison.protocol.PaneSurfaceScroll
import com.toxicbakery.app.bison.protocol.PaneSurfaceSplit
import com.toxicbakery.app.bison.protocol.PaneSurfaceSplitDirection
import com.toxicbakery.app.bison.protocol.SemanticNotification
import com.toxicbakery.app.bison.protocol.SurfaceRect
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.PaneSurfaceBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
import com.toxicbakery.app.bison.ssh.SshDebugLog
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.store.MachineStore
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Simulated multi-machine Herdr server for debug / demo builds (`DemoActivity`).
 *
 * Seeds two realistic machines (`workbox` over Mosh UDP `:60004` at `11ms` RTT with local predictive echo,
 * and `gpu-rig` over SSH Endpoint Gen 1 at `28ms` RTT), multiple workspaces & Git worktrees, tabs,
 * a 3-pane BSP split terminal layout with TrueColor cells matching the Zen Canvas specification,
 * and federated coding agents (`Claude Code` in `Blocked` state, `Codex CLI` in `Working` state,
 * and `Gemini CLI` in `Done` state).
 */
class FakeHerdrServer(
    private val machineStore: MachineStore? = null,
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
    private val endpointSnapshotBus: EndpointSnapshotBus,
    private val paneSurfaceBus: PaneSurfaceBus,
    private val semanticNotificationBus: SemanticNotificationBus,
) : DebugServer {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var enabled: Boolean = false

    override val isEnabled: Boolean
        get() = enabled

    private data class SimulatedMachineState(
        val machine: HostMachine,
        val backbone: ActiveBackbone,
        var cols: UShort = DEFAULT_COLS,
        var rows: UShort = DEFAULT_ROWS,
        var rootSplitRatio: Float = 0.56f,
        var rightSplitRatio: Float = 0.52f,
        var claudePromptInput: String = "",
        var claudeApprovalNote: String? = null,
        val paneExtraLines: MutableMap<String, MutableList<String>> = mutableMapOf(),
        var snapshot: ClientShellSnapshot,
    )

    private val machineStates = mutableMapOf<String, SimulatedMachineState>()

    /** The ids of the machines that this server made. The seeded demo machines are always in the set. */
    @Volatile
    private var fakeMachineIds: Set<String> = setOf(WORKBOX_ID, GPU_RIG_ID)

    /**
     * Returns true when this server made the machine with [machineId]: a seeded demo machine, or an
     * ad hoc machine that has no entry in the machine store. The host name has no effect.
     */
    fun isFakeMachine(machineId: String): Boolean = machineId in fakeMachineIds

    override fun enable() {
        if (enabled && machineStates.isNotEmpty()) return
        enabled = true
        seedDemoFleet()
    }

    private fun seedDemoFleet() {
        val workboxMachine = HostMachine(
            id = WORKBOX_ID,
            name = "workbox",
            hostname = "100.64.0.12",
            port = 22,
            username = "ian",
            authMethod = SshAuthMethod.KeystoreKey(
                keyAlias = "bison_default_ed25519",
                algorithm = KeyAlgorithm.Ed25519,
            ),
            transportMode = TransportMode.AutoPreferMosh,
            moshUdpPortRange = "60000:61000",
            herdrSessionName = "herdr-core",
            themeOverrideId = "gruvbox",
            fontSizeSp = 13f,
        )
        val gpuRigMachine = HostMachine(
            id = GPU_RIG_ID,
            name = "gpu-rig",
            hostname = "gpu-rig.tailnet.ts.net",
            port = 22,
            username = "ian",
            authMethod = SshAuthMethod.KeystoreKey(
                keyAlias = "bison_default_ed25519",
                algorithm = KeyAlgorithm.Ed25519,
            ),
            transportMode = TransportMode.AutoPreferMosh,
            moshUdpPortRange = "60000:61000",
            herdrSessionName = "cuda-kernels",
            themeOverrideId = "tokyo-night",
            fontSizeSp = 13f,
        )

        machineStore?.update { existing ->
            val seededIds = setOf(WORKBOX_ID, GPU_RIG_ID)
            listOf(workboxMachine, gpuRigMachine) + existing.filterNot { it.id in seededIds }
        }

        val workboxSnapshot = ClientShellSnapshot(
            bootId = "boot-workbox-demo",
            revision = 1u,
            focusedWorkspaceId = "w1",
            focusedTabId = "w1:t1",
            focusedPaneId = "w1:p1",
            worktreeDirectory = "/home/ian/workspace/worktrees",
            workspaces = listOf(
                ClientShellWorkspace(
                    workspaceId = "w1",
                    activeTabId = "w1:t1",
                    newWorkspaceCwd = "/home/ian/workspace/herdr-tablet",
                    number = 1,
                    label = "herdr-core",
                    branch = "main",
                    gitAheadBehind = listOf(2, 0),
                    tokens = listOf(listOf("model", "claude-opus-4")),
                    focused = true,
                    agentStatus = AgentStatus.Blocked,
                ),
                ClientShellWorkspace(
                    workspaceId = "w2",
                    activeTabId = "w2:t1",
                    newWorkspaceCwd = "/home/ian/workspace/herdr-tablet-mosh",
                    number = 2,
                    label = "wt/mosh-ssp",
                    branch = "feat/mosh-udp-ssp",
                    gitAheadBehind = listOf(1, 0),
                    worktree = ClientShellWorktreeInfo(
                        key = "herdr-tablet/feat/mosh-udp-ssp",
                        label = "wt/mosh-ssp",
                        isLinkedWorktree = true,
                    ),
                    focused = false,
                    agentStatus = AgentStatus.Done,
                ),
                ClientShellWorkspace(
                    workspaceId = "w3",
                    activeTabId = "w3:t1",
                    newWorkspaceCwd = "/home/ian/workspace/herdr-tablet/rust/ghostty-vt",
                    number = 3,
                    label = "ghostty-jni",
                    branch = "feat/ghostty-vt",
                    focused = false,
                    agentStatus = AgentStatus.Idle,
                ),
            ),
            tabs = listOf(
                ClientShellTab(
                    tabId = "w1:t1",
                    workspaceId = "w1",
                    number = 1,
                    label = "1:claude-mosh",
                    zoomed = false,
                    focused = true,
                    agentStatus = AgentStatus.Blocked,
                ),
                ClientShellTab(
                    tabId = "w1:t2",
                    workspaceId = "w1",
                    number = 2,
                    label = "2:gradle-test",
                    zoomed = false,
                    focused = false,
                    agentStatus = AgentStatus.Idle,
                ),
                ClientShellTab(
                    tabId = "w2:t1",
                    workspaceId = "w2",
                    number = 1,
                    label = "1:ssp-crypto",
                    zoomed = false,
                    focused = false,
                    agentStatus = AgentStatus.Done,
                ),
                ClientShellTab(
                    tabId = "w3:t1",
                    workspaceId = "w3",
                    number = 1,
                    label = "1:zig-ndk",
                    zoomed = false,
                    focused = false,
                    agentStatus = AgentStatus.Idle,
                ),
            ),
            panes = listOf(
                ClientShellPane(
                    paneId = "w1:p1",
                    workspaceId = "w1",
                    tabId = "w1:t1",
                    label = "Claude Code · Pane #1",
                    cwd = "/home/ian/workspace/herdr-tablet",
                    foregroundCwd = "/home/ian/workspace/herdr-tablet",
                    focused = true,
                ),
                ClientShellPane(
                    paneId = "w1:p2",
                    workspaceId = "w1",
                    tabId = "w1:t1",
                    label = "Mosh UDP · Pane #2",
                    cwd = "/home/ian/workspace/herdr-tablet/rust/herdr-mobile-core",
                    foregroundCwd = "/home/ian/workspace/herdr-tablet/rust/herdr-mobile-core",
                    focused = false,
                ),
                ClientShellPane(
                    paneId = "w1:p3",
                    workspaceId = "w1",
                    tabId = "w1:t1",
                    label = "Gemini CLI · Pane #3",
                    cwd = "/home/ian/workspace/herdr-tablet/core/common",
                    foregroundCwd = "/home/ian/workspace/herdr-tablet/core/common",
                    focused = false,
                ),
                ClientShellPane(
                    paneId = "w1:p4",
                    workspaceId = "w1",
                    tabId = "w1:t2",
                    label = "Gradle Runner",
                    cwd = "/home/ian/workspace/herdr-tablet",
                    foregroundCwd = "/home/ian/workspace/herdr-tablet",
                    focused = false,
                ),
            ),
            agents = listOf(
                ClientShellAgent(
                    paneId = "w1:p1",
                    workspaceId = "w1",
                    tabId = "w1:t1",
                    name = "claude",
                    displayAgent = "Claude Code",
                    agent = "claude",
                    title = "Allow Bash: ./gradlew :app:android:assembleDebug?",
                    terminalTitle = "Claude Code — herdr-tablet",
                    terminalTitleStripped = "Claude Code — herdr-tablet",
                    agentStatus = AgentStatus.Blocked,
                    stateChangeSeq = 14u,
                    stateLabels = listOf(listOf("blocked", "permission_prompt")),
                    tokens = listOf(listOf("tool", "Bash"), listOf("branch", "main")),
                    focused = true,
                ),
                ClientShellAgent(
                    paneId = "w1:p3",
                    workspaceId = "w1",
                    tabId = "w1:t1",
                    name = "gemini",
                    displayAgent = "Gemini CLI",
                    agent = "gemini",
                    title = "Verified 19 Herdr color themes (Gruvbox Dark default)",
                    terminalTitle = "Gemini CLI — core/common",
                    terminalTitleStripped = "Gemini CLI — core/common",
                    agentStatus = AgentStatus.Done,
                    stateChangeSeq = 11u,
                    stateLabels = listOf(listOf("done", "completed")),
                    tokens = listOf(listOf("theme", "gruvbox")),
                    focused = false,
                ),
            ),
            commands = listOf(
                ClientShellCommand(
                    commandId = "split_right",
                    bindingLabel = "prefix+v",
                    bindingLabels = listOf("prefix+v"),
                    action = "SplitRight",
                    description = "Split active pane vertically to the right",
                ),
                ClientShellCommand(
                    commandId = "toggle_zoom",
                    bindingLabel = "prefix+z",
                    bindingLabels = listOf("prefix+z"),
                    action = "ToggleZoom",
                    description = "Toggle single-pane fullscreen zoom",
                ),
            ),
        )

        val gpuRigSnapshot = ClientShellSnapshot(
            bootId = "boot-gpurig-demo",
            revision = 1u,
            focusedWorkspaceId = "w1",
            focusedTabId = "w1:t1",
            focusedPaneId = "w1:p2",
            workspaces = listOf(
                ClientShellWorkspace(
                    workspaceId = "w1",
                    activeTabId = "w1:t1",
                    newWorkspaceCwd = "/home/ian/ghostty-vt-bench",
                    number = 1,
                    label = "ghostty-simd",
                    branch = "main",
                    focused = true,
                    agentStatus = AgentStatus.Working,
                ),
            ),
            tabs = listOf(
                ClientShellTab(
                    tabId = "w1:t1",
                    workspaceId = "w1",
                    number = 1,
                    label = "1:cargo-ndk",
                    zoomed = false,
                    focused = true,
                    agentStatus = AgentStatus.Working,
                ),
            ),
            panes = listOf(
                ClientShellPane(
                    paneId = "w1:p2",
                    workspaceId = "w1",
                    tabId = "w1:t1",
                    label = "Codex CLI · Pane #2",
                    cwd = "/home/ian/ghostty-vt-bench",
                    foregroundCwd = "/home/ian/ghostty-vt-bench",
                    focused = true,
                ),
            ),
            agents = listOf(
                ClientShellAgent(
                    paneId = "w1:p2",
                    workspaceId = "w1",
                    tabId = "w1:t1",
                    name = "codex",
                    displayAgent = "Codex CLI",
                    agent = "codex",
                    title = "Compiling libghostty-vt...",
                    terminalTitle = "Codex CLI — gpu-rig",
                    terminalTitleStripped = "Codex CLI — gpu-rig",
                    agentStatus = AgentStatus.Working,
                    stateChangeSeq = 8u,
                    stateLabels = listOf(listOf("working", "cargo_build")),
                    focused = true,
                ),
            ),
        )

        val workboxBackbone = ActiveBackbone.MoshUdp(
            udpPort = 60004,
            rttMs = 11,
            localEchoEnabled = true,
            roamedCount = 0,
        )
        val gpuRigBackbone = ActiveBackbone.SshEndpoint(
            rttMs = 28,
            fallbackReason = "SSH Endpoint Gen 1 (surface_delta)",
        )

        machineStates[WORKBOX_ID] = SimulatedMachineState(
            machine = workboxMachine,
            backbone = workboxBackbone,
            snapshot = workboxSnapshot,
        )
        machineStates[GPU_RIG_ID] = SimulatedMachineState(
            machine = gpuRigMachine,
            backbone = gpuRigBackbone,
            snapshot = gpuRigSnapshot,
        )

        publishMachineState(WORKBOX_ID, markConnected = true)
        publishMachineState(GPU_RIG_ID, markConnected = true)

        semanticNotificationBus.updateAgentSnippet(
            machineId = WORKBOX_ID,
            paneId = "w1:p1",
            snippet = "Allow Bash: `./gradlew :app:android:assembleDebug`?\n❯ [1] Approve (Enter)   [2] Always allow   [3] Cancel (Esc)",
        )
        semanticNotificationBus.updateAgentSnippet(
            machineId = WORKBOX_ID,
            paneId = "w1:p3",
            snippet = "✓ Verified all 19 Herdr color palettes (Gruvbox Dark default)",
        )
        semanticNotificationBus.updateAgentSnippet(
            machineId = GPU_RIG_ID,
            paneId = "w1:p2",
            snippet = "Compiling libghostty-vt (aarch64-linux-android) [120Hz SIMD]...",
        )
        semanticNotificationBus.publish(
            machineId = WORKBOX_ID,
            notification = SemanticNotification(
                bootId = workboxSnapshot.bootId,
                sequence = 1u,
                title = "Claude Code waiting for approval",
                body = "Allow Bash: ./gradlew :app:android:assembleDebug?",
                paneId = "w1:p1",
                agentName = "claude",
                agentStatus = AgentStatus.Blocked,
            ),
        )
        sshDebugLog.record(
            machineId = WORKBOX_ID,
            tag = "MOSH",
            message = "MOSH CONNECT 60004 · AES-128-OCB UDP SSP active (11ms RTT, local echo ON)",
        )
        sshDebugLog.record(
            machineId = GPU_RIG_ID,
            tag = "ENDPOINT",
            message = "Endpoint Generation 1 connected (shell.snapshot.v1 + surface_delta, 28ms RTT)",
        )
    }

    override suspend fun handleConnect(machineId: String): Boolean {
        if (!enabled) return false
        val state = machineStates[machineId] ?: createAdHocMachineState(machineId)
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.AuthenticatingSsh)
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.BootstrappingMoshOrBridge)
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.VerifyingUdpOrHello)
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.SyncingHerdrSnapshot)
        publishMachineState(state.machine.id, markConnected = true)
        sshDebugLog.record(
            machineId = machineId,
            tag = "DemoConnect",
            message = "Connected simulated machine $machineId (${state.backbone})",
        )
        return true
    }

    override suspend fun handleDisconnect(machineId: String): Boolean {
        if (!enabled) return false
        sshStateManager.markMachineDisconnected(machineId)
        sshDebugLog.record(
            machineId = machineId,
            tag = "DemoDisconnect",
            message = "Disconnected simulated machine $machineId",
        )
        return true
    }

    override suspend fun handleSetSurfaceLease(machineId: String, active: Boolean): Boolean {
        if (!enabled) return false
        val state = machineStates[machineId] ?: return false
        if (active) {
            sshStateManager.setRoamingStatus(machineId, RoamingStatus.Healthy)
            state.snapshot = state.snapshot.copy(revision = state.snapshot.revision + 1u)
            publishMachineState(machineId, markConnected = false)
            sshDebugLog.record(
                machineId = machineId,
                tag = "SurfaceLease",
                message = "client_shell.surface.set(active=true) -> resumed 120Hz surface stream (<20ms)",
            )
        } else {
            sshStateManager.setRoamingStatus(machineId, RoamingStatus.SurfaceParked)
            sshDebugLog.record(
                machineId = machineId,
                tag = "SurfaceLease",
                message = "client_shell.surface.set(active=false) -> parked surface stream (0 B/s idle lease)",
            )
        }
        return true
    }

    override suspend fun handleEndpointCommand(
        machineId: String,
        method: String,
        paramsJson: String,
    ): String? {
        if (!enabled) return null
        val state = machineStates[machineId] ?: return null

        sshDebugLog.record(
            machineId = machineId,
            tag = "RPC",
            message = "$method $paramsJson",
        )

        val params by lazy(LazyThreadSafetyMode.NONE) {
            if (paramsJson.isBlank() || paramsJson == "{}") {
                EMPTY_JSON_OBJECT
            } else {
                runCatching { json.parseToJsonElement(paramsJson).jsonObject }
                    .getOrElse { EMPTY_JSON_OBJECT }
            }
        }

        when (method) {
            "client_shell.surface.set" -> {
                val active = params["active"]?.jsonPrimitive?.booleanOrNull ?: true
                handleSetSurfaceLease(machineId, active)
                return """{"result":{"type":"client_shell_surface_set","active":$active}}"""
            }

            "workspace.focus" -> {
                val targetWsId = params["workspace_id"]?.jsonPrimitive?.content ?: return null
                focusWorkspace(state, targetWsId)
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"workspace_info","workspace_id":"$targetWsId"}}"""
            }

            "workspace.create" -> {
                val nextIndex = state.snapshot.workspaces.size + 1
                val newWsId = "w$nextIndex"
                val newTabId = "$newWsId:t1"
                val newPaneId = "$newWsId:p1"
                val label = params["label"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: "workspace-$nextIndex"
                val cwd = params["cwd"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: "/home/ian/workspace/herdr-tablet"
                val focus = params["focus"]?.jsonPrimitive?.booleanOrNull ?: true

                val newWorkspace = ClientShellWorkspace(
                    workspaceId = newWsId,
                    activeTabId = newTabId,
                    newWorkspaceCwd = cwd,
                    number = nextIndex,
                    label = label,
                    customLabel = true,
                    branch = "main",
                    focused = focus,
                    agentStatus = AgentStatus.Idle,
                )
                val newTab = ClientShellTab(
                    tabId = newTabId,
                    workspaceId = newWsId,
                    number = 1,
                    label = "1:main",
                    focused = focus,
                    agentStatus = AgentStatus.Idle,
                )
                val newPane = ClientShellPane(
                    paneId = newPaneId,
                    workspaceId = newWsId,
                    tabId = newTabId,
                    label = "$label · Pane #1",
                    cwd = cwd,
                    foregroundCwd = cwd,
                    focused = focus,
                )
                state.snapshot = state.snapshot.copy(
                    revision = state.snapshot.revision + 1u,
                    workspaces = state.snapshot.workspaces + newWorkspace,
                    tabs = state.snapshot.tabs + newTab,
                    panes = state.snapshot.panes + newPane,
                )
                if (focus) {
                    focusWorkspace(state, newWsId)
                }
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"workspace_created","workspace_id":"$newWsId"}}"""
            }

            "worktree.create" -> {
                val branch = params["branch"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: "feat/tablet-roam"
                val nextIndex = state.snapshot.workspaces.size + 1
                val newWsId = "w$nextIndex"
                val newTabId = "$newWsId:t1"
                val newPaneId = "$newWsId:p1"
                val focus = params["focus"]?.jsonPrimitive?.booleanOrNull ?: true
                val label = "wt/${branch.substringAfterLast('/')}"

                val newWorkspace = ClientShellWorkspace(
                    workspaceId = newWsId,
                    activeTabId = newTabId,
                    newWorkspaceCwd = "/home/ian/workspace/worktrees/${branch.replace('/', '-')}",
                    number = nextIndex,
                    label = label,
                    branch = branch,
                    worktree = ClientShellWorktreeInfo(
                        key = "herdr-tablet/$branch",
                        label = label,
                        isLinkedWorktree = true,
                    ),
                    focused = focus,
                    agentStatus = AgentStatus.Idle,
                )
                val newTab = ClientShellTab(
                    tabId = newTabId,
                    workspaceId = newWsId,
                    number = 1,
                    label = "1:$label",
                    focused = focus,
                    agentStatus = AgentStatus.Idle,
                )
                val newPane = ClientShellPane(
                    paneId = newPaneId,
                    workspaceId = newWsId,
                    tabId = newTabId,
                    label = "$label · Pane #1",
                    cwd = newWorkspace.newWorkspaceCwd,
                    foregroundCwd = newWorkspace.newWorkspaceCwd,
                    focused = focus,
                )
                state.snapshot = state.snapshot.copy(
                    revision = state.snapshot.revision + 1u,
                    workspaces = state.snapshot.workspaces + newWorkspace,
                    tabs = state.snapshot.tabs + newTab,
                    panes = state.snapshot.panes + newPane,
                )
                if (focus) {
                    focusWorkspace(state, newWsId)
                }
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"worktree_created","workspace_id":"$newWsId","branch":"$branch"}}"""
            }

            "tab.focus" -> {
                val targetTabId = params["tab_id"]?.jsonPrimitive?.content ?: return null
                focusTab(state, targetTabId)
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"tab_info","tab_id":"$targetTabId"}}"""
            }

            "tab.create" -> {
                val wsId = params["workspace_id"]?.jsonPrimitive?.content
                    ?: state.snapshot.focusedWorkspaceId
                    ?: "w1"
                val wsTabs = state.snapshot.tabs.filter { it.workspaceId == wsId }
                val nextNum = wsTabs.size + 1
                val newTabId = "$wsId:t$nextNum"
                val newPaneId = "$wsId:p${state.snapshot.panes.size + 1}"
                val label = params["label"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: "$nextNum:shell"
                val focus = params["focus"]?.jsonPrimitive?.booleanOrNull ?: true

                val newTab = ClientShellTab(
                    tabId = newTabId,
                    workspaceId = wsId,
                    number = nextNum,
                    label = label,
                    focused = focus,
                    agentStatus = AgentStatus.Idle,
                )
                val newPane = ClientShellPane(
                    paneId = newPaneId,
                    workspaceId = wsId,
                    tabId = newTabId,
                    label = "Shell · $newPaneId",
                    cwd = "/home/ian/workspace/herdr-tablet",
                    foregroundCwd = "/home/ian/workspace/herdr-tablet",
                    focused = focus,
                )
                state.snapshot = state.snapshot.copy(
                    revision = state.snapshot.revision + 1u,
                    tabs = state.snapshot.tabs + newTab,
                    panes = state.snapshot.panes + newPane,
                )
                if (focus) {
                    focusTab(state, newTabId)
                }
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"tab_created","tab_id":"$newTabId"}}"""
            }

            "pane.focus" -> {
                val targetPaneId = params["pane_id"]?.jsonPrimitive?.content ?: return null
                focusPane(state, targetPaneId)
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"pane_info","pane_id":"$targetPaneId"}}"""
            }

            "pane.split" -> {
                val activeWsId = state.snapshot.focusedWorkspaceId ?: "w1"
                val activeTabId = state.snapshot.focusedTabId ?: "w1:t1"
                val newPaneIndex = state.snapshot.panes.size + 1
                val newPaneId = "$activeWsId:p$newPaneIndex"
                val direction = params["direction"]?.jsonPrimitive?.content ?: "right"
                val newPane = ClientShellPane(
                    paneId = newPaneId,
                    workspaceId = activeWsId,
                    tabId = activeTabId,
                    label = "Split ($direction) · Pane #$newPaneIndex",
                    cwd = "/home/ian/workspace/herdr-tablet",
                    foregroundCwd = "/home/ian/workspace/herdr-tablet",
                    focused = true,
                )
                state.snapshot = state.snapshot.copy(
                    revision = state.snapshot.revision + 1u,
                    panes = state.snapshot.panes + newPane,
                )
                focusPane(state, newPaneId)
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"pane_info","pane_id":"$newPaneId"}}"""
            }

            "pane.zoom" -> {
                val activeTabId = state.snapshot.focusedTabId ?: "w1:t1"
                val mode = params["mode"]?.jsonPrimitive?.content ?: "toggle"
                state.snapshot = state.snapshot.copy(
                    revision = state.snapshot.revision + 1u,
                    tabs = state.snapshot.tabs.map { tab ->
                        if (tab.tabId == activeTabId) {
                            val nextZoom = when (mode) {
                                "on", "enable", "true" -> true
                                "off", "disable", "false" -> false
                                else -> !tab.zoomed
                            }
                            tab.copy(zoomed = nextZoom)
                        } else {
                            tab
                        }
                    },
                )
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"pane_zoom","tab_id":"$activeTabId"}}"""
            }

            "layout.set_split_ratio" -> {
                val ratio = params["ratio"]?.jsonPrimitive?.floatOrNull?.coerceIn(0.15f, 0.85f) ?: 0.5f
                val pathArray = params["path"] as? JsonArray
                val path = pathArray?.mapNotNull { it.jsonPrimitive.booleanOrNull }.orEmpty()
                if (path.isEmpty()) {
                    state.rootSplitRatio = ratio
                } else {
                    state.rightSplitRatio = ratio
                }
                state.snapshot = state.snapshot.copy(revision = state.snapshot.revision + 1u)
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"layout_split_ratio_set","ratio":$ratio}}"""
            }

            "agent.focus" -> {
                val targetName = params["name"]?.jsonPrimitive?.content
                val targetPaneId = params["pane_id"]?.jsonPrimitive?.content
                    ?: state.snapshot.agents.firstOrNull {
                        it.name == targetName
                    }?.paneId
                    ?: state.snapshot.agents.firstOrNull()?.paneId
                if (targetPaneId != null) {
                    focusPane(state, targetPaneId)
                    publishMachineState(machineId, markConnected = false)
                }
                return """{"result":{"type":"agent_info","pane_id":"${targetPaneId.orEmpty()}"}}"""
            }

            "agent.send_keys", "pane.send_keys" -> {
                val targetPaneId = params["pane_id"]?.jsonPrimitive?.content
                    ?: state.snapshot.focusedPaneId
                    ?: "w1:p1"
                val keys = (params["keys"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.content }
                    .orEmpty()
                val joinedKeys = keys.joinToString(" ").lowercase()
                if (joinedKeys.contains("enter") || joinedKeys.contains("y") || joinedKeys.contains("1")) {
                    resolveAgentPermissionPrompt(machineId, state, targetPaneId, approved = true)
                } else if (joinedKeys.contains("esc") || joinedKeys.contains("n") || joinedKeys.contains("3")) {
                    resolveAgentPermissionPrompt(machineId, state, targetPaneId, approved = false)
                }
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"ok"}}"""
            }

            "agent.prompt", "pane.send_input" -> {
                val targetPaneId = params["pane_id"]?.jsonPrimitive?.content
                    ?: state.snapshot.focusedPaneId
                    ?: "w1:p1"
                val promptText = params["prompt"]?.jsonPrimitive?.content
                    ?: params["text"]?.jsonPrimitive?.content
                    ?: ""
                submitPromptToPane(machineId, state, targetPaneId, promptText)
                publishMachineState(machineId, markConnected = false)
                return """{"result":{"type":"agent_prompted","pane_id":"$targetPaneId"}}"""
            }

            "agent.read", "pane.read" -> {
                val targetPaneId = params["pane_id"]?.jsonPrimitive?.content
                    ?: state.snapshot.focusedPaneId
                    ?: "w1:p1"
                val snippet = semanticNotificationBus.snippetFor(machineId, targetPaneId)
                    ?: "Pane $targetPaneId ready."
                val escaped = snippet.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                return """{"result":{"type":"pane_read","pane_id":"$targetPaneId","text":"$escaped"}}"""
            }

            "pane.selection.read" -> {
                val targetPaneId = params["pane_id"]?.jsonPrimitive?.content
                    ?: state.snapshot.focusedPaneId
                    ?: "w1:p1"
                return """{"result":{"type":"pane_selection","pane_id":"$targetPaneId","text":"./gradlew :app:android:assembleDebug"}}"""
            }

            else -> {
                return """{"result":{"type":"ok"}}"""
            }
        }
    }

    override suspend fun handlePaneInput(
        machineId: String,
        paneId: String,
        inputJson: String,
    ): Boolean {
        if (!enabled) return false
        val state = machineStates[machineId] ?: return false
        val lower = inputJson.lowercase()

        when {
            lower.contains("\"enter\"") ||
                lower.contains("char_y") ||
                lower.contains("approve") -> {
                resolveAgentPermissionPrompt(machineId, state, paneId, approved = true)
            }

            lower.contains("\"escape\"") ||
                lower.contains("char_n") ||
                lower.contains("reject") ||
                lower.contains("char_c") -> {
                resolveAgentPermissionPrompt(machineId, state, paneId, approved = false)
            }

            lower.contains("backspace") -> {
                if (state.claudePromptInput.isNotEmpty()) {
                    state.claudePromptInput = state.claudePromptInput.dropLast(1)
                }
            }

            else -> {
                val extractedText = extractTextFromInputJson(inputJson)
                if (!extractedText.isNullOrEmpty()) {
                    if (extractedText.contains('\n')) {
                        submitPromptToPane(machineId, state, paneId, extractedText.trim())
                    } else {
                        state.claudePromptInput += extractedText
                    }
                }
            }
        }

        state.snapshot = state.snapshot.copy(revision = state.snapshot.revision + 1u)
        publishMachineState(machineId, markConnected = false)
        return true
    }

    override suspend fun handleResizeShell(
        machineId: String,
        cols: UShort,
        rows: UShort,
    ): Boolean {
        if (!enabled) return false
        val state = machineStates[machineId] ?: return false
        state.cols = max(40, cols.toInt()).toUShort()
        state.rows = max(12, rows.toInt()).toUShort()
        state.snapshot = state.snapshot.copy(revision = state.snapshot.revision + 1u)
        publishMachineState(machineId, markConnected = false)
        return true
    }

    private fun resolveAgentPermissionPrompt(
        machineId: String,
        state: SimulatedMachineState,
        paneId: String,
        approved: Boolean,
    ) {
        val nextStatus = if (approved) AgentStatus.Working else AgentStatus.Idle
        val statusSummary = if (approved) {
            "✓ Approved: running `./gradlew :app:android:assembleDebug` (BUILD SUCCESSFUL in 1.8s)"
        } else {
            "✕ Rejected bash command; awaiting next instruction."
        }
        state.claudeApprovalNote = statusSummary
        state.claudePromptInput = ""

        val updatedAgents = state.snapshot.agents.map { agent ->
            if (agent.paneId == paneId || agent.agentStatus == AgentStatus.Blocked) {
                agent.copy(
                    agentStatus = nextStatus,
                    title = statusSummary,
                    stateChangeSeq = agent.stateChangeSeq + 1u,
                )
            } else {
                agent
            }
        }
        val activeWsId = state.snapshot.focusedWorkspaceId ?: "w1"
        val activeTabId = state.snapshot.focusedTabId ?: "w1:t1"
        state.snapshot = state.snapshot.copy(
            revision = state.snapshot.revision + 1u,
            agents = updatedAgents,
            workspaces = state.snapshot.workspaces.map { ws ->
                if (ws.workspaceId == activeWsId) ws.copy(agentStatus = nextStatus) else ws
            },
            tabs = state.snapshot.tabs.map { tab ->
                if (tab.tabId == activeTabId) tab.copy(agentStatus = nextStatus) else tab
            },
        )
        semanticNotificationBus.updateAgentSnippet(
            machineId = machineId,
            paneId = paneId,
            snippet = statusSummary,
        )
    }

    private fun submitPromptToPane(
        machineId: String,
        state: SimulatedMachineState,
        paneId: String,
        promptText: String,
    ) {
        if (promptText.isBlank()) return
        val lines = state.paneExtraLines.getOrPut(paneId) { mutableListOf() }
        lines.add("❯ $promptText")
        lines.add("● Agent executing: $promptText")
        state.claudePromptInput = ""
        state.claudeApprovalNote = "✓ Prompt submitted: $promptText"

        val updatedAgents = state.snapshot.agents.map { agent ->
            if (agent.paneId == paneId) {
                agent.copy(
                    agentStatus = AgentStatus.Working,
                    title = "Working: $promptText",
                    stateChangeSeq = agent.stateChangeSeq + 1u,
                )
            } else {
                agent
            }
        }
        state.snapshot = state.snapshot.copy(
            revision = state.snapshot.revision + 1u,
            agents = updatedAgents,
        )
        semanticNotificationBus.updateAgentSnippet(
            machineId = machineId,
            paneId = paneId,
            snippet = "❯ $promptText\n● Agent executing task...",
        )
    }

    private fun focusWorkspace(state: SimulatedMachineState, workspaceId: String) {
        val ws = state.snapshot.workspaces.firstOrNull { it.workspaceId == workspaceId } ?: return
        val tabId = ws.activeTabId
            ?: state.snapshot.tabs.firstOrNull { it.workspaceId == workspaceId }?.tabId
            ?: "w1:t1"
        val paneId = state.snapshot.panes.firstOrNull { it.tabId == tabId }?.paneId
            ?: state.snapshot.focusedPaneId
            ?: "w1:p1"
        state.snapshot = state.snapshot.copy(
            revision = state.snapshot.revision + 1u,
            focusedWorkspaceId = workspaceId,
            focusedTabId = tabId,
            focusedPaneId = paneId,
            workspaces = state.snapshot.workspaces.map { it.copy(focused = it.workspaceId == workspaceId) },
            tabs = state.snapshot.tabs.map { it.copy(focused = it.tabId == tabId) },
            panes = state.snapshot.panes.map { it.copy(focused = it.paneId == paneId) },
            agents = state.snapshot.agents.map { it.copy(focused = it.paneId == paneId) },
        )
    }

    private fun focusTab(state: SimulatedMachineState, tabId: String) {
        val tab = state.snapshot.tabs.firstOrNull { it.tabId == tabId } ?: return
        val paneId = state.snapshot.panes.firstOrNull { it.tabId == tabId }?.paneId
            ?: state.snapshot.focusedPaneId
            ?: "w1:p1"
        state.snapshot = state.snapshot.copy(
            revision = state.snapshot.revision + 1u,
            focusedWorkspaceId = tab.workspaceId,
            focusedTabId = tabId,
            focusedPaneId = paneId,
            workspaces = state.snapshot.workspaces.map {
                it.copy(
                    focused = it.workspaceId == tab.workspaceId,
                    activeTabId = if (it.workspaceId == tab.workspaceId) tabId else it.activeTabId,
                )
            },
            tabs = state.snapshot.tabs.map { it.copy(focused = it.tabId == tabId) },
            panes = state.snapshot.panes.map { it.copy(focused = it.paneId == paneId) },
            agents = state.snapshot.agents.map { it.copy(focused = it.paneId == paneId) },
        )
    }

    private fun focusPane(state: SimulatedMachineState, paneId: String) {
        val pane = state.snapshot.panes.firstOrNull { it.paneId == paneId } ?: return
        state.snapshot = state.snapshot.copy(
            revision = state.snapshot.revision + 1u,
            focusedWorkspaceId = pane.workspaceId,
            focusedTabId = pane.tabId,
            focusedPaneId = paneId,
            workspaces = state.snapshot.workspaces.map { it.copy(focused = it.workspaceId == pane.workspaceId) },
            tabs = state.snapshot.tabs.map { it.copy(focused = it.tabId == pane.tabId) },
            panes = state.snapshot.panes.map { it.copy(focused = it.paneId == paneId) },
            agents = state.snapshot.agents.map { it.copy(focused = it.paneId == paneId) },
        )
    }

    private fun createAdHocMachineState(machineId: String): SimulatedMachineState {
        val machine = machineStore?.getMachine(machineId) ?: HostMachine(
            id = machineId,
            name = machineId,
            hostname = "$machineId.tailnet.ts.net",
            username = "ian",
        ).also { fakeMachineIds = fakeMachineIds + machineId }
        val backbone = ActiveBackbone.MoshUdp(udpPort = 60008, rttMs = 14, localEchoEnabled = true)
        val snapshot = machineStates[WORKBOX_ID]?.snapshot?.copy(bootId = "boot-$machineId")
            ?: ClientShellSnapshot(bootId = "boot-$machineId", revision = 1u)
        return SimulatedMachineState(
            machine = machine,
            backbone = backbone,
            snapshot = snapshot,
        ).also { machineStates[machineId] = it }
    }

    private fun publishMachineState(machineId: String, markConnected: Boolean) {
        val state = machineStates[machineId] ?: return
        if (markConnected) {
            sshStateManager.markMachineConnected(machineId, state.backbone)
        }
        endpointSnapshotBus.publish(machineId, state.snapshot)
        paneSurfaceBus.publish(machineId, buildSurfaceFrame(state))
    }

    private fun buildSurfaceFrame(state: SimulatedMachineState): PaneSurfaceFrame {
        val totalCols = state.cols.toInt().coerceAtLeast(40)
        val totalRows = state.rows.toInt().coerceAtLeast(12)
        val cells = MutableList(totalCols * totalRows) { CellData.EMPTY }

        val activeTab = state.snapshot.tabs.firstOrNull { it.tabId == state.snapshot.focusedTabId }
        val isZoomed = activeTab?.zoomed == true
        val focusedPaneId = state.snapshot.focusedPaneId ?: "w1:p1"

        val leftWidth = (totalCols * state.rootSplitRatio).roundToInt().coerceIn(16, totalCols - 16)
        val rightX = (leftWidth + 1).coerceAtMost(totalCols - 1)
        val rightWidth = max(1, totalCols - rightX)
        val topRightHeight = (totalRows * state.rightSplitRatio).roundToInt().coerceIn(4, totalRows - 4)
        val bottomRightY = (topRightHeight + 1).coerceAtMost(totalRows - 1)
        val bottomRightHeight = max(1, totalRows - bottomRightY)

        val p1AgentStatus = state.snapshot.agents.firstOrNull { it.paneId == "w1:p1" }?.agentStatus
            ?: AgentStatus.Blocked
        val p3AgentStatus = state.snapshot.agents.firstOrNull { it.paneId == "w1:p3" }?.agentStatus
            ?: AgentStatus.Done

        val fullRect = SurfaceRect(0u, 0u, totalCols.toUShort(), totalRows.toUShort())
        val leftRect = if (isZoomed) fullRect else SurfaceRect(0u, 0u, leftWidth.toUShort(), totalRows.toUShort())
        val topRightRect = SurfaceRect(rightX.toUShort(), 0u, rightWidth.toUShort(), topRightHeight.toUShort())
        val bottomRightRect = SurfaceRect(
            rightX.toUShort(),
            bottomRightY.toUShort(),
            rightWidth.toUShort(),
            bottomRightHeight.toUShort(),
        )

        val surfacePanes = if (isZoomed) {
            val zoomedPaneMeta = state.snapshot.panes.firstOrNull { it.paneId == focusedPaneId }
            listOf(
                PaneSurfacePane(
                    paneId = focusedPaneId,
                    rect = fullRect,
                    innerRect = fullRect,
                    scroll = PaneSurfaceScroll(offsetFromBottom = 0u, maxScrollback = 120u, viewportRows = totalRows.toUShort()),
                    focused = true,
                    zoomed = true,
                    title = zoomedPaneMeta?.label ?: "Zoomed Pane ($focusedPaneId)",
                    agentStatus = state.snapshot.agents.firstOrNull { it.paneId == focusedPaneId }?.agentStatus
                        ?: AgentStatus.Unknown,
                ),
            )
        } else {
            listOf(
                PaneSurfacePane(
                    paneId = "w1:p1",
                    rect = leftRect,
                    innerRect = leftRect,
                    scroll = PaneSurfaceScroll(offsetFromBottom = 0u, maxScrollback = 240u, viewportRows = totalRows.toUShort()),
                    focused = focusedPaneId == "w1:p1",
                    zoomed = false,
                    title = "Claude Code · Pane #1",
                    agentStatus = p1AgentStatus,
                ),
                PaneSurfacePane(
                    paneId = "w1:p2",
                    rect = topRightRect,
                    innerRect = topRightRect,
                    scroll = PaneSurfaceScroll(offsetFromBottom = 0u, maxScrollback = 80u, viewportRows = topRightHeight.toUShort()),
                    focused = focusedPaneId == "w1:p2",
                    zoomed = false,
                    title = "Mosh UDP · Pane #2",
                    agentStatus = AgentStatus.Idle,
                ),
                PaneSurfacePane(
                    paneId = "w1:p3",
                    rect = bottomRightRect,
                    innerRect = bottomRightRect,
                    scroll = PaneSurfaceScroll(offsetFromBottom = 0u, maxScrollback = 60u, viewportRows = bottomRightHeight.toUShort()),
                    focused = focusedPaneId == "w1:p3",
                    zoomed = false,
                    title = "Gemini CLI · Pane #3",
                    agentStatus = p3AgentStatus,
                ),
            )
        }

        val splits = if (isZoomed) {
            emptyList()
        } else {
            listOf(
                PaneSurfaceSplit(
                    id = "s0:root",
                    direction = PaneSurfaceSplitDirection.Vertical,
                    ratio = state.rootSplitRatio,
                    hitRect = SurfaceRect(leftWidth.toUShort(), 0u, 1u, totalRows.toUShort()),
                    path = emptyList(),
                    workspaceRect = fullRect,
                ),
                PaneSurfaceSplit(
                    id = "s1:r",
                    direction = PaneSurfaceSplitDirection.Horizontal,
                    ratio = state.rightSplitRatio,
                    hitRect = SurfaceRect(rightX.toUShort(), topRightHeight.toUShort(), rightWidth.toUShort(), 1u),
                    path = listOf(true),
                    workspaceRect = SurfaceRect(rightX.toUShort(), 0u, rightWidth.toUShort(), totalRows.toUShort()),
                ),
            )
        }

        val accentGold = CellData.rgbColor(0xD7, 0x99, 0x21)
        val greenOk = CellData.rgbColor(0xB8, 0xBB, 0x26)
        val redAlert = CellData.rgbColor(0xFB, 0x49, 0x34)
        val mauvePurple = CellData.rgbColor(0xD3, 0x86, 0x9B)
        val warmText = CellData.rgbColor(0xEB, 0xDB, 0xB2)
        val dimText = CellData.rgbColor(0xA8, 0x99, 0x84)

        val activeWs = state.snapshot.workspaces.firstOrNull { it.workspaceId == state.snapshot.focusedWorkspaceId }
        val wsTitle = activeWs?.label ?: "herdr-core"
        val wsBranch = activeWs?.branch ?: "main"

        // Render Left Pane (or Zoomed Pane)
        val leftLines = buildList {
            add(StyledLine("╭─ Claude Code · Pane #1 (${if (focusedPaneId == "w1:p1") "Focused" else "Ready"}) ─ [$wsTitle:$wsBranch] ─╮", accentGold, bold = true))
            add(StyledLine("│ Host: ${state.machine.name} (Mosh UDP SSP :60004) · Local Echo: ON · 11ms RTT │", dimText))
            add(StyledLine("╰────────────────────────────────────────────────────────────────────╯", accentGold))
            add(StyledLine("", warmText))
            add(StyledLine("● Inspecting platform/android/di/SshModule.kt", greenOk))
            add(StyledLine("● Verified Kodein bindSingleton wiring for MoshUdpEngine", greenOk))
            add(StyledLine("● Verified Gruvbox Dark (#1d2021) default theme & 19 Herdr palettes", greenOk))
            add(StyledLine("", warmText))
            val approvalNote = state.claudeApprovalNote
            if (approvalNote != null) {
                add(StyledLine(approvalNote, greenOk, bold = true))
            } else {
                add(StyledLine("⚠ Permission Prompt — Pane #1 (Claude Code Blocked)", redAlert, bold = true))
                add(StyledLine("  Allow Bash: `./gradlew :app:android:assembleDebug`?", warmText, bold = true))
                add(StyledLine("  ❯ [1] Approve (Enter)   [2] Always allow   [3] Cancel (Esc)", accentGold, bold = true))
            }
            state.paneExtraLines["w1:p1"].orEmpty().forEach { extra ->
                add(StyledLine(extra, mauvePurple))
            }
            add(StyledLine("", warmText))
            add(StyledLine("Prompt > ${state.claudePromptInput}", accentGold, bold = true, predicted = state.claudePromptInput.isNotEmpty()))
        }
        writeLinesIntoRect(cells, totalCols, totalRows, leftRect, leftLines)

        if (!isZoomed) {
            // Render Top-Right Pane (Pane #2: Mosh UDP SSP Diagnostics)
            val topRightLines = listOf(
                StyledLine("╭─ Mosh UDP SSP · Pane #2 ─────────────────────────╮", mauvePurple, bold = true),
                StyledLine("$ mosh-server new -s -c 256 -- env HERDR_CONFIG_PATH=... herdr", warmText),
                StyledLine("MOSH CONNECT 60004 4k9X2vL8pQ1mN7zR3wT6yA", accentGold, bold = true),
                StyledLine("✓ Zero-drop UDP roaming armed (AES-128-OCB · 11ms)", greenOk),
                StyledLine("✓ Sideband: shell.snapshot.v1 + surface_delta Ready", greenOk),
                StyledLine("✓ Background Lease: client_shell.surface.set Ready", dimText),
            )
            writeLinesIntoRect(cells, totalCols, totalRows, topRightRect, topRightLines)

            // Render Bottom-Right Pane (Pane #3: Gemini CLI)
            val bottomRightLines = listOf(
                StyledLine("╭─ Gemini CLI · Pane #3 (Done) ────────────────────╮", greenOk, bold = true),
                StyledLine("✓ 19 Themes registered · Default: Gruvbox Dark", warmText),
                StyledLine("✓ Split-Thumb Clusters & Native Prompt Composer", warmText),
                StyledLine("✓ Drag BSP dividers (24.dp) or double-tap to zoom", dimText),
            )
            writeLinesIntoRect(cells, totalCols, totalRows, bottomRightRect, bottomRightLines)
        }

        val promptLineRow = min(leftRect.height.toInt() - 1, leftLines.size - 1).coerceAtLeast(0)
        val cursorCol = min(
            leftRect.width.toInt() - 1,
            "Prompt > ${state.claudePromptInput}".length,
        ).coerceAtLeast(0)

        return PaneSurfaceFrame(
            bootId = state.snapshot.bootId,
            revision = state.snapshot.revision,
            frame = FrameData(
                width = totalCols.toUShort(),
                height = totalRows.toUShort(),
                cells = cells,
                cursor = CursorState(
                    x = (leftRect.x.toInt() + cursorCol).toUShort(),
                    y = (leftRect.y.toInt() + promptLineRow).toUShort(),
                    shape = CursorShape.Block,
                    visible = true,
                ),
            ),
            panes = surfacePanes,
            splits = splits,
        )
    }

    private data class StyledLine(
        val text: String,
        val fg: UInt,
        val bold: Boolean = false,
        val predicted: Boolean = false,
    )

    private fun writeLinesIntoRect(
        cells: MutableList<CellData>,
        gridCols: Int,
        gridRows: Int,
        rect: SurfaceRect,
        lines: List<StyledLine>,
    ) {
        val startCol = rect.x.toInt()
        val startRow = rect.y.toInt()
        val maxCols = rect.width.toInt()
        val maxRows = rect.height.toInt()

        for ((lineIdx, line) in lines.withIndex()) {
            if (lineIdx >= maxRows) break
            val row = startRow + lineIdx
            if (row !in 0..<gridRows) continue
            var modifier: UShort = 0u
            if (line.bold) {
                modifier = (modifier.toInt() or CellData.MOD_BOLD.toInt()).toUShort()
            }
            if (line.predicted) {
                modifier = (modifier.toInt() or CellData.MOD_PREDICTED_ECHO.toInt()).toUShort()
            }
            val rowOffset = row * gridCols
            for ((charIdx, ch) in line.text.withIndex()) {
                if (charIdx >= maxCols) break
                val col = startCol + charIdx
                if (col !in 0..<gridCols) continue
                cells[rowOffset + col] = CellData(
                    symbol = CellData.symbolForChar(ch),
                    fg = line.fg,
                    modifier = modifier,
                )
            }
        }
    }

    private fun extractTextFromInputJson(inputJson: String): String? =
        runCatching {
            val obj = json.parseToJsonElement(inputJson).jsonObject
            obj["text"]?.jsonPrimitive?.content
        }.getOrNull()

    companion object {
        const val WORKBOX_ID: String = "workbox"
        const val GPU_RIG_ID: String = "gpu-rig"
        val DEFAULT_COLS: UShort = 120u
        val DEFAULT_ROWS: UShort = 36u
        private val EMPTY_JSON_OBJECT = JsonObject(emptyMap())
    }
}
