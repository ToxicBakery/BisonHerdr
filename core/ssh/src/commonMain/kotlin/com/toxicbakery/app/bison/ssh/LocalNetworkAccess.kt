package com.toxicbakery.app.bison.ssh

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A request of the transport for the local network permission (`ACCESS_LOCAL_NETWORK`) of Android 17. [hostPort] is
 * the first host of the route that needs the permission: the bastion, or the target when the route has no bastion.
 *
 * Two requests are equal only when they are the same instance.
 */
class LocalNetworkRequest internal constructor(val hostPort: String) {
    /** The answer to this request. Only the first answer has an effect. */
    internal val answer = CompletableDeferred<LocalNetworkOutcome>()

    override fun toString(): String = "LocalNetworkRequest(hostPort=$hostPort)"
}

/** The result of a [LocalNetworkRequest]. */
enum class LocalNetworkOutcome {
    /** The app has the permission. */
    Granted,

    /** The user or the system refused the permission. */
    Denied,

    /** No UI shows requests, for example because the app is in the background. The app did not ask. */
    NoUi,

    /** The user did not answer before the time limit. */
    TimedOut,

    /** The UI stopped before the answer. */
    Cancelled,
}

/**
 * Sends the requests of the transport for the local network permission to the UI, and gives the answers back.
 *
 * Android shows a permission dialog only over an activity, so a service cannot ask. A connect from the user calls
 * [request] when the first host of the route is on the local network and the app does not have the permission. A UI
 * shows the request and gives the answer through [hostRequests]. With no UI, for example during a reconnect in the
 * background, [request] returns [LocalNetworkOutcome.NoUi] at once, and the connection stops until the user connects
 * again from the app.
 *
 * The permission is for the full app. Thus a request that comes while a different request is open gets the same
 * answer, and the UI shows one dialog.
 */
class LocalNetworkAccess {

    /** The number of callers that run [hostRequests] now. [request] asks the user only while it is more than zero. */
    private val hosts = MutableStateFlow(0)

    private val _openRequest = MutableStateFlow<LocalNetworkRequest?>(null)

    /** The request that waits for the answer of the user, or null. */
    val openRequest: StateFlow<LocalNetworkRequest?> = _openRequest.asStateFlow()

    /**
     * Asks the UI for the local network permission for [hostPort], and waits for the answer.
     *
     * - When no UI runs [hostRequests], it returns [LocalNetworkOutcome.NoUi] at once and puts no request.
     * - When a request is open, it waits for the answer to that request.
     * - When no answer comes in [timeout], it returns [LocalNetworkOutcome.TimedOut] and removes the request.
     */
    suspend fun request(hostPort: String, timeout: Duration): LocalNetworkOutcome {
        if (hosts.value == 0) return LocalNetworkOutcome.NoUi
        val created = LocalNetworkRequest(hostPort)
        val request = _openRequest.updateAndGet { open -> open ?: created } ?: created
        try {
            // The last UI can stop after the first check. Then no UI saw the request.
            if (hosts.value == 0) return LocalNetworkOutcome.NoUi
            return withTimeoutOrNull(timeout) { request.answer.await() } ?: LocalNetworkOutcome.TimedOut
        } finally {
            // A late answer from the UI has no effect.
            answer(request, LocalNetworkOutcome.TimedOut)
        }
    }

    /**
     * Shows each open request with [show], one at a time, and gives the result of [show] as the answer.
     *
     * A UI calls this function only while it can show a dialog, for example while its activity is started. The
     * function runs until the caller cancels it. When a request closes before [show] returns, for example after a
     * time-out, it cancels [show]. When the last caller stops, the open request gets [LocalNetworkOutcome.Cancelled].
     */
    suspend fun hostRequests(show: suspend (LocalNetworkRequest) -> LocalNetworkOutcome) {
        hosts.update { it + 1 }
        try {
            _openRequest.collectLatest { request ->
                if (request != null) answer(request, showSafely(request, show))
            }
        } finally {
            if (hosts.updateAndGet { it - 1 } == 0) {
                _openRequest.value?.let { answer(it, LocalNetworkOutcome.Cancelled) }
            }
        }
    }

    /** Returns the result of [show], or [LocalNetworkOutcome.Denied] when [show] throws an error. */
    private suspend fun showSafely(
        request: LocalNetworkRequest,
        show: suspend (LocalNetworkRequest) -> LocalNetworkOutcome,
    ): LocalNetworkOutcome =
        try {
            show(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LocalNetworkOutcome.Denied
        }

    /** Gives [outcome] as the answer to [request] when [request] has no answer, and removes [request]. */
    private fun answer(request: LocalNetworkRequest, outcome: LocalNetworkOutcome) {
        request.answer.complete(outcome)
        _openRequest.compareAndSet(request, null)
    }
}
