package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The lines of one `herdr remote-api-bridge` process on a host. See [AgentStatusWatch]. */
internal interface AgentEventStream {
    /** Waits for the next line of the host. Returns null when the stream ended. */
    suspend fun readLine(): String?

    /** Closes the stream. A [readLine] that waits then returns null or throws. */
    fun close()
}

/**
 * Watches the coding agents of one machine while the surface lease is off (issue #53).
 *
 * While the lease is off, the control poll of [RealHerdrSshClient] reads nothing, and it runs [run]. One
 * subscription has these steps:
 * 1. The watch opens a stream with an `events.subscribe` request ([openStream]). The request names the agent panes
 *    that the client knows ([knownPaneIds]). After a failed request, the watch reads a snapshot first, because a
 *    pane of the request can be closed.
 * 2. Herdr answers `subscription_started`. Then the watch reads one snapshot ([readSnapshot]). Thus a status change
 *    between the last snapshot and the start of the subscription also gives its alert.
 * 3. The watch gives each `pane.agent_status_changed` event to [onStatusEvent].
 * 4. A `pane.agent_detected` event, or a snapshot with different agent panes, starts a new subscription, because
 *    Herdr needs one subscription for each pane. After a `pane.agent_detected` event, the watch reads a snapshot
 *    first, so that the new request has the new panes. The new subscription starts after [RESUBSCRIBE_DELAY], so
 *    that an agent that starts and stops again and again cannot cause a fast loop of requests.
 *
 * Herdr ends the stream when the bridge reads more input or the end of its input, so the stream keeps its input
 * open. While no agent changes, the stream sends no data. When a step fails or the stream ends, [run] waits for the
 * delay from [retryDelay] and starts a new subscription. [run] ends when its coroutine is cancelled or when
 * `awaitLive` returns false.
 */
