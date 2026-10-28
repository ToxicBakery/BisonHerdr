package com.toxicbakery.app.bison.service

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests that [NetworkEventQueue] handles the network events one at a time and in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkEventQueueTest {

    private val queue = NetworkEventQueue()

    @Test
    fun eachEventStartsAfterTheEventBeforeItEnds() = runTest {
        val log = mutableListOf<String>()
        backgroundScope.launch {
            queue.run(
                handle = { event ->
                    log += "start $event"
                    // A liveness check after onAvailable takes time.
                    if (event == NetworkEventQueue.Event.Available) delay(CHECK_MS)
                    log += "end $event"
                },
                onError = { _, error -> throw AssertionError(error) },
            )
        }

        queue.send(NetworkEventQueue.Event.Available)
        queue.send(NetworkEventQueue.Event.Lost)
        queue.send(NetworkEventQueue.Event.LinkPropertiesChanged)
        runCurrent()
        assertEquals(listOf("start Available"), log)

        advanceTimeBy(CHECK_MS)
        runCurrent()

        assertEquals(
            listOf(
                "start Available",
                "end Available",
                "start Lost",
                "end Lost",
                "start LinkPropertiesChanged",
                "end LinkPropertiesChanged",
            ),
            log,
        )
    }

    @Test
    fun anEventThatFailsDoesNotStopTheQueue() = runTest {
        val handled = mutableListOf<NetworkEventQueue.Event>()
        val errors = mutableListOf<NetworkEventQueue.Event>()
        backgroundScope.launch {
            queue.run(
                handle = { event ->
                    if (event == NetworkEventQueue.Event.Lost) throw IOException("The socket did not close")
                    handled += event
                },
                onError = { event, _ -> errors += event },
            )
        }

        queue.send(NetworkEventQueue.Event.Lost)
        queue.send(NetworkEventQueue.Event.Available)
        runCurrent()

        assertEquals(listOf(NetworkEventQueue.Event.Lost), errors)
        assertEquals(listOf(NetworkEventQueue.Event.Available), handled)
    }

    private companion object {
        const val CHECK_MS = 3_000L
    }
}
