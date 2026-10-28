package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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

    /** A `pane.scroll` request that Herdr got. */
    private data class ScrollCall(val paneId: String, val offset: Int)

    /**
     * A poll batch that Herdr holds until the test calls [release]. When [beforeRead] is true, Herdr holds the batch
     * before it reads its state. Otherwise Herdr reads its state, and then holds the output. When [snapshot] is true,
     * Herdr holds a batch with `herdr api snapshot`. Otherwise it holds a batch with only pane reads.
     */
    private class HeldPoll(val beforeRead: Boolean, val snapshot: Boolean) {
        private val held = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        /** True when Herdr holds the batch. */
        val isHeld: Boolean
            get() = held.isCompleted

        /** Waits until Herdr holds the batch. */
        suspend fun awaitHeld() = withTimeout(TIMEOUT_MS) { held.await() }

        /** Tells Herdr to continue the batch. */
        fun release() {
            released.complete(Unit)
        }

        /** Holds the batch until the test calls [release]. */
        suspend fun hold() {
            held.complete(Unit)
            released.await()
        }
    }

    /**
     * A Herdr server with one tab. The panes of [paneIds] are side by side, and each pane starts at offset 12 of 263.
     * The server tells that the first pane has the focus. The layout shows the panes of [layoutPaneIds].
     *
     * When [holdScrolls] is true, Herdr holds each `pane.scroll` request until the test calls [answerScroll].
     * When [holdInputs] is true, Herdr holds each `pane.send_text` request until the test calls [answerInput].
     * [holdNextPoll] holds one poll batch. Then the test, not the clock, sets the order of the events.
     *
     * The client gives each batch to [execute] through `remoteBatchExecutor`. That path has no lane lock, so two
     * batches of one lane can run at the same time. In production, each lane runs one batch at a time. Thus some
     * orders of these tests cannot occur in production. The tests check the rules of the client for each order.
     */
    private class FakeHerdr(
        private val paneIds: List<String> = listOf("w1:p1"),
        private val holdScrolls: Boolean = true,
        private val holdInputs: Boolean = false,
    ) {
        private val offsets = ConcurrentHashMap(paneIds.associateWith { 12 })

        /** The scroll offset of the first pane. */
        var serverOffset: Int
            get() = offsets.getValue(paneIds.first())
            set(value) {
                offsets[paneIds.first()] = value
            }

        /** The text of all `pane.send_text` requests. A `pane.read` shows it after the offset. */
        @Volatile var typed = ""

        /** New output of the shell. A `pane.read` shows it at the end of the line. */
        @Volatile var newOutput = ""

        /** When true, each `pane.scroll` request fails after the answer of the test. */
        @Volatile var failScrolls = false

        /** The panes that the layout of the tab shows. */
        @Volatile var layoutPaneIds: List<String> = paneIds
        val snapshotCalls = mutableListOf<RealHerdrSshClient.WorkerLane>()

        /** Each `pane.scroll` request that Herdr gets, in order. */
        val scrollRequests = Channel<ScrollCall>(Channel.UNLIMITED)
        private val scrollAnswers = Channel<Unit>(Channel.UNLIMITED)
        private val inputAnswers = Channel<Unit>(Channel.UNLIMITED)
        private val nextHold = AtomicReference<HeldPoll?>(null)

        /** Tells Herdr to answer the `pane.scroll` request that it holds. */
        fun answerScroll() {
            scrollAnswers.trySend(Unit)
        }

        /** Tells Herdr to answer the `pane.send_text` request that it holds. */
        fun answerInput() {
            inputAnswers.trySend(Unit)
        }

        /** Tells Herdr to hold the next poll batch of one kind. See [HeldPoll]. */
        fun holdNextPoll(beforeRead: Boolean = false, snapshot: Boolean = true): HeldPoll =
            HeldPoll(beforeRead, snapshot).also { nextHold.set(it) }

        /** Waits for the next `pane.scroll` request that Herdr gets. */
        suspend fun nextScrollCall(): ScrollCall = withTimeout(TIMEOUT_MS) { scrollRequests.receive() }

        /** Waits for the next `pane.scroll` request that Herdr gets, and returns its offset. */
        suspend fun nextScroll(): Int = nextScrollCall().offset

        suspend fun execute(
            lane: RealHerdrSshClient.WorkerLane,
            commands: List<RealHerdrSshClient.RemoteShellCommand>,
        ): String {
            val hasSnapshot = commands.any {
                it is RealHerdrSshClient.RemoteShellCommand.Exec && it.command.contains("herdr api snapshot")
            }
            val hold = nextHold.get()?.takeIf { held ->
                lane == RealHerdrSshClient.WorkerLane.Poll && held.snapshot == hasSnapshot &&
                    nextHold.compareAndSet(held, null)
            }
            if (hold?.beforeRead == true) hold.hold()
            val output = respond(lane, commands)
            if (hold?.beforeRead == false) hold.hold()
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
                            val paneId = paneIdOf(cmd.ndjsonLine)
                            val offset = Regex("\"offset_from_bottom\":(\\d+)").find(cmd.ndjsonLine)!!.groupValues[1].toInt()
                            scrollRequests.send(ScrollCall(paneId, offset))
                            if (holdScrolls) scrollAnswers.receive()
                            if (failScrolls) throw IOException("The channel closed")
                            offsets[paneId] = offset
                            appendLine("""{"id":"pane_scroll","result":{"type":"pane_info"}}""")
                        }
                        cmd.ndjsonLine.contains("\"pane.send_text\"") -> {
                            val text = Regex("\"text\":\"([^\"]*)\"").find(cmd.ndjsonLine)!!.groupValues[1]
                            if (holdInputs) inputAnswers.receive()
                            typed += text
                        }
                        cmd.ndjsonLine.contains("\"pane.read\"") -> {
                            val paneId = paneIdOf(cmd.ndjsonLine)
                            val text = "line at ${offsets.getValue(paneId)}$typed$newOutput"
                            appendLine("""{"id":"read","result":{"type":"pane_read","read":{"pane_id":"$paneId","text":"$text"}}}""")
                        }
                    }
                }
            }
        }

        private fun paneIdOf(ndjsonLine: String): String =
            Regex("\"pane_id\":\"([^\"]+)\"").find(ndjsonLine)!!.groupValues[1]

        fun snapshotJson(): String {
            val layoutPaneIds = layoutPaneIds
            val width = 80 / layoutPaneIds.size
            val panes = paneIds.joinToString(",") { id ->
                """{"pane_id":"$id","workspace_id":"w1","tab_id":"w1:t1","focused":${id == paneIds.first()},""" +
                    """"scroll":{"offset_from_bottom":${offsets.getValue(id)},"max_offset_from_bottom":263,"viewport_rows":40}}"""
            }
            val layoutPanes = layoutPaneIds.withIndex().joinToString(",") { (index, id) ->
                """{"pane_id":"$id","rect":{"x":${index * width},"y":0,"width":$width,"height":40},"focused":${index == 0}}"""
            }
            return """{"id":"snap","result":{"type":"session_snapshot","snapshot":{"focused_workspace_id":"w1","focused_tab_id":"w1:t1","focused_pane_id":"${paneIds.first()}",""" +
                """"workspaces":[{"workspace_id":"w1","active_tab_id":"w1:t1","label":"main","focused":true}],""" +
                """"tabs":[{"tab_id":"w1:t1","workspace_id":"w1","number":1,"label":"1","focused":true}],""" +
                """"panes":[$panes],"agents":[],""" +
                """"layouts":[{"workspace_id":"w1","tab_id":"w1:t1","zoomed":false,"panes":[$layoutPanes],"splits":[]}]}}}"""
        }
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
        debugLog: SshDebugLog = SshDebugLog(),
    ) = RealHerdrSshClient(
        localNetworkGuard = permittedLocalNetwork(),
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = surfaceBus,
        semanticNotificationBus = SemanticNotificationBus(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
        secretStore = InMemorySecretStore(),
        remoteBatchExecutor = { _, _, lane, commands -> fake.execute(lane, commands) },
        framePublisher = gate::publish,
    )

    private fun PaneSurfaceFrame.offset(paneId: String = "w1:p1"): Int =
        panes.single { it.paneId == paneId }.scroll.offsetFromBottom.toInt()

    private fun PaneSurfaceBus.offset(paneId: String = "w1:p1"): Int = assertNotNull(surfaceFor("box")).offset(paneId)

    /** Returns true when the frame shows [text] at the start of the first row of [paneId]. */
    private fun PaneSurfaceFrame.showsText(text: String, paneId: String = "w1:p1"): Boolean {
        val rect = panes.single { it.paneId == paneId }.rect
        return text.indices.all { col ->
            frame.cellAt(rect.x.toInt() + col, rect.y.toInt())?.symbol == text[col].toString()
        }
    }

    /** Waits for the frame that shows [text] at the start of the first row of [paneId]. */
    private suspend fun PaneSurfaceBus.awaitFrameWithText(text: String, paneId: String = "w1:p1"): PaneSurfaceFrame =
        withTimeout(TIMEOUT_MS) {
            surfaces.mapNotNull { it["box"] }.first { it.showsText(text, paneId) }
        }

    /**
     * Starts [step] on a thread of [Dispatchers.IO]. Returns when the step is done, when its thread waits for a
     * `synchronized` lock, or when [stopWhen] returns true. Thus a frame that the step publishes without a wait is on
     * the bus when this function returns. A test gives [stopWhen] when the step can suspend, for example in a poll
     * that Herdr holds.
     *
     * The client goes to [Dispatchers.IO] with `withContext`. On a thread of that dispatcher, `withContext` does not
     * change the thread, so the step stays on the thread until it suspends. The function reads the thread state
     * [Thread.State.BLOCKED], which only a thread that waits for a monitor has.
     */
    private fun CoroutineScope.launchUntilDoneOrBlocked(
        stopWhen: () -> Boolean = { false },
        step: suspend () -> Unit,
    ): Job {
        val started = CountDownLatch(1)
        val stepThread = AtomicReference<Thread>()
        val job = launch(Dispatchers.IO) {
            stepThread.set(Thread.currentThread())
            started.countDown()
            step()
        }
        assertTrue(started.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "The step must start")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        while (!job.isCompleted && stepThread.get().state != Thread.State.BLOCKED && !stopWhen()) {
            assertTrue(
                System.nanoTime() < deadline,
                "The step must end, or wait for a lock. This function sees only Thread.State.BLOCKED, the state of a " +
                    "thread that waits for a synchronized monitor. A lock of a different type gives a different state.",
            )
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
        assertEquals(0, frame.offset(), "The frame must stay at the bottom")
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
        assertEquals(30, frame.offset(), "The answer frame must show the request")

        // Herdr answered before this poll started, so the poll shows the position of Herdr.
        fake.serverOffset = 7
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(7, surfaceBus.offset(), "A poll after the answer must show the position of Herdr")
    }

    @Test
    fun eachPaneKeepsThePositionOfItsOwnRequest() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr(paneIds = listOf("w1:p1", "w1:p2"))
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 30))
        assertEquals(ScrollCall("w1:p1", 30), fake.nextScrollCall(), "The p1 request must go to Herdr")
        // Herdr holds the p1 request, so the p2 request stays in the queue.
        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p2", 5))

        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(30, surfaceBus.offset("w1:p1"), "A poll must keep the position of the p1 request in flight")
        assertEquals(5, surfaceBus.offset("w1:p2"), "A poll must keep the position of the queued p2 request")

        fake.answerScroll()
        assertEquals(ScrollCall("w1:p2", 5), fake.nextScrollCall(), "The p2 request must go to Herdr after p1")
        val p1Answer = surfaceBus.awaitFrameWithText("line at 30", "w1:p1")
        assertEquals(30, p1Answer.offset("w1:p1"), "The p1 answer frame must show the p1 request")
        assertEquals(5, p1Answer.offset("w1:p2"), "The p1 answer frame must keep the p2 request")

        fake.answerScroll()
        val p2Answer = surfaceBus.awaitFrameWithText("line at 5", "w1:p2")
        assertEquals(30, p2Answer.offset("w1:p1"), "The p2 answer frame must keep the p1 position")
        assertEquals(5, p2Answer.offset("w1:p2"), "The p2 answer frame must show the p2 request")
    }

    @Test
    fun aPollThatReadHerdrBeforeAScrollKeepsTheFrameOfTheScroll() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        // Herdr makes the output of the poll at the old position, and holds it.
        val held = fake.holdNextPoll()
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        held.awaitHeld()

        // Herdr moves the pane and answers the request before the poll output arrives.
        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 30))
        assertEquals(30, fake.nextScroll(), "The request must go to Herdr")
        fake.answerScroll()
        surfaceBus.awaitFrameWithText("line at 30")

        held.release()
        poll.join()
        val frame = assertNotNull(surfaceBus.surfaceFor("box"))
        assertEquals(30, frame.offset(), "The poll must not move the pane back")
        assertTrue(frame.showsText("line at 30"), "The poll must not show the pane text from before the scroll")
    }

    @Test
    fun aPollThatReadHerdrAfterAScrollShowsTheTextThatItRead() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        // The poll starts, and Herdr holds it before it reads its state.
        val held = fake.holdNextPoll(beforeRead = true)
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        held.awaitHeld()

        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 30))
        assertEquals(30, fake.nextScroll(), "The request must go to Herdr")
        fake.answerScroll()
        surfaceBus.awaitFrameWithText("line at 30")

        // The shell writes new output. Then Herdr reads its state for the poll, after the scroll.
        fake.newOutput = " NEW"
        held.release()
        poll.join()
        val frame = assertNotNull(surfaceBus.surfaceFor("box"))
        assertEquals(30, frame.offset(), "The poll must show the position of the scroll")
        assertTrue(frame.showsText("line at 30 NEW"), "The poll must show the newer text that it read")
    }

    @Test
    fun aPollDoesNotShowOlderTextAfterAFailedScrollRequest() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val debugLog = SshDebugLog()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus, debugLog = debugLog)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        // The shell writes new output. Herdr reads its state for the poll, and holds the output.
        fake.newOutput = " NEW"
        val held = fake.holdNextPoll()
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        held.awaitHeld()

        fake.failScrolls = true
        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p1", 30))
        assertEquals(30, fake.nextScroll(), "The request must go to Herdr")
        fake.answerScroll()
        withTimeout(TIMEOUT_MS) {
            debugLog.entries.first { entries -> entries.any { it.message.startsWith("pane.scroll for w1:p1 failed") } }
        }

        held.release()
        poll.join()
        val frame = assertNotNull(surfaceBus.surfaceFor("box"))
        assertEquals(12, frame.offset(), "After a failed request, a poll must show the position of Herdr")
        assertTrue(frame.showsText("line at 12 NEW"), "The poll must show the text that it read")
    }

    @Test
    fun aSecondBatchThatReadHerdrAfterAScrollShowsTheTextThatItRead() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr(paneIds = listOf("w1:p1", "w1:p2"))
        fake.layoutPaneIds = listOf("w1:p1")
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        // The layout now shows p2. The cache has no p2 text, so the poll reads p2 in a second batch. Herdr reads its
        // state for the first batch, and holds the output.
        fake.layoutPaneIds = listOf("w1:p1", "w1:p2")
        val held = fake.holdNextPoll()
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        held.awaitHeld()

        // Herdr moves p2 and answers before the second batch starts. The answer frame does not show p2 yet.
        client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneScroll("w1:p2", 30))
        val requestRevision = assertNotNull(surfaceBus.surfaceFor("box")).revision
        assertEquals(ScrollCall("w1:p2", 30), fake.nextScrollCall(), "The request must go to Herdr")
        fake.answerScroll()
        withTimeout(TIMEOUT_MS) { surfaceBus.surfaces.first { it.getValue("box").revision > requestRevision } }

        // The shell writes new output. Then the second batch reads p2, after the scroll.
        fake.newOutput = " NEW"
        held.release()
        poll.join()
        val frame = assertNotNull(surfaceBus.surfaceFor("box"))
        assertEquals(30, frame.offset("w1:p2"), "The poll must show the position of the scroll")
        assertTrue(frame.showsText("line at 30 NEW", "w1:p2"), "The poll must show the newer text of the second batch")
    }

    @Test
    fun aPollInFlightAtADisconnectPublishesNothing() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        val frameBefore = surfaceBus.surfaceFor("box")

        // Herdr reads its state for the poll, and holds the output. Then the user disconnects the machine.
        fake.newOutput = " NEW"
        val held = fake.holdNextPoll()
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        held.awaitHeld()
        client.disconnect(machine.id)

        held.release()
        poll.join()
        assertSame(frameBefore, surfaceBus.surfaceFor("box"), "A poll that ends after a disconnect must not publish")
    }

    @Test
    fun anOlderPollDoesNotReplaceTheFrameOfANewerPoll() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus)

        // The first poll has no cache, so it reads the pane in a second batch. Herdr holds the output of that batch.
        val held = fake.holdNextPoll(snapshot = false)
        val olderPoll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        held.awaitHeld()

        // The shell writes new output, and a newer poll reads and publishes it.
        fake.newOutput = " NEW"
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertTrue(
            assertNotNull(surfaceBus.surfaceFor("box")).showsText("line at 12 NEW"),
            "The newer poll must publish",
        )

        held.release()
        olderPoll.join()
        assertTrue(
            assertNotNull(surfaceBus.surfaceFor("box")).showsText("line at 12 NEW"),
            "The older poll must not replace the frame of the newer poll",
        )
    }

    @Test
    fun aPollThatReadTheOldSelectionPublishesNothing() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val fake = FakeHerdr(paneIds = listOf("w1:p1", "w1:p2"))
        val client = client(fake, surfaceBus)

        // The first poll parses the snapshot with p1 focused, and then reads the panes in a second batch. Herdr holds
        // the output of that batch.
        val heldReads = fake.holdNextPoll(snapshot = false)
        val olderPoll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        heldReads.awaitHeld()

        // The user focuses p2. Herdr holds the poll that follows the focus request, before it reads its state.
        val heldRefresh = fake.holdNextPoll(beforeRead = true)
        val focus = launch(Dispatchers.IO) {
            client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneFocus("w1:p2"))
        }
        heldRefresh.awaitHeld()

        heldReads.release()
        olderPoll.join()
        assertNull(surfaceBus.surfaceFor("box"), "The poll that read the old selection must not publish a frame")

        heldRefresh.release()
        focus.join()
        val frame = assertNotNull(surfaceBus.surfaceFor("box"))
        assertEquals("w1:p2", frame.panes.single { it.focused }.paneId, "The frame must show the new selection")
    }

    @Test
    fun aPollFrameDoesNotReplaceTheFrameOfANewerFocus() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val gate = FrameGate(surfaceBus)
        val fake = FakeHerdr(paneIds = listOf("w1:p1", "w1:p2"))
        val client = client(fake, surfaceBus, gate)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        // The poll builds its frame with p1 focused, and the gate holds the frame.
        gate.holdNextFrame()
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        gate.awaitHeldFrame()
        // The user focuses p2 before the poll frame goes to the bus. Herdr holds the poll that follows the focus
        // request, so that only the focus frame can show p2.
        val heldRefresh = fake.holdNextPoll(beforeRead = true)
        val focus = launchUntilDoneOrBlocked(stopWhen = { heldRefresh.isHeld }) {
            client.executeEndpointRpc(machine, route, HerdrRpcBuilder.paneFocus("w1:p2"))
        }
        gate.publishHeldFrame()
        poll.join()
        heldRefresh.awaitHeld()

        val frame = assertNotNull(surfaceBus.surfaceFor("box"))
        assertEquals("w1:p2", frame.panes.single { it.focused }.paneId, "The poll frame must not replace the focus")
        heldRefresh.release()
        focus.join()
    }

    @Test
    fun aPollFrameDoesNotReplaceTheFrameOfANewerViewportSize() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val gate = FrameGate(surfaceBus)
        val fake = FakeHerdr()
        val client = client(fake, surfaceBus, gate)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        // The poll builds its frame at the size of the layout, and the gate holds the frame.
        gate.holdNextFrame()
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        gate.awaitHeldFrame()
        // The view changes its size before the poll frame goes to the bus.
        val resize = launchUntilDoneOrBlocked { client.resizeViewport("box", 100u, 50u) }
        gate.publishHeldFrame()
        poll.join()
        resize.join()

        val frame = assertNotNull(surfaceBus.surfaceFor("box")).frame
        assertEquals(100 to 50, frame.width.toInt() to frame.height.toInt(), "The poll frame must not replace the size")
    }

    @Test
    fun theEchoShowsTheTypedTextOneTime() = runBlocking {
        val surfaceBus = PaneSurfaceBus()
        val gate = FrameGate(surfaceBus)
        val fake = FakeHerdr(holdInputs = true)
        // The pane is at the bottom, so the input requests no scroll. Thus the echo is the first step of the input
        // that waits for the frame lock.
        fake.serverOffset = 0
        val client = client(fake, surfaceBus, gate)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))

        // The poll builds its frame with no edit, and the gate holds the frame.
        gate.holdNextFrame()
        val poll = launch(Dispatchers.IO) { client.refreshSnapshotAndSurface(machine, route) }
        gate.awaitHeldFrame()
        // The user types before the poll frame goes to the bus.
        val typing = launchUntilDoneOrBlocked {
            client.sendPaneInput(machine, route, "w1:p1", ClientPaneInputEvent.TextCommit("x"))
        }
        gate.publishHeldFrame()
        poll.join()
        typing.join()

        val cells = assertNotNull(surfaceBus.surfaceFor("box")).frame.cells
        assertEquals(1, cells.count { it.isPredictedEcho && it.symbol == "x" }, "The frame must show the echo one time")
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
        assertEquals(263, frame.offset(), "The frame must stay at the top")
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
