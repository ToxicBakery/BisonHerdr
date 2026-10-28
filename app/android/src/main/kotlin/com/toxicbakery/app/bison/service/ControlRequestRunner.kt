package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.service.ServiceStopPolicy.Companion.isConnectStep
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshStateManager
import kotlinx.coroutines.CancellationException

/**
 * Runs [dispatch] for [request]. Returns the error of [dispatch], or null when it completes.
 *
 * A [SshRequest.Connect] that throws or that is cancelled can leave the progress of its machine
 * at a connect step. [ServiceStopPolicy] counts a connect step as busy, so the service then does
 * not stop. Thus this function sets the progress to [ConnectionProgress.Failed] when the machine
 * is still at a connect step. It then throws a [CancellationException] again.
 */
internal suspend fun SshStateManager.runControlRequest(
    request: SshRequest,
    dispatch: suspend (SshRequest) -> Unit,
): Throwable? {
    val error = runCatching { dispatch(request) }.exceptionOrNull() ?: return null
    markInterruptedConnectFailed(request)
    if (error is CancellationException) throw error
    return error
}

/** Sets [ConnectionProgress.Failed] when [request] is a connect and its machine is at a connect step. */
internal fun SshStateManager.markInterruptedConnectFailed(request: SshRequest) {
    if (request !is SshRequest.Connect) return
    val step = connectionProgress.value[request.machineId] ?: return
    if (step.isConnectStep()) {
        setConnectionProgress(request.machineId, ConnectionProgress.Failed)
    }
}
