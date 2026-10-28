package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.service.ServiceStopPolicy.Companion.isConnectStep
import com.toxicbakery.app.bison.ssh.LocalNetworkAccess
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshStateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Runs [dispatch] for [request]. Returns the error of [dispatch], or null when it completes.
 *
 * A [SshRequest.Connect] that throws or that is cancelled can leave the progress of its machine
 * at a connect step. [ServiceStopPolicy] counts a connect step as busy, so the service then does
 * not stop. Thus this function sets the progress to [ConnectionProgress.Failed] when the machine
 * is still at a connect step.
 *
 * When the coroutine of the caller is cancelled, this function then throws the [CancellationException] again.
 * A [CancellationException] from inside the request, for example a `TimeoutCancellationException`, is a failed
 * request. This function returns it, and the loop of the caller continues.
 */
internal suspend fun SshStateManager.runControlRequest(
    request: SshRequest,
    dispatch: suspend (SshRequest) -> Unit,
): Throwable? {
    val error = runCatching { dispatch(request) }.exceptionOrNull() ?: return null
    markInterruptedConnectFailed(request)
    rethrowIfCancelled(error)
    return error
}

/**
 * Ends the user authentication wait and the keyboard-interactive wait of the machine of [request] at once when
 * [request] is a disconnect. Call it when the request arrives, before the control queue. A connect that waits for the
 * user can hold that queue for up to 90 s, so a disconnect in the queue cannot end that wait.
 *
 * For a connect, it keeps the cancel mark of the machine ([SshStateManager.markConnectRequest]). Thus a disconnect
 * while the connect waits in the queue also stops the security key login of that connect.
 */
internal fun SshStateManager.cancelUserAuthBeforeQueue(request: SshRequest) {
    if (request is SshRequest.Disconnect) cancelUserAuth(request.machineId)
    if (request is SshRequest.Connect) markConnectRequest(request.machineId)
}

/**
 * Ends the wait for the local network permission of the machine of [request] at once when [request] is a disconnect.
 * Call it when the request arrives, before the control queue. A connect that waits for the answer of the user can
 * hold that queue for up to 60 s.
 */
internal fun LocalNetworkAccess.cancelRequestBeforeQueue(request: SshRequest) {
    if (request is SshRequest.Disconnect) cancel(request.machineId)
}

/** Sets [ConnectionProgress.Failed] when [request] is a connect and its machine is at a connect step. */
internal fun SshStateManager.markInterruptedConnectFailed(request: SshRequest) {
    if (request !is SshRequest.Connect) return
    val step = connectionProgress.value[request.machineId] ?: return
    if (step.isConnectStep()) {
        setConnectionProgress(request.machineId, ConnectionProgress.Failed)
    }
}

/**
 * Throws [error] again when it is a [CancellationException] and the coroutine of the caller is cancelled.
 *
 * A [CancellationException] can also come from inside a request, for example a `TimeoutCancellationException` of a
 * `withTimeout` in the request. The coroutine of the caller then stays active, and the caller must handle the error
 * as a failed request. If the caller throws it again, the loop of the caller stops.
 */
internal suspend fun rethrowIfCancelled(error: Throwable) {
    if (error is CancellationException && !currentCoroutineContext().isActive) throw error
}
