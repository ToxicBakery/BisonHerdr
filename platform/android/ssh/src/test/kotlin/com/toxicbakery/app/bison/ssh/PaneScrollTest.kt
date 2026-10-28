package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Tests the scroll position and the `pane.scroll` handling of [RealHerdrSshClient]. */
class PaneScrollTest {

    /**
     * When [holdScrolls] is true, Herdr holds each `pane.scroll` request until the test calls [answerScroll].
     * When [holdInputs] is true, Herdr holds each `pane.send_text` request until the test calls [answerInput].
     * After [holdNextPoll], Herdr holds the output of the next poll until the test calls [answerPoll].
     * Then the test, not the clock, sets the order of the events.
     */
    private class FakeHerdr(
        private val holdScrolls: Boolean = true,
        private val holdInputs: Boolean = false,
    ) {
        @Volatile var serverOffset = 12

        /** The text of all `pane.send_text` requests. A `pane.read` shows it at the end of the line. */
        @Volatile var typed = ""
        val snapshotCalls = mutableListOf<RealHerdrSshClient.WorkerLane>()

        /** The offset of each `pane.scroll` request that Herdr gets, in order. */
        val scrollRequests = Channel<Int>(Channel.UNLIMITED)
        private val scrollAnswers = Channel<Unit>(Channel.UNLIMITED)
        private val inputAnswers = Channel<Unit>(Channel.UNLIMITED)
        private val holdPoll = AtomicBoolean(false)
        private val heldPolls = Channel<Unit>(Channel.UNLIMITED)
        private val pollAnswers = Channel<Unit>(Channel.UNLIMITED)

        /** Tells Herdr to answer the `pane.scroll` request that it holds. */
        fun answerScroll() {
            scrollAnswers.trySend(Unit)
        }

        /** Tells Herdr to answer the `pane.send_text` request that it holds. */
        fun answerInput() {
            inputAnswers.trySend(Unit)
        }

        /** Tells Herdr to hold the output of the next poll. The output shows the state of Herdr before the hold. */
        fun holdNextPoll() {
            holdPoll.set(true)
        }

        /** Waits until Herdr holds the output of a poll. */
        suspend fun awaitHeldPoll() = withTimeout(TIMEOUT_MS) { heldPolls.receive() }

        /** Tells Herdr to send the output of the poll that it holds. */
        fun answerPoll() {
            pollAnswers.trySend(Unit)
        }

        /** Waits for the next `pane.scroll` request that Herdr gets. */
        suspend fun nextScroll(): Int = withTimeout(TIMEOUT_MS) { scrollRequests.receive() }

        suspend fun execute(
            lane: RealHerdrSshClient.WorkerLane,
            commands: List<RealHerdrSshClient.RemoteShellCommand>,
        ): String {
            val output = respond(lane, commands)
            if (lane == RealHerdrSshClient.WorkerLane.Poll && holdPoll.compareAndSet(true, false)) {
                heldPolls.send(Unit)
                pollAnswers.receive()
            }
            return output
        }

        private suspend fun respond(
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
                        cmd.ndjsonLine.contains("\"pane.send_text\"") -> {
                            val text = Regex("\"text\":\"([^\"]*)\"").find(cmd.ndjsonLine)!!.groupValues[1]
                            if (holdInputs) inputAnswers.receive()
                            typed += text
                        }
                        cmd.ndjsonLine.contains("\"pane.read\"") ->
                            appendLine("""{"id":"read","result":{"type":"pane_read","read":{"pane_id":"w1:p1","text":"line at $serverOffset$typed"}}}""")
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

    /**
     * Puts the frames of the client on [bus]. After [holdNextFrame], it holds the next frame before the bus gets it,
     * until the test calls [publishHeldFrame]. The step that publishes the frame waits for the test in that time.
     */
    private class FrameGate(private val bus: PaneSurfaceBus) {
        private val holdNext = AtomicBoolean(false)
        private val held = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val published = CountDownLatch(1)

        fun publish(machineId: String, frame: PaneSurfaceFrame) {
            if (holdNext.compareAndSet(true, false)) {
                held.countDown()
                released.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                bus.publish(machineId, frame)
                published.countDown()
            } else {
                bus.publish(machineId, frame)
            }
        }

        /** Tells the gate to hold the next frame. */
        fun holdNextFrame() {
            holdNext.set(true)
        }

        /** Waits until the gate holds a frame. */
        fun awaitHeldFrame() {
            assertTrue(held.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "A step must publish a frame")
        }

        /** Sends the held frame to the bus, and waits until the bus has it. */
        fun publishHeldFrame() {
            released.countDown()
            assertTrue(published.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "The held frame must go to the bus")
        }
    }

    private fun client(
        fake: FakeHerdr,
        surfaceBus: PaneSurfaceBus,
        gate: FrameGate = FrameGate(surfaceBus),
    ) = RealHerdrSshClient(
        sshDebugLog = SshDebugLog(),
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = surfaceBus,
        semanticNotificationBus = SemanticNotificationBus(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
        secretStore = InMemorySecretStore(),
        remoteBatchExecutor = { _, _, lane, commands -> fake.execute(lane, commands) },
        framePublisher = gate::publish,
    )

    private fun PaneSurfaceBus.offset(): Int =
        assertNotNull(surfaceFor("box")).panes.single().scroll.offsetFromBottom.toInt()

    /** Returns true when the frame shows [text] at the start of the first row. */
    private fun PaneSurfaceFrame.showsText(text: String): Boolean =
        text.indices.all { col -> frame.cellAt(col, 0)?.symbol == text[col].toString() }

    /** Waits for the frame that shows [text] at the start of the first row. */
    private suspend fun PaneSurfaceBus.awaitFrameWithText(text: String): PaneSurfaceFrame = withTimeout(TIMEOUT_MS) {
        surfaces.mapNotNull { it["box"] }.first { it.showsText(text) }
    }

    /**
     * Starts [step] on a thread of [Dispatchers.IO]. Returns when the step is done, or when its thread waits for a
     * lock. Thus a frame that the step publishes without a wait is on the bus when this function returns.
     *
     * The client goes to [Dispatchers.IO] with `withContext`. On a thread of that dispatcher, `withContext` does not
     * change the thread, so the step stays on the thread until it suspends.
     */
    private fun CoroutineScope.launchUntilDoneOrBlocked(step: suspend () -> Unit): Job {
        val started = CountDownLatch(1)
        val stepThread = AtomicReference<Thread>()
        val job = launch(Dispatchers.IO) {
            stepThread.set(Thread.currentThread())
            started.countDown()
            step()
        }
        assertTrue(started.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "The step must start")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        while (!job.isCompleted && stepThread.get().state != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < deadline, "The step must end or wait for a lock")
            Thread.yield()
        }
        return job
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
    fun aPollFrameDoesNotReplaceTheFrameOfANewerScrollRequest() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val gate = FrameGate(surfaceBus)
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus, gate)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        // The poll builds its frame at the position of the snapshot, and the gate holds the frame.
        gate.holdNextFrame()
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        gate.awaitHeldFrame()
        // The user scrolls before the poll frame goes to the bus.
        val scroll = launchUntilDoneOrBlocked {
            client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 30))
        }
        gate.publishHeldFrame()
        poll.join()
        scroll.join()

