package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Tests the scroll position and the `pane.scroll` handling of [RealHerdrSshClient]. */
class PaneScrollTest {

    /**
     * When [holdScrolls] is true, Herdr holds each `pane.scroll` request until the test calls [answerScroll].
     * Then the test, not the clock, sets the order of the events.
     */
    private class FakeHerdr(private val holdScrolls: Boolean = true) {
        @Volatile var serverOffset = 12
        val snapshotCalls = mutableListOf<RealHerdrSshClient.WorkerLane>()

        /** The offset of each `pane.scroll` request that Herdr gets, in order. */
        val scrollRequests = Channel<Int>(Channel.UNLIMITED)
        private val scrollAnswers = Channel<Unit>(Channel.UNLIMITED)

        /** Tells Herdr to answer the `pane.scroll` request that it holds. */
        fun answerScroll() {
            scrollAnswers.trySend(Unit)
        }

        /** Waits for the next `pane.scroll` request that Herdr gets. */
        suspend fun nextScroll(): Int = withTimeout(TIMEOUT_MS) { scrollRequests.receive() }

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
                            val offset = Regex("\"offset_from_bottom\":(\\d+)").find(cmd.ndjsonLine)!!.groupValues[1].toInt()
                            scrollRequests.send(offset)
                            if (holdScrolls) scrollAnswers.receive()
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

    /** Waits for the frame that shows [text] at the start of the first row. */
    private suspend fun PaneSurfaceBus.awaitFrameWithText(text: String): PaneSurfaceFrame = withTimeout(TIMEOUT_MS) {
        surfaces.mapNotNull { it["box"] }.first { surface ->
            text.indices.all { col -> surface.frame.cellAt(col, 0)?.symbol == text[col].toString() }
        }
    }

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

        // One drag up by 30 lines, then back to the bottom. Herdr holds the first request, so that the others queue up.
        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 13))
        assertEquals(13, fake.nextScroll(), "The first request must go to Herdr at once")
        for (offset in 14..42) client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", offset))
        assertEquals(42, surfaceBus.offset(), "The frame must show the requested position at once")
        for (offset in 41 downTo 0) client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", offset))
        assertEquals(0, surfaceBus.offset(), "The frame must show the bottom at once")

        fake.answerScroll()
        assertEquals(0, fake.nextScroll(), "Only the newest of the 71 queued requests must go to Herdr")
        fake.answerScroll()
        val frame = surfaceBus.awaitFrameWithText("line at 0")
        assertEquals(0, frame.panes.single().scroll.offsetFromBottom.toInt(), "The frame must stay at the bottom")
        assertNull(fake.scrollRequests.tryReceive().getOrNull(), "No request must go to Herdr after the bottom")
        assertEquals(
            snapshotsBefore,
            synchronized(fake.snapshotCalls) { fake.snapshotCalls.size },
            "A scroll must not refresh the full snapshot",
        )
    }

    @Test
    fun aFrameNeverShowsAnOlderPositionThanTheNewestRequest() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        // Herdr answers at once, so that the scroll worker publishes frames while new requests arrive.
        val fake = FakeHerdr(holdScrolls = false)
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        repeat(RACE_REQUESTS) { i ->
            val offset = i % 200
            client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", offset))
            assertEquals(offset, surfaceBus.offset(), "Request ${i + 1}: the frame must show the newest position")
        }
    }

    @Test
    fun requestsAboveTheHistoryAreLimited() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 1_000))
        assertEquals(263, surfaceBus.offset(), "The frame must stop at the top of the history")
        assertEquals(263, fake.nextScroll(), "The request to Herdr must stop at the top of the history")
        fake.answerScroll()
        val frame = surfaceBus.awaitFrameWithText("line at 263")
        assertEquals(263, frame.panes.single().scroll.offsetFromBottom.toInt(), "The frame must stay at the top")
    }

    @Test
    fun typingInAScrolledPaneGoesBackToTheBottom() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(12, surfaceBus.offset(), "The pane must start at the position from the snapshot")

        client.sendPaneInput(machine, route, "w1:p1", ClientPaneInputEvent.TextCommit("x"))
        assertEquals(0, surfaceBus.offset(), "The frame must go to the bottom at once")
        assertEquals(0, fake.nextScroll(), "Herdr must get a request to go to the bottom")
        fake.answerScroll()
    }

    private companion object {
        /** The maximum time for one wait. It stops a test that cannot continue. */
        const val TIMEOUT_MS = 5_000L
        const val RACE_REQUESTS = 2_000
    }
}