internal class AgentStatusWatch(
    /** Returns the agent panes of the last snapshot of the machine, or null when the client has no snapshot. */
    private val knownPaneIds: () -> Set<String>?,
    /** Reads a snapshot, records its agents, and returns its agent panes. Returns null when Herdr gave no snapshot. */
    private val readSnapshot: suspend () -> Set<String>?,
    /** Opens a stream that sends the request line to `herdr remote-api-bridge`. */
    private val openStream: suspend (requestLine: String) -> AgentEventStream,
    /** Records the status event of one agent. */
    private val onStatusEvent: (ClientShellAgent) -> Unit,
    /** Writes a message to the debug log of the machine. */
    private val log: (message: String, isError: Boolean) -> Unit,
    /** Gives the delay after a number of failed subscriptions in sequence. */
    private val retryDelay: (failures: Int) -> Duration = ::defaultRetryDelay,
) {
    /** How one subscription ended. */
    private sealed interface Outcome {
        /**
         * The agent panes changed. Start a new subscription at once. When [snapshotFirst] is true, the client does not
         * know the new panes yet.
         */
        data class Resubscribe(val snapshotFirst: Boolean) : Outcome

        /** A step failed, or the stream ended. [started] is true when Herdr started the subscription. */
        data class Failed(val cause: String, val started: Boolean) : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Runs subscriptions until the coroutine is cancelled. Before each subscription, it waits for [awaitLive], and
     * it ends when [awaitLive] returns false.
     */
    suspend fun run(awaitLive: suspend () -> Boolean) {
        var failures = 0
        var snapshotFirst = false
        while (currentCoroutineContext().isActive && awaitLive()) {
            when (val outcome = runSubscription(snapshotFirst)) {
                is Outcome.Resubscribe -> {
                    failures = 0
                    snapshotFirst = outcome.snapshotFirst
                    delay(RESUBSCRIBE_DELAY)
                }

                is Outcome.Failed -> {
                    failures = if (outcome.started) 1 else failures + 1
                    snapshotFirst = !outcome.started
                    val nextDelay = retryDelay(failures)
                    log(
                        "The agent watch stopped: ${outcome.cause}. The next attempt starts in " +
                            "${nextDelay.inWholeSeconds} s.",
                        true,
                    )
                    delay(nextDelay)
                }
            }
        }
    }

    /** Runs one subscription. A [CancellationException] goes up. Each other error is a failed subscription. */
    private suspend fun runSubscription(snapshotFirst: Boolean): Outcome {
        val paneIds = try {
            (if (snapshotFirst) null else knownPaneIds()) ?: readSnapshot()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failure(error, started = false)
        } ?: return Outcome.Failed("Herdr gave no snapshot", started = false)

        val request = HerdrRpcBuilder.toNdjsonLine(HerdrRpcBuilder.agentStatusSubscribe(paneIds.sorted())).trim()
        val stream = try {
            openStream(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failure(error, started = false)
        }
        var started = false
        return try {
            val answer = readObject(stream)
                ?: return Outcome.Failed("The stream ended before Herdr answered", started = false)
            if (!isSubscriptionStarted(answer)) {
                return Outcome.Failed(errorOf(answer) ?: "Herdr did not start the subscription", started = false)
            }
            started = true
            log("The agent watch subscribed to ${paneIds.size} agent panes", false)
            val snapshotPaneIds = readSnapshot()
                ?: return Outcome.Failed("Herdr gave no snapshot", started = true)
            if (snapshotPaneIds != paneIds) return Outcome.Resubscribe(snapshotFirst = false)
            readEvents(stream)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure(error, started)
        } finally {
            stream.close()
        }
    }

    /** Reads the events of a started subscription until the agent panes change or the stream ends. */
    private suspend fun readEvents(stream: AgentEventStream): Outcome {
        while (true) {
            val obj = readObject(stream) ?: return Outcome.Failed("The stream ended", started = true)
            when (obj.text("event")) {
                STATUS_EVENT -> parseStatusEvent(obj)?.let(onStatusEvent)
                AGENT_DETECTED_EVENT -> return Outcome.Resubscribe(snapshotFirst = true)
                else -> errorOf(obj)?.let { return Outcome.Failed(it, started = true) }
            }
        }
    }

    /** Returns the next JSON object of [stream]. It skips the lines that are not JSON. Returns null at the end. */
    private suspend fun readObject(stream: AgentEventStream): JsonObject? {
        while (true) {
            val line = stream.readLine() ?: return null
            parseLine(line)?.let { return it }
        }
    }

    private fun parseLine(line: String): JsonObject? =
        line.trim().takeIf { it.startsWith("{") }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun isSubscriptionStarted(answer: JsonObject): Boolean =
        (answer["result"] as? JsonObject)?.text("type") == SUBSCRIPTION_STARTED

    private fun parseStatusEvent(obj: JsonObject): ClientShellAgent? {
        val data = obj["data"] as? JsonObject ?: return null
        val paneId = data.text("pane_id")?.takeIf { it.isNotBlank() } ?: return null
        return ClientShellAgent(
            paneId = paneId,
            workspaceId = data.text("workspace_id").orEmpty(),
            displayAgent = data.text("display_agent").orEmpty(),
            agent = data.text("agent").orEmpty(),
            title = data.text("title"),
            agentStatus = AgentStatus.fromWireName(data.text("agent_status")),
        )
    }

    /** Returns the code and the message of a Herdr error answer, or null when [obj] is not an error. */
    private fun errorOf(obj: JsonObject): String? {
        val error = obj["error"] as? JsonObject ?: return null
        return listOfNotNull(error.text("code"), error.text("message")).joinToString(": ").ifBlank { "error" }
    }

    /**
     * Returns the failed subscription of [error]. A cancel can close the stream while a read waits, and the read
     * then throws an [java.io.IOException]. Thus this function first throws a [CancellationException] when the
     * coroutine is cancelled.
     */
    private suspend fun failure(error: Exception, started: Boolean): Outcome.Failed {
        currentCoroutineContext().ensureActive()
        return Outcome.Failed(causeOf(error), started)
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun causeOf(error: Exception): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "error"

    companion object {
        /** Herdr writes this result type when the subscription starts. */
        const val SUBSCRIPTION_STARTED: String = "subscription_started"

        /** The event of a status change of one pane. */
        const val STATUS_EVENT: String = "pane.agent_status_changed"

        /** The event of an agent that started or stopped in a pane. */
        const val AGENT_DETECTED_EVENT: String = "pane.agent_detected"

        /** The delay before a new subscription after a change of the agent panes. */
        val RESUBSCRIBE_DELAY: Duration = 1.seconds

        /** The delay after the first failed subscription. Each failure in sequence doubles it. */
        val FIRST_RETRY_DELAY: Duration = 5.seconds

        /** The maximum delay between two subscriptions. */
        val MAX_RETRY_DELAY: Duration = 5.minutes

        /**
         * Returns [FIRST_RETRY_DELAY] for one failure. Each failure after it doubles the delay, to [MAX_RETRY_DELAY].
         */
        fun defaultRetryDelay(failures: Int): Duration {
            var delay = FIRST_RETRY_DELAY
            repeat((failures - 1).coerceIn(0, MAX_DOUBLINGS)) { delay *= 2 }
            return delay.coerceAtMost(MAX_RETRY_DELAY)
        }

        private const val MAX_DOUBLINGS = 16
    }
}