        assertEquals(30, surfaceBus.offset(), "The poll frame must not replace the frame of the newer request")
        assertEquals(30, fake.nextScroll(), "The request must go to Herdr")
        fake.answerScroll()
    }

    @Test
    fun aPollKeepsThePositionOfARequestInFlight() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 30))
        assertEquals(30, fake.nextScroll(), "The request must go to Herdr")

        // Herdr holds the request, so the snapshot of this poll shows the old position.
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(30, surfaceBus.offset(), "A poll must not move the pane back while the request is in flight")

        fake.answerScroll()
        val frame = surfaceBus.awaitFrameWithText("line at 30")
        assertEquals(30, frame.panes.single().scroll.offsetFromBottom.toInt(), "The answer frame must show the request")

        // Herdr answered before this poll started, so the poll shows the position of Herdr.
        fake.serverOffset = 7
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(7, surfaceBus.offset(), "A poll after the answer must show the position of Herdr")
    }

    @Test
    fun aPollThatReadHerdrBeforeAScrollKeepsTheFrameOfTheScroll() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        // Herdr makes the output of the poll at the old position, and holds it.
        fake.holdNextPoll()
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        fake.awaitHeldPoll()

        // Herdr moves the pane and answers the request before the poll output arrives.
        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 30))
        assertEquals(30, fake.nextScroll(), "The request must go to Herdr")
        fake.answerScroll()
        surfaceBus.awaitFrameWithText("line at 30")

        fake.answerPoll()
        poll.join()
        val frame = assertNotNull(surfaceBus.surfaceFor("box"))
        assertEquals(30, frame.panes.single().scroll.offsetFromBottom.toInt(), "The poll must not move the pane back")
        assertTrue(frame.showsText("line at 30"), "The poll must not show the pane text from before the scroll")
    }

    @Test
    fun anInputFrameDoesNotReplaceTheFrameOfANewerScrollRequest() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val gate = FrameGate(surfaceBus)
        val fake = FakeHerdr(holdInputs = true)
        val client = client(fake, surfaceBus, gate)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        client.sendPaneInput(machine, route, "w1:p1", ClientPaneInputEvent.TextCommit("x"))
        assertEquals(0, fake.nextScroll(), "Herdr must get a request to go to the bottom")

        // The input flush builds its frame at the bottom, and the gate holds the frame.
        gate.holdNextFrame()
        fake.answerInput()
        gate.awaitHeldFrame()
        // The user scrolls up before the input frame goes to the bus.
        val scroll = launchUntilDoneOrBlocked {
            client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 30))
        }
        gate.publishHeldFrame()
        scroll.join()

        assertEquals(30, surfaceBus.offset(), "The input frame must not replace the frame of the newer request")
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
