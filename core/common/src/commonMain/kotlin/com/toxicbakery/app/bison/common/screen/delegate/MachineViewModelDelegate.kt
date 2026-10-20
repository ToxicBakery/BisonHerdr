package com.toxicbakery.app.bison.common.screen.delegate

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.store.MachineStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface MachineViewModelDelegate {
    val knownMachines: StateFlow<List<HostMachine>>
    val snapshots: StateFlow<Map<String, ClientShellSnapshot>>

    fun markPendingConnect(machineId: String) {}
    fun consumePendingConnect(machineId: String): Boolean = false
    suspend fun connect(machineId: String)
    suspend fun disconnect(machineId: String)
    suspend fun setSurfaceLease(machineId: String, active: Boolean)
    suspend fun upsertMachine(machine: HostMachine)
    suspend fun removeMachine(machineId: String)
    suspend fun updateFontSize(machineId: String, fontSizeSp: Float)
    suspend fun updateThemeOverride(machineId: String, themeId: String?)

    suspend fun focusWorkspace(machineId: String, workspaceId: String)
    suspend fun createWorkspace(machineId: String, label: String?, cwd: String? = null)
    suspend fun createWorktree(machineId: String, branch: String, workspaceId: String? = null)

    suspend fun focusTab(machineId: String, tabId: String)
    suspend fun createTab(machineId: String, workspaceId: String? = null, label: String? = null)
    suspend fun closeTab(machineId: String, tabId: String)

    suspend fun focusPane(machineId: String, paneId: String)
    suspend fun splitPane(
        machineId: String,
        paneId: String? = null,
        direction: String = "right",
        ratio: Float = 0.5f,
    )
    suspend fun zoomPane(machineId: String, paneId: String? = null, mode: String = "toggle")
    suspend fun closePane(machineId: String, paneId: String)
    suspend fun setSplitRatio(
        machineId: String,
        path: List<Boolean>,
        ratio: Float,
        tabId: String? = null,
    )
    suspend fun scrollPane(machineId: String, paneId: String, offsetFromBottom: Int)
    suspend fun resizeShell(
        machineId: String,
        cols: UShort,
        rows: UShort,
        cellWidthPx: Int = 18,
        cellHeightPx: Int = 36,
    )
    suspend fun copySelection(
        machineId: String,
        paneId: String,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        contentRevision: ULong? = null,
    )
}

