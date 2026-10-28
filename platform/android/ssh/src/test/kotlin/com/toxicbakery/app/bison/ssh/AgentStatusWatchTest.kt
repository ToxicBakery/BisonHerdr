package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Tests the subscriptions of [AgentStatusWatch] with fake streams and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentStatusWatchTest {

    /** A stream that gives the lines of the test. [errorLine] is the last line of its error output. */
    private class FakeStream(val requestLine: String, private val errorLine: String?) : AgentEventStream {
        private val lines = Channel<String>(Channel.UNLIMITED)

        @Volatile
        var closed = false
            private set

        /** The panes of the `pane.agent_status_changed` subscriptions of the request. */
        val paneIds: List<String> =
            Json.parseToJsonElement(requestLine).jsonObject.getValue("params").jsonObject
                .getValue("subscriptions").jsonArray
                .mapNotNull { it.jsonObject["pane_id"]?.jsonPrimitive?.content }

        override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()

        override fun errorText(): String? = errorLine

        override fun close() {
            closed = true
            lines.close()
        }

        fun send(line: String) {
            lines.trySend(line)
        }

        /** Ends the stream, as a closed channel does. */
        fun end() {
            lines.close()
        }
    }

    /** The host and the client of the watch. The watch measures its streams in the virtual time of [scope]. */
    private class Harness(scope: TestScope) {
        /** The agent panes that the client knows. A snapshot sets them to [hostPaneIds]. */
        @Volatile var knownPaneIds: Set<String>? = setOf("w1:p1")

        /** The agent panes of the next snapshot. */
        @Volatile var hostPaneIds: Set<String> = setOf("w1:p1")

        @Volatile var snapshots = 0

        /** The error of the next stream, or null. */
        @Volatile var openError: Exception? = null

        /** The last line of the error output of the next stream, or null. */
        @Volatile var errorLine: String? = null

        val streams = mutableListOf<FakeStream>()
        val events = mutableListOf<ClientShellAgent>()
        val errors = mutableListOf<String>()

        /** The steps of the watch in order: `snapshot` and `open`. */
        val steps = mutableListOf<String>()

        val watch = AgentStatusWatch(
            knownPaneIds = { knownPaneIds },
            readSnapshot = {
                snapshots++
                steps += "snapshot"
                knownPaneIds = hostPaneIds
                hostPaneIds
            },
            openStream = { requestLine ->
                steps += "open"
                openError?.let { throw it }
                FakeStream(requestLine, errorLine).also { streams += it }
            },
            onStatusEvent = { events += it },
            log = { message, isError -> if (isError) errors += message },
            timeSource = scope.testScheduler.timeSource,
        )

        val lastStream: FakeStream
            get() = streams.last()
    }

    private fun TestScope.startWatch(harness: Harness) =
        backgroundScope.launch { harness.watch.run(awaitLive = { true }) }

    @Test
    fun theWatchSubscribesToTheKnownPanesAndGivesEachStatusEvent() = runTest {
        val harness = Harness(this).apply {
            knownPaneIds = setOf("w1:p1", "w2:p1")
            hostPaneIds = setOf("w1:p1", "w2:p1")
        }
        startWatch(harness)
        runCurrent()

        val stream = harness.lastStream
        assertEquals(listOf("w1:p1", "w2:p1"), stream.paneIds)
        assertTrue("\"pane.agent_detected\"" in stream.requestLine)
        // The client knows the panes, so the watch reads its snapshot only after the start of the subscription.
        assertEquals(0, harness.snapshots)

        stream.send(STARTED)
        runCurrent()
        assertEquals(1, harness.snapshots)

        stream.send(statusEvent("w2:p1", "blocked"))
        stream.send("not json")
        stream.send(statusEvent("w1:p1", "done"))
        runCurrent()
        assertEquals(
            listOf("w2:p1" to AgentStatus.Blocked, "w1:p1" to AgentStatus.Done),
            harness.events.map { it.paneId to it.agentStatus },
        )
        assertEquals("codex", harness.events.first().agent)
        assertEquals(1, harness.streams.size)
        assertTrue(harness.errors.isEmpty())
    }

    /** The lines of this test are copies of the output of Herdr 0.9.5 for a subscription of the watch. */
    @Test
    fun theLinesOfRealHerdrGiveTheStatusAndANewSubscriptionForANewAgent() = runTest {
        val harness = Harness(this).apply {
            knownPaneIds = setOf("w6:p1")
            hostPaneIds = setOf("w6:p1")
        }
        startWatch(harness)
        runCurrent()
        val first = harness.lastStream
        first.send(HERDR_STARTED)
        // Herdr also writes the event for an agent that it finds again in a pane of the subscription.
        first.send(HERDR_DETECTED_W6_P1)
        first.send(HERDR_STATUS_WORKING_W6_P1)
        first.send(HERDR_STATUS_BLOCKED_W6_P1)
        runCurrent()
        assertFalse(first.closed)
        assertEquals(
            listOf("w6:p1" to AgentStatus.Working, "w6:p1" to AgentStatus.Blocked),
            harness.events.map { it.paneId to it.agentStatus },
        )

        // An agent in a new pane starts a new subscription with the panes of a new snapshot.
        harness.hostPaneIds = setOf("w6:p1", "w6:p2")
        first.send(HERDR_DETECTED_W6_P2)
        runCurrent()
        assertTrue(first.closed)
        advanceTimeBy(AgentStatusWatch.RESUBSCRIBE_DELAY)
        runCurrent()
        assertEquals(listOf("w6:p1", "w6:p2"), harness.lastStream.paneIds)
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun aStoppedAgentStartsANewSubscription() = runTest {
        val harness = Harness(this).apply {
            knownPaneIds = setOf("w6:p2")
            hostPaneIds = setOf("w6:p2")
        }
        startWatch(harness)
        runCurrent()
        val first = harness.lastStream
        first.send(STARTED)
        runCurrent()

        // A copy of the output of Herdr 0.9.5 after `pane.release_agent`.
        harness.hostPaneIds = emptySet()
        first.send(HERDR_RELEASED_W6_P2)
        advanceTimeBy(AgentStatusWatch.RESUBSCRIBE_DELAY)
        runCurrent()
        assertTrue(first.closed)
        assertEquals(emptyList(), harness.lastStream.paneIds)
    }

    @Test
    fun aNewAgentStartsANewSubscriptionWithTheNewPanes() = runTest {
        val harness = Harness(this)
        startWatch(harness)
        runCurrent()
        val first = harness.lastStream
        first.send(STARTED)
        runCurrent()

        harness.hostPaneIds = setOf("w1:p1", "w3:p2")
        first.send(agentDetected("w3:p2"))
        runCurrent()
        assertTrue(first.closed)

        // After a short delay, the watch reads a snapshot first, so the new request has the new panes.
        advanceTimeBy(AgentStatusWatch.RESUBSCRIBE_DELAY - 1.milliseconds)
        runCurrent()
        assertEquals(1, harness.streams.size)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(2, harness.snapshots)
        val second = harness.lastStream
        assertEquals(listOf("w1:p1", "w3:p2"), second.paneIds)
        second.send(STARTED)
        runCurrent()
        assertEquals(3, harness.snapshots)
        assertFalse(second.closed)

        // A snapshot after the start with different panes also starts a new subscription.
        harness.hostPaneIds = setOf("w1:p1")
        harness.lastStream.send(agentDetected("w4:p1"))
        advanceTimeBy(AgentStatusWatch.RESUBSCRIBE_DELAY)
        runCurrent()
        harness.hostPaneIds = setOf("w3:p2")
        harness.lastStream.send(STARTED)
        advanceTimeBy(AgentStatusWatch.RESUBSCRIBE_DELAY)
        runCurrent()
        assertEquals(listOf("w3:p2"), harness.lastStream.paneIds)
        assertEquals(4, harness.streams.size)
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun aFailedRequestReadsASnapshotFirstAndWaitsLongerAfterEachFailure() = runTest {
        val harness = Harness(this)
        startWatch(harness)
        runCurrent()

        // A pane of the request is closed, so Herdr refuses the subscription.
        harness.lastStream.send(PANE_NOT_FOUND)
        runCurrent()
        assertTrue(harness.lastStream.closed)
        assertEquals(
            listOf("The agent watch stopped: pane_not_found: no pane. The next attempt starts in 5 s."),
            harness.errors,
        )

        advanceTimeBy(5.seconds - 1.milliseconds)
        runCurrent()
        assertEquals(1, harness.streams.size)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(2, harness.streams.size)
        // The new request uses the panes of a new snapshot.
        assertEquals(listOf("open", "snapshot", "open"), harness.steps)

        harness.lastStream.end()
        runCurrent()
        assertTrue(harness.errors.last().endsWith("The next attempt starts in 10 s."))
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(3, harness.streams.size)
    }

    @Test
    fun theEndOfAStartedStreamReadsASnapshotBeforeTheNextRequest() = runTest {
        val harness = Harness(this)
        startWatch(harness)
        runCurrent()
        harness.lastStream.send(STARTED)
        runCurrent()
        assertEquals(listOf("open", "snapshot"), harness.steps)

        // The agent pane closes, and then the stream ends. The next request must not name the closed pane.
        harness.hostPaneIds = setOf("w2:p1")
        harness.lastStream.end()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(listOf("open", "snapshot", "snapshot", "open"), harness.steps)
        assertEquals(listOf("w2:p1"), harness.lastStream.paneIds)
    }

    @Test
    fun onlyAStreamThatStaysOpenStartsTheDelaysAgain() = runTest {
        val harness = Harness(this)
        harness.openError = IOException("The session is closed")
        startWatch(harness)
        runCurrent()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(
            listOf(
                "The agent watch stopped: The session is closed. The next attempt starts in 5 s.",
                "The agent watch stopped: The session is closed. The next attempt starts in 10 s.",
            ),
            harness.errors,
        )

        // A stream that ends soon after its start is one more failure.
        harness.openError = null
        advanceTimeBy(10.seconds)
        runCurrent()
        harness.lastStream.send(STARTED)
        runCurrent()
        advanceTimeBy(AgentStatusWatch.STABLE_STREAM_TIME - 1.seconds)
        harness.lastStream.end()
        runCurrent()
        assertTrue(harness.errors.last().endsWith("The next attempt starts in 20 s."), harness.errors.last())

        // A stream that stays open for the stable time starts the delays again.
        advanceTimeBy(20.seconds)
        runCurrent()
        harness.lastStream.send(STARTED)
        runCurrent()
        advanceTimeBy(AgentStatusWatch.STABLE_STREAM_TIME)
        harness.lastStream.end()
        runCurrent()
        assertTrue(harness.errors.last().endsWith("The next attempt starts in 5 s."), harness.errors.last())
    }

    @Test
    fun theErrorOutputOfTheBridgeTellsTheCause() = runTest {
        val harness = Harness(this).apply { errorLine = "Error: no Herdr session named \"work\"" }
        startWatch(harness)
        runCurrent()
        harness.lastStream.end()
        runCurrent()
        assertEquals(
            listOf(
                "The agent watch stopped: Error: no Herdr session named \"work\". The next attempt starts in 5 s.",
            ),
            harness.errors,
        )
    }

    @Test
    fun theDelaysStopAtTheMaximum() {
        assertEquals(5.seconds, AgentStatusWatch.defaultRetryDelay(1))
        assertEquals(10.seconds, AgentStatusWatch.defaultRetryDelay(2))
        assertEquals(160.seconds, AgentStatusWatch.defaultRetryDelay(6))
        assertEquals(5.minutes, AgentStatusWatch.defaultRetryDelay(7))
        assertEquals(5.minutes, AgentStatusWatch.defaultRetryDelay(Int.MAX_VALUE))
    }

    @Test
    fun aCancelClosesTheStream() = runTest {
        val harness = Harness(this)
        val job = startWatch(harness)
        runCurrent()
        harness.lastStream.send(STARTED)
        runCurrent()
        assertFalse(harness.lastStream.closed)

        job.cancel()
        runCurrent()
        assertTrue(harness.lastStream.closed)
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun aCancelDuringTheOpenClosesTheNewResource() = runTest {
        val closed = mutableListOf<String>()
        val job = backgroundScope.launch {
            openNonCancellable(
                open = {
                    delay(100.milliseconds)
                    "channel"
                },
                close = { closed += it },
            )
            closed += "not cancelled"
        }
        advanceTimeBy(50.milliseconds)
        job.cancel()
        runCurrent()
        // The open continues to its end, and then the resource closes.
        assertEquals(emptyList(), closed)
        advanceTimeBy(50.milliseconds)
        runCurrent()
        assertEquals(listOf("channel"), closed)
        assertTrue(job.isCancelled)

        // With no cancel, the caller gets the resource, and nothing closes it.
        assertEquals("open", openNonCancellable(open = { "open" }, close = { closed += it }))
        assertEquals(listOf("channel"), closed)
    }

    @Test
    fun theWatchEndsWhenTheSessionDoesNotComeBack() = runTest {
        val harness = Harness(this)
        val job = backgroundScope.launch { harness.watch.run(awaitLive = { false }) }
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(harness.streams.isEmpty())
    }

    private companion object {
        const val STARTED = """{"id":"agent_status_watch","result":{"type":"subscription_started"}}"""
        const val PANE_NOT_FOUND =
            """{"id":"agent_status_watch","error":{"code":"pane_not_found","message":"no pane"}}"""

        /** The output of Herdr 0.9.5 for the subscription of `w6:p1`, line by line. */
        const val HERDR_STARTED = """{"id":"agent_status_watch","result":{"type":"subscription_started"}}"""
        const val HERDR_DETECTED_W6_P1 =
            """{"data":{"agent":"claude","pane_id":"w6:p1","type":"pane_agent_detected","workspace_id":"w6"},""" +
                """"event":"pane_agent_detected"}"""
        const val HERDR_STATUS_WORKING_W6_P1 =
            """{"data":{"agent":"claude","agent_status":"working","pane_id":"w6:p1","workspace_id":"w6"},""" +
                """"event":"pane.agent_status_changed"}"""
        const val HERDR_STATUS_BLOCKED_W6_P1 =
            """{"data":{"agent":"claude","agent_status":"blocked","pane_id":"w6:p1","workspace_id":"w6"},""" +
                """"event":"pane.agent_status_changed"}"""
        const val HERDR_DETECTED_W6_P2 =
            """{"data":{"agent":"codex","pane_id":"w6:p2","type":"pane_agent_detected","workspace_id":"w6"},""" +
                """"event":"pane_agent_detected"}"""

        const val HERDR_RELEASED_W6_P2 =
            """{"data":{"agent":"codex","final_status":"unknown","pane_id":"w6:p2","released":true,""" +
                """"type":"pane_agent_detected","workspace_id":"w6"},"event":"pane_agent_detected"}"""

        fun agentDetected(paneId: String): String =
            """{"data":{"agent":"claude","pane_id":"$paneId","type":"pane_agent_detected","workspace_id":"w1"},""" +
                """"event":"pane_agent_detected"}"""

        fun statusEvent(paneId: String, status: String): String =
            """{"data":{"agent":"codex","agent_status":"$status","pane_id":"$paneId","workspace_id":"w1"},""" +
                """"event":"pane.agent_status_changed"}"""
    }
}
