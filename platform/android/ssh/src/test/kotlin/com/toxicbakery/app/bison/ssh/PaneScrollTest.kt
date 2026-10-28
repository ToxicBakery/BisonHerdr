package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Tests the scroll position and the `pane.scroll` handling of [RealHerdrSshClient]. */
class PaneScrollTest {

    private class FakeHerdr {
        @Volatile var serverOffset = 12
        val scrollRequests = mutableListOf<Int>()
        val snapshotCalls = mutableListOf<RealHerdrSshClient.WorkerLane>()

        suspend fun execute(
            lane: RealHerdrSshClient.WorkerLane,
            commands: List<RealHerdrSshClient.RemoteShellCommand>,
        ): String = buildString {
            for (cmd in commands) {
                when (cmd) {
                    is RealHerdrSshClient.RemoteShellCommand.Exec -> if (cmd.command.contains("herdr api snapshot")) {
                        synchronized(snapshotCalls) { snapshotCalls += lane }
                        appendLine(snapshotJson())
                    }
                    is RealHerdrSshClient.RemoteShellCommand.ApiBridgeLine -> when {
                        cmd.ndjsonLine.contains("\"pane.scroll\"") -> {
                            // A slow network, so that the requests of one drag queue up.
                            delay(40)
                            val offset = Regex("\"offset_from_bottom\":(\\d+)").find(cmd.ndjsonLine)!!.groupValues[1].toInt()
                            synchronized(scrollRequests) { scrollRequests += offset }
                            serverOffset = offset
                            appendLine("""{"id":"pane_scroll","result":{"type":"pane_info"}}""")
                        }
                        cmd.ndjsonLine.contains("\"pane.read\"") ->
                            appendLine("""{"id":"read","result":{"type":"pane_read","read":{"pane_id":"w1:p1","text":"line at $serverOffset"}}}""")
                    }
                }
            }
        }

        fun snapshotJson(): String =
            """{"id":"snap","result":{"type":"session_snapshot","snapshot":{"focused_workspace_id":"w1","focused_tab_id":"w1:t1","focused_pane_id":"w1:p1",""" +
                """"workspaces":[{"workspace_id":"w1","active_tab_id":"w1:t1","label":"main","focused":true}],""" +
                """"tabs":[{"tab_id":"w1:t1","workspace_id":"w1","number":1,"label":"1","focused":true}],""" +
                """"panes":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","focused":true,""" +
                """"scroll":{"offset_from_bottom":$serverOffset,"max_offset_from_bottom":263,"viewport_rows":40}}],"agents":[],""" +
                """"layouts":[{"workspace_id":"w1","tab_id":"w1:t1","zoomed":false,"panes":[{"pane_id":"w1:p1","rect":{"x":0,"y":0,"width":80,"height":40},"focused":true}],"splits":[]}]}}}"""
    }

    private val machine = HostMachine(id = "box", name = "box", hostname = "192.168.1.42", username = "ian")
    private val route = ProxyJumpTunnel().resolveRoute(machine)

    private fun client(fake: FakeHerdr, surfaceBus: PaneSurfaceBus) = RealHerdrSshClient(
        sshDebugLog = SshDebugLog(),
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = surfaceBus,
        semanticNotificationBus = SemanticNotificationBus(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
        remoteBatchExecutor = { _, _, lane, commands -> fake.execute(lane, commands) },
    )

    private fun PaneSurfaceBus.offset(): Int =
        assertNotNull(surfaceFor("box")).panes.single().scroll.offsetFromBottom.toInt()

    @Test
    fun frameShowsTheScrollPositionFromTheSnapshot() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        assertTrue(client(fake, surfaceBus).refreshSnapshotAndSurface(machine, route))

        val scroll = surfaceBus.surfaceFor("box")!!.panes.single().scroll
        assertEquals(12, scroll.offsetFromBottom.toInt())
        assertEquals(263, scroll.maxScrollback.toInt())
    }

    @Test
    fun aBurstOfScrollRequestsSendsOnlyTheNewestPosition() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        val snapshotsBefore = synchronized(fake.snapshotCalls) { fake.snapshotCalls.size }

        // One drag up by 30 lines, then back to the bottom.
        for (offset in 13..42) client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", offset))
        assertEquals(42, surfaceBus.offset(), "The frame shows the requested position at once")
        for (offset in 41 downTo 0) client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", offset))
        assertEquals(0, surfaceBus.offset())

        withTimeout(5_000) {
            while (fake.serverOffset != 0 || synchronized(fake.scrollRequests) { fake.scrollRequests.isEmpty() }) delay(20)
        }
        delay(200)
        val sent = synchronized(fake.scrollRequests) { fake.scrollRequests.toList() }
        assertEquals(0, sent.last(), "The last request must be the bottom")
        assertTrue(sent.size <= 4, "72 requests must collapse to a few, sent: $sent")
        assertEquals(
            snapshotsBefore,
            synchronized(fake.snapshotCalls) { fake.snapshotCalls.size },
            "A scroll must not refresh the full snapshot",
        )
        assertEquals("l", surfaceBus.surfaceFor("box")!!.frame.cellAt(0, 0)?.symbol)
    }

    @Test
    fun requestsAboveTheHistoryAreLimited() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 1_000))
        assertEquals(263, surfaceBus.offset())
        withTimeout(5_000) { while (fake.serverOffset != 263) delay(20) }
    }

    @Test
    fun typingInAScrolledPaneGoesBackToTheBottom() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(12, surfaceBus.offset())

        client.sendPaneInput(machine, route, "w1:p1", ClientPaneInputEvent.TextCommit("x"))
        withTimeout(5_000) { while (fake.serverOffset != 0) delay(20) }
        assertEquals(0, surfaceBus.offset())
    }
}