class MachineViewModelDelegateImpl(
    private val machineStore: MachineStore,
    private val sshRequestManager: SshRequestManager,
    private val endpointSnapshotBus: EndpointSnapshotBus,
) : MachineViewModelDelegate {

    override val knownMachines: StateFlow<List<HostMachine>> = machineStore.knownMachines
    override val snapshots: StateFlow<Map<String, ClientShellSnapshot>> = endpointSnapshotBus.snapshots
    private val pendingConnectIds = MutableStateFlow<Set<String>>(emptySet())

    override fun markPendingConnect(machineId: String) {
        pendingConnectIds.update { it + machineId }
    }

    override fun consumePendingConnect(machineId: String): Boolean {
        var wasPresent = false
        pendingConnectIds.update { current ->
            wasPresent = machineId in current
            if (wasPresent) current - machineId else current
        }
        return wasPresent
    }

    override suspend fun connect(machineId: String) {
        sshRequestManager.emit(SshRequest.Connect(machineId))
    }

    override suspend fun disconnect(machineId: String) {
        pendingConnectIds.update { it - machineId }
        sshRequestManager.emit(SshRequest.Disconnect(machineId))
    }

    override suspend fun setSurfaceLease(machineId: String, active: Boolean) {
        sshRequestManager.emit(SshRequest.SetSurfaceLease(machineId = machineId, active = active))
    }

    override suspend fun upsertMachine(machine: HostMachine) {
        machineStore.upsertMachine(machine)
    }

    override suspend fun removeMachine(machineId: String) {
        machineStore.removeMachine(machineId)
    }

    override suspend fun updateFontSize(machineId: String, fontSizeSp: Float) {
        machineStore.updateFontSize(machineId, fontSizeSp)
    }

    override suspend fun updateThemeOverride(machineId: String, themeId: String?) {
        machineStore.updateThemeOverride(machineId, themeId)
    }

    override suspend fun focusWorkspace(machineId: String, workspaceId: String) {
        sendEndpointCommand(machineId, HerdrRpcBuilder.workspaceFocus(workspaceId))
    }

    override suspend fun createWorkspace(machineId: String, label: String?, cwd: String?) {
        sendEndpointCommand(machineId, HerdrRpcBuilder.workspaceCreate(label = label, cwd = cwd, focus = true))
    }

    override suspend fun createWorktree(machineId: String, branch: String, workspaceId: String?) {
        sendEndpointCommand(
            machineId,
            HerdrRpcBuilder.worktreeCreate(branch = branch, workspaceId = workspaceId, focus = true),
        )
    }

    override suspend fun focusTab(machineId: String, tabId: String) {
        sendEndpointCommand(machineId, HerdrRpcBuilder.tabFocus(tabId))
    }

    override suspend fun createTab(machineId: String, workspaceId: String?, label: String?) {
        sendEndpointCommand(
            machineId,
            HerdrRpcBuilder.tabCreate(workspaceId = workspaceId, label = label, focus = true),
        )
    }

    override suspend fun closeTab(machineId: String, tabId: String) {
        val request = ClientShellEndpointRequest(
            id = "tab_close",
            method = "tab.close",
            paramsJson = buildJsonObject { put("tab_id", tabId) }.toString(),
        )
        sendEndpointCommand(machineId, request)
    }

    override suspend fun focusPane(machineId: String, paneId: String) {
        sendEndpointCommand(machineId, HerdrRpcBuilder.paneFocus(paneId))
    }

    override suspend fun splitPane(
        machineId: String,
        paneId: String?,
        direction: String,
        ratio: Float,
    ) {
        sendEndpointCommand(
            machineId,
            HerdrRpcBuilder.paneSplit(paneId = paneId, direction = direction, ratio = ratio, focus = true),
        )
    }

    override suspend fun zoomPane(machineId: String, paneId: String?, mode: String) {
        sendEndpointCommand(machineId, HerdrRpcBuilder.paneZoom(paneId = paneId, mode = mode))
    }

    override suspend fun closePane(machineId: String, paneId: String) {
        val request = ClientShellEndpointRequest(
            id = "pane_close",
            method = "pane.close",
            paramsJson = buildJsonObject { put("pane_id", paneId) }.toString(),
        )
        sendEndpointCommand(machineId, request)
    }

    override suspend fun setSplitRatio(
        machineId: String,
        path: List<Boolean>,
        ratio: Float,
        tabId: String?,
    ) {
        sendEndpointCommand(
            machineId,
            HerdrRpcBuilder.layoutSetSplitRatio(path = path, ratio = ratio, tabId = tabId),
        )
    }

    override suspend fun scrollPane(machineId: String, paneId: String, offsetFromBottom: Int) {
        sendEndpointCommand(
            machineId,
            HerdrRpcBuilder.paneScroll(paneId = paneId, offsetFromBottom = offsetFromBottom),
        )
    }

    override suspend fun resizeShell(
        machineId: String,
        cols: UShort,
        rows: UShort,
        cellWidthPx: Int,
        cellHeightPx: Int,
    ) {
        sshRequestManager.emit(
            SshRequest.ResizeShell(
                machineId = machineId,
                cols = cols,
                rows = rows,
                cellWidthPx = cellWidthPx,
                cellHeightPx = cellHeightPx,
            )
        )
    }

    override suspend fun copySelection(
        machineId: String,
        paneId: String,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        contentRevision: ULong?,
    ) {
        sendEndpointCommand(
            machineId,
            HerdrRpcBuilder.paneSelectionRead(
                paneId = paneId,
                startRow = startRow,
                startCol = startCol,
                endRow = endRow,
                endCol = endCol,
                contentRevision = contentRevision,
            ),
        )
    }

    private suspend fun sendEndpointCommand(machineId: String, request: ClientShellEndpointRequest) {
        sshRequestManager.emit(SshRequest.SendEndpointCommand(machineId = machineId, request = request))
    }
}
