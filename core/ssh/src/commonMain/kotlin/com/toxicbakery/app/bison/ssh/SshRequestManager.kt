package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
 * Shared event bus for dispatching [SshRequest] commands from KMP presentation code to the
 * active transport service without coupling `:core:common` to Android services or sockets.
 */
class SshRequestManager {
    private val _bus = MutableSharedFlow<SshRequest>(extraBufferCapacity = 64)
    val bus: SharedFlow<SshRequest> = _bus.asSharedFlow()

    suspend fun emit(request: SshRequest) {
        _bus.emit(request)
    }

    fun tryEmit(request: SshRequest): Boolean = _bus.tryEmit(request)
}
