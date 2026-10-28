package com.toxicbakery.app.bison.service

import kotlinx.coroutines.channels.Channel

/**
 * Keeps the network events of `ConnectivityManager.NetworkCallback` in order, so that [run] handles them one at a
 * time.
 *
 * The callback runs on a thread of the system. If each event starts its own coroutine, a check after `onAvailable`
 * and a socket close after `onLost` can run at the same time, in the wrong order.
 */
internal class NetworkEventQueue {

    /** A change of the default network. */
    enum class Event {
        Available,
        LinkPropertiesChanged,
        Lost,
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    /** Adds [event] to the queue. It does not wait. */
    fun send(event: Event) {
        events.trySend(event)
    }

    /**
     * Handles each event with [handle], one at a time and in order, until the coroutine is cancelled. When [handle]
     * fails, [onError] gets the error, and the next event follows.
     */
    suspend fun run(handle: suspend (Event) -> Unit, onError: (Event, Throwable) -> Unit) {
        for (event in events) {
            val error = runCatching { handle(event) }.exceptionOrNull() ?: continue
            rethrowIfCancelled(error)
            onError(event, error)
        }
    }
}
