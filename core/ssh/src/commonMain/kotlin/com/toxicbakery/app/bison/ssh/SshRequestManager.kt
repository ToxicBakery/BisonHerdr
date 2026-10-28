package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Cross-layer transport request commands emitted by ViewModels/Delegates and handled by
 * `BisonSessionService` / `SshTransportManager` (or `FakeHerdrServer` in Demo mode).
 */
sealed interface SshRequest {
    data class Connect(val machineId: String) : SshRequest

    data class Disconnect(val machineId: String) : SshRequest

    data class SetSurfaceLease(val machineId: String, val active: Boolean) : SshRequest

    data class SendEndpointCommand(
        val machineId: String,
        val request: ClientShellEndpointRequest,
    ) : SshRequest

    data class SendPaneInput(
        val machineId: String,
        val paneId: String,
        val event: ClientPaneInputEvent,
    ) : SshRequest

    data class SubmitAgentPrompt(
        val machineId: String,
        val paneId: String,
        val agentName: String?,
        val prompt: String,
    ) : SshRequest

    data class ResizeShell(
        val machineId: String,
        val cols: UShort,
        val rows: UShort,
        val cellWidthPx: Int = 18,
        val cellHeightPx: Int = 36,
    ) : SshRequest

    data class TriggerNetworkRoam(val machineId: String? = null) : SshRequest

    data class PromptHardwareKeyTouch(
        val machineId: String,
        val keyType: String,
        val promptMessage: String,
    ) : SshRequest
}

/**
 * Request queue that sends [SshRequest] commands from KMP presentation code to the active
 * transport service. `:core:common` does not get a dependency on Android services or sockets.
 *
 * A buffered [Channel] keeps each request until a collector receives it. Thus a request that the
 * app sends before `BisonSessionService` starts is not lost. The channel gives each request to
 * one collector only, so `BisonSessionService` must be the only collector in the app. The queue
 * has no replay: a new collector does not get a request that an earlier collector received.
 */
class SshRequestManager {
    private val requests = Channel<SshRequest>(capacity = REQUEST_CAPACITY)

    /** The requests in the order that [emit] and [tryEmit] sent them. Each request goes to one collector. */
    val bus: Flow<SshRequest> = requests.receiveAsFlow()

    /** Sends [request]. Suspends while [REQUEST_CAPACITY] requests wait for the collector. */
    suspend fun emit(request: SshRequest) {
        requests.send(request)
    }

    /** Sends [request] when the queue has space. Returns false when the queue is full. */
    fun tryEmit(request: SshRequest): Boolean = requests.trySend(request).isSuccess

    companion object {
        /** The maximum number of requests that wait for the collector. */
        const val REQUEST_CAPACITY: Int = 64
    }
}
