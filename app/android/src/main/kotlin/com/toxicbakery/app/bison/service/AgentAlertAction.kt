package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.ssh.PasteThenEnter
import com.toxicbakery.app.bison.ssh.SshTransportManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * An action of an agent alert of [BisonSessionService] (issue #53), and the Herdr requests that it sends.
 *
 * Each action goes to the SSH control lane of the machine as Herdr requests. It does not use the input queue of the
 * terminal, so it does not need the surface lease, and the service gets the answer of Herdr. Thus the service can
 * remove the alert after a success, and show the cause after a failure. The Mosh plan (decision 5) also sends the
 * input of a notification over SSH `pane.send_keys`. Each request names the pane, not the agent, so it also works for
 * an agent with no Herdr name.
 */
internal sealed interface AgentAlertAction {
    /** The pane of the agent. */
    val paneId: String

    /** Sends the requests of the action through [transport] in order, and returns the result. */
    suspend fun perform(transport: suspend (ClientShellEndpointRequest) -> String?): Result

    /** Sends `Enter`, for example to accept a permission prompt. */
    data class Approve(override val paneId: String) : AgentAlertAction {
        val request: ClientShellEndpointRequest =
            HerdrRpcBuilder.paneSendKeys(paneId = paneId, keys = listOf("Enter"), requestId = "agent_alert_approve")

        override suspend fun perform(transport: suspend (ClientShellEndpointRequest) -> String?): Result =
            resultOf(request, transport(request))
    }

    /** Sends `Esc`, for example to refuse a permission prompt. */
    data class Reject(override val paneId: String) : AgentAlertAction {
        val request: ClientShellEndpointRequest =
            HerdrRpcBuilder.paneSendKeys(paneId = paneId, keys = listOf("Esc"), requestId = "agent_alert_reject")

        override suspend fun perform(transport: suspend (ClientShellEndpointRequest) -> String?): Result =
            resultOf(request, transport(request))
    }

    /**
     * Sends [text], and then `Enter` in a second request ([PasteThenEnter]). With one request, Claude Code keeps the
     * text in its composer (Herdr issue 1878).
     */
    data class Reply(override val paneId: String, val text: String) : AgentAlertAction {
        override suspend fun perform(transport: suspend (ClientShellEndpointRequest) -> String?): Result {
            val steps = PasteThenEnter.send(
                paneId = paneId,
                text = text,
                requestId = "agent_alert_reply",
                transport = transport,
                accepted = { request, response -> resultOf(request, response) == Result.Sent },
            )
            val last = steps.last()
            return resultOf(last.request, last.response)
        }
    }

    /** The result of an action. */
    sealed interface Result {
        /** Herdr ran the request. */
        data object Sent : Result

        /**
         * The request failed. [cause] is the message of the error, or null when the machine sent no answer. The
         * service shows its own text for null.
         */
        data class Failed(val cause: String?) : Result
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Returns the result of [request] from the [response] of the transport.
         *
         * The response can have more than one line, for example the answer of a `pane.send_keys` request and the
         * answer of the `pane.read` request after it. The answer with the id of [request] decides. When no line has
         * that id, the first answer decides, because the demo server sends answers with no id. A response with no
         * answer is a failure with no cause. The error of the transport for no response
         * ([SshTransportManager.NO_RESPONSE_CODE]) is also a failure with no cause.
         */
        fun resultOf(request: ClientShellEndpointRequest, response: String?): Result {
            val answers = response.orEmpty().lineSequence().mapNotNull(::parseAnswer).toList()
            val answer = answers.firstOrNull { it.text("id") == request.id }
                ?: answers.firstOrNull()
                ?: return Result.Failed(cause = null)
            val error = answer["error"] as? JsonObject ?: return Result.Sent
            if (error.text("code") == SshTransportManager.NO_RESPONSE_CODE) return Result.Failed(cause = null)
            return Result.Failed(cause = error.text("message") ?: error.text("code"))
        }

        /** Returns the JSON object of [line] when it has a `result` or an `error`, or null. */
        private fun parseAnswer(line: String): JsonObject? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) return null
            val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
            return obj.takeIf { "result" in it || "error" in it }
        }

        private fun JsonObject.text(name: String): String? =
            (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
