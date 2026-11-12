package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Sends a prompt to a pane in two Herdr requests: the text, and then `Enter` (issue #68).
 *
 * Herdr writes the text and the keys of one `pane.send_input` request in one write. Claude Code then reads the `Enter`
 * as a part of the pasted text, and keeps the text in its composer (Herdr issue 1878). Thus [send] sends the text with
 * `pane.send_input` and no keys. When Herdr accepts the text, it waits [ENTER_DELAY], and then sends `pane.send_keys`
 * with `Enter`.
 */
object PasteThenEnter {

    /** The time between the answer of Herdr to the text and the `Enter` request. */
    val ENTER_DELAY: Duration = 300.milliseconds

    /** One request and the answer of the transport, or null when the transport gave no answer. */
    data class Step(val request: ClientShellEndpointRequest, val response: String?)

    /**
     * Sends [text] and then `Enter` to [paneId] through [transport], and returns each request with its answer, in
     * order. The ids of the requests start with [requestId]. When [accepted] returns false for the answer to the text,
     * this function sends no `Enter`, so that a failed paste does not send an empty prompt.
     */
    suspend fun send(
        paneId: String,
        text: String,
        requestId: String,
        transport: suspend (ClientShellEndpointRequest) -> String?,
        accepted: (request: ClientShellEndpointRequest, response: String?) -> Boolean,
        enterDelay: Duration = ENTER_DELAY,
    ): List<Step> {
        val textRequest = HerdrRpcBuilder.paneSendInput(
            paneId = paneId,
            text = text,
            keys = emptyList(),
            requestId = "${requestId}_text",
        )
        val textStep = Step(textRequest, transport(textRequest))
        if (!accepted(textStep.request, textStep.response)) return listOf(textStep)
        delay(enterDelay)
        val enterRequest = HerdrRpcBuilder.paneSendKeys(
            paneId = paneId,
            keys = listOf("Enter"),
            requestId = "${requestId}_enter",
        )
        return listOf(textStep, Step(enterRequest, transport(enterRequest)))
    }
}
