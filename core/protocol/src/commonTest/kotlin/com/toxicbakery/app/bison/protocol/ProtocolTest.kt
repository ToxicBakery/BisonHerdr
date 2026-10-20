package com.toxicbakery.app.bison.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun agentStatusOrderingAndUnknownFallback() {
        val statuses = listOf(
            AgentStatus.Unknown,
            AgentStatus.Idle,
            AgentStatus.Working,
            AgentStatus.Done,
            AgentStatus.Blocked,
        )
        assertEquals(
            listOf(
                AgentStatus.Blocked,
                AgentStatus.Done,
                AgentStatus.Working,
                AgentStatus.Idle,
                AgentStatus.Unknown,
            ),
            statuses.sortedBy { it.priorityOrder },
        )

        val snapshotJson = """
            {
              "boot_id": "boot-v1",
              "revision": 7,
              "focused_workspace_id": "w1",
              "focused_tab_id": "w1:t1",
              "focused_pane_id": "w1:p1",
              "workspaces": [
                {
                  "workspace_id": "w1",
                  "label": "repo",
                  "agent_status": "future_status_from_new_server"
                }
              ],
              "agents": [
                {
                  "pane_id": "w1:p2",
                  "display_agent": "Codex",
                  "agent_status": "working"
                },
                {
                  "pane_id": "w1:p1",
                  "display_agent": "Claude",
                  "agent_status": "blocked"
                }
              ]
            }
        """.trimIndent()
        val decoded = json.decodeFromString<ClientShellSnapshot>(snapshotJson)
        assertEquals(AgentStatus.Unknown, decoded.workspaces.first().agentStatus)
        assertEquals(listOf("w1:p1", "w1:p2"), decoded.prioritizedAgents.map { it.paneId })
    }

    @Test
    fun deriveSplitsFromLayoutComputesBspPathsAndHitRectsAndRespectsZoom() {
        val layout = PaneLayoutSnapshot(
            workspaceId = "w1",
            tabId = "w1:t1",
            zoomed = false,
            panes = listOf(
                PaneLayoutPane("w1:p1", SurfaceRect(0u, 0u, 70u, 42u), focused = true),
                PaneLayoutPane("w1:p2", SurfaceRect(70u, 0u, 70u, 21u), focused = false),
                PaneLayoutPane("w1:p3", SurfaceRect(70u, 21u, 70u, 21u), focused = false),
            ),
            splits = listOf(
                PaneLayoutSplit(
                    id = "s0:root",
                    direction = PaneSurfaceSplitDirection.Vertical,
                    ratio = 0.5f,
                    rect = SurfaceRect(0u, 0u, 140u, 42u),
                ),
                PaneLayoutSplit(
                    id = "s1:r",
                    direction = PaneSurfaceSplitDirection.Horizontal,
                    ratio = 0.5f,
                    rect = SurfaceRect(70u, 0u, 70u, 42u),
                ),
                PaneLayoutSplit(
                    id = "s2:lr",
                    direction = PaneSurfaceSplitDirection.Vertical,
                    ratio = 0.5f,
                    rect = SurfaceRect(0u, 0u, 70u, 42u),
                ),
            ),
        )

        val splits = deriveSplitsFromLayout(layout)
        assertEquals(3, splits.size)

        assertEquals(emptyList(), splits[0].path)
        assertEquals(SurfaceRect(70u, 0u, 1u, 42u), splits[0].hitRect)

        assertEquals(listOf(true), splits[1].path)
        assertEquals(SurfaceRect(70u, 21u, 70u, 1u), splits[1].hitRect)

        assertEquals(listOf(false, true), splits[2].path)
        assertEquals(SurfaceRect(35u, 0u, 1u, 42u), splits[2].hitRect)

        val zoomedSplits = deriveSplitsFromLayout(layout.copy(zoomed = true))
        assertTrue(zoomedSplits.isEmpty())
    }

    @Test
    fun surfaceDeltaAssemblerAppliesFullReuseScrollAndDeltaWithPresentationFence() {
        val assembler = SurfaceDeltaAssembler()
        assembler.onPresentationSync("workbox", 4u)

        val initialCells = List(4 * 3) { idx -> CellData(symbol = "${idx % 10}") }
        val baseFrame = PaneSurfaceFrame(
            bootId = "boot-1",
            revision = 1u,
            frame = FrameData(
                width = 4u,
                height = 3u,
                cells = initialCells,
                cursor = CursorState(x = 0u, y = 0u),
            ),
            presentationEpoch = 4u,
        )

        val committedResult = assembler.commitFullFrame("workbox", baseFrame)
        assertNotNull(committedResult.presentationReadyControl)
        assertEquals(
            "endpoint.presentation.ready.v1",
            committedResult.presentationReadyControl.kind,
        )
        assertEquals("""{"epoch":4}""", committedResult.presentationReadyControl.dataJson)

        // Reuse keeps cells while updating revision
        val reused = assembler.applySurfaceReuse(
            "workbox",
            baseFrame.copy(
                revision = 2u,
                frame = FrameData(width = 4u, height = 3u, cells = emptyList()),
            ),
        )
        assertEquals(2uL, reused.frame.revision)
        assertEquals("0", reused.frame.frame.cellAt(0, 0)?.symbol)
        assertNull(reused.presentationReadyControl)

        // Patch row 1 starting at col 1
        val patch = PaneSurfacePatch(
            bootId = "boot-1",
            revision = 3u,
            width = 4u,
            height = 3u,
            rows = listOf(
                PaneSurfacePatchRow(
                    y = 1u,
                    startX = 1u,
                    cells = listOf(CellData(symbol = "A"), CellData(symbol = "B")),
                ),
            ),
        )
        val patched = assembler.applySurfaceDelta("workbox", patch)
        assertNotNull(patched)
        assertEquals("A", patched.frame.frame.cellAt(1, 1)?.symbol)
        assertEquals("B", patched.frame.frame.cellAt(2, 1)?.symbol)

        // Scroll region up by 1 row (row 1 moves to row 0)
        val scrolled = assembler.applySurfaceScroll(
            machineId = "workbox",
            region = SurfaceRect(0u, 0u, 4u, 3u),
            deltaRows = 1,
        )
        assertNotNull(scrolled)
        assertEquals("A", scrolled.frame.frame.cellAt(1, 0)?.symbol)
        assertEquals(" ", scrolled.frame.frame.cellAt(1, 2)?.symbol)
    }

    @Test
    fun herdrRpcBuilderBuildsExpectedPayloadsAndUsesVisibleForWorkingOrBlockedAgents() {
        val blockedRead = HerdrRpcBuilder.agentRead(
            paneId = "w1:p1",
            agentStatus = AgentStatus.Blocked,
        )
        assertTrue(blockedRead.paramsJson.contains(""""source":"visible""""))

        val workingRead = HerdrRpcBuilder.agentRead(
            paneId = "w1:p2",
            agentStatus = AgentStatus.Working,
        )
        assertTrue(workingRead.paramsJson.contains(""""source":"visible""""))

        val idleRead = HerdrRpcBuilder.agentRead(
            paneId = "w1:p3",
            agentStatus = AgentStatus.Idle,
        )
        assertTrue(idleRead.paramsJson.contains(""""source":"recent""""))

        val splitRatioReq = HerdrRpcBuilder.layoutSetSplitRatio(
            path = listOf(false, true),
            ratio = 0.65f,
            tabId = "w1:t1",
        )
        assertEquals("layout.set_split_ratio", splitRatioReq.method)
        assertTrue(splitRatioReq.paramsJson.contains(""""path":[false,true]"""))

        val ndjson = HerdrRpcBuilder.toNdjsonLine(HerdrRpcBuilder.clientShellSurfaceSet(active = false))
        assertTrue(ndjson.endsWith("\n"))
        assertTrue(ndjson.contains(""""method":"client_shell.surface.set""""))
    }

    @Test
    fun moshBootstrapConfigParsesConnectBannerAndGeneratesCleanTabletConfig() {
        val banner = """
            Warning: Permanently added '100.64.0.12' (ED25519) to the list of known hosts.

            MOSH CONNECT 60004 4k9X2vL8pQ1mN7zR3wT6yA
            mosh-server (mosh 1.4.0) [build mosh 1.4.0]
        """.trimIndent()

        val parsed = MoshBootstrapConfig.parseMoshConnectLine(banner)
        assertNotNull(parsed)
        assertEquals(60004, parsed.udpPort)
        assertEquals("4k9X2vL8pQ1mN7zR3wT6yA", parsed.sessionKey)
        assertNull(MoshBootstrapConfig.parseMoshConnectLine("no mosh connect here"))

        val configToml = MoshBootstrapConfig.buildTabletHerdrConfigToml()
        assertTrue(configToml.contains("onboarding = false"))
        assertTrue(configToml.contains("""pane_borders = "off""""))
        assertTrue(configToml.contains("pane_outer_borders = false"))
        assertTrue(configToml.contains("pane_scrollbars = false"))
        assertTrue(configToml.contains("pane_gaps = false"))

        assertTrue(MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode("workspace.focus"))
        assertTrue(MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode("tab.focus"))
        assertTrue(MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode("pane.focus"))
        assertTrue(MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode("agent.focus"))
        assertFalse(MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode("pane.scroll"))
        assertTrue(MoshBootstrapConfig.isClientShellSupportedMethod("client_shell.surface.set"))
        assertFalse(MoshBootstrapConfig.isClientShellSupportedMethod("agent.prompt"))
    }
}
