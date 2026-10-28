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

    /** A stream that gives the lines of the test. */
    private class FakeStream(val requestLine: String) : AgentEventStream {
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

    /** The host and the client of the watch. */
    private class Harness {
        /** The agent panes that the client knows. A snapshot sets them to [hostPaneIds]. */
        @Volatile var knownPaneIds: Set<String>? = setOf("w1:p1")

        /** The agent panes of the next snapshot. */
        @Volatile var hostPaneIds: Set<String> = setOf("w1:p1")

        @Volatile var snapshots = 0

        /** The error of the next stream, or null. */
        @Volatile var openError: Exception? = null

        val streams = mutableListOf<FakeStream>()
        val events = mutableListOf<ClientShellAgent>()
        val errors = mutableListOf<String>()

        val watch = AgentStatusWatch(
            knownPaneIds = { knownPaneIds },
            readSnapshot = {
                snapshots++
                knownPaneIds = hostPaneIds
                hostPaneIds
            },
            openStream = { requestLine ->
                openError?.let { throw it }
                FakeStream(requestLine).also { streams += it }
            },
            onStatusEvent = { events += it },
            log = { message, isError -> if (isError) errors += message },
        )

        val lastStream: FakeStream
            get() = streams.last()
    }

    private fun TestScope.startWatch(harness: Harness) =
        backgroundScope.launch { harness.watch.run(awaitLive = { true }) }

    @Test
    fun theWatchSubscribesToTheKnownPanesAndGivesEachStatusEvent() = runTest {
        val harness = Harness().apply {
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

    @Test
    fun aNewAgentStartsANewSubscriptionWithTheNewPanes() = runTest {
        val harness = Harness()
        startWatch(harness)
        runCurrent()
        val first = harness.lastStream
        first.send(STARTED)
        runCurrent()

        harness.hostPaneIds = setOf("w1:p1", "w3:p2")
        first.send(AGENT_DETECTED)
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

        // A snapshot after the start with different panes also starts a new subscription at once.
        harness.hostPaneIds = setOf("w1:p1")
        harness.lastStream.send(AGENT_DETECTED)
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
        val harness = Harness()
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
        assertEquals(1, harness.snapshots)

        harness.lastStream.end()
        runCurrent()
        assertTrue(harness.errors.last().endsWith("The next attempt starts in 10 s."))
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(3, harness.streams.size)
    }

    @Test
    fun theEndOfAStartedStreamStartsTheDelaysAgain() = runTest {
        val harness = Harness()
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

        harness.openError = null
        advanceTimeBy(10.seconds)
        runCurrent()
        harness.lastStream.send(STARTED)
        runCurrent()
        harness.lastStream.end()
        runCurrent()
        assertTrue(harness.errors.last().endsWith("The next attempt starts in 5 s."))
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
        val harness = Harness()
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
    fun theWatchEndsWhenTheSessionDoesNotComeBack() = runTest {
        val harness = Harness()
        val job = backgroundScope.launch { harness.watch.run(awaitLive = { false }) }
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(harness.streams.isEmpty())
    }

    private companion object {
        const val STARTED = """{"id":"agent_status_watch","result":{"type":"subscription_started"}}"""
        const val PANE_NOT_FOUND =
            """{"id":"agent_status_watch","error":{"code":"pane_not_found","message":"no pane"}}"""
        const val AGENT_DETECTED =
            """{"data":{"pane_id":"w3:p2","workspace_id":"w3","agent":"claude"},"event":"pane.agent_detected"}"""

        fun statusEvent(paneId: String, status: String): String =
            """{"data":{"agent":"codex","agent_status":"$status","pane_id":"$paneId","workspace_id":"w1"},""" +
                """"event":"pane.agent_status_changed"}"""
    }
}
