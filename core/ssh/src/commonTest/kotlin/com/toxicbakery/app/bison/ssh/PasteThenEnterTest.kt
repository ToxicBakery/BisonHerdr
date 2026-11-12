package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Tests the order and the gap of the two requests of [PasteThenEnter] with a fake Herdr and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasteThenEnterTest {

    /** A request that the fake Herdr got: the virtual time of its start, its method, and its parameters. */
    private data class Call(val atMs: Long, val method: String, val params: String)

    /**
     * A fake Herdr that answers each request after [answerMs] of virtual time. It refuses a text request when
     * [refuseText] is true.
     */
    private class FakeHerdr(private val scope: TestScope, private val answerMs: Long, private val refuseText: Boolean) {
        val calls = mutableListOf<Call>()

        suspend fun send(request: ClientShellEndpointRequest): String {
            val params = Json.parseToJsonElement(request.paramsJson).jsonObject
            val keys = params["keys"]?.jsonArray?.joinToString(",") { it.jsonPrimitive.content }.orEmpty()
            val text = params["text"]?.jsonPrimitive?.content.orEmpty()
            calls += Call(scope.currentTime, request.method, "text=$text keys=$keys")
            delay(answerMs)
            return if (refuseText && request.method == "pane.send_input") {
                """{"id":"${request.id}","error":{"code":"pane_not_found","message":"no pane"}}"""
            } else {
                """{"id":"${request.id}","result":{"type":"ok"}}"""
            }
        }
    }

    private fun accepted(request: ClientShellEndpointRequest, response: String?): Boolean =
        response != null && "\"result\"" in response && request.id in response

    @Test
    fun theTextGoesFirstAndEnterComesAfterTheAnswerAndTheGap() = runTest {
        val herdr = FakeHerdr(this, answerMs = 120, refuseText = false)
        val steps = PasteThenEnter.send(
            paneId = "w1:p2",
            text = "use the cache",
            requestId = "reply",
            transport = herdr::send,
            accepted = ::accepted,
        )

        assertEquals(
            listOf(
                Call(0, "pane.send_input", "text=use the cache keys="),
                // The answer to the text came at 120 ms. Enter starts 300 ms after it.
                Call(120 + PasteThenEnter.ENTER_DELAY.inWholeMilliseconds, "pane.send_keys", "text= keys=Enter"),
            ),
            herdr.calls,
        )
        assertEquals(listOf("reply_text", "reply_enter"), steps.map { it.request.id })
        assertEquals(listOf("w1:p2", "w1:p2"), steps.map {
            Json.parseToJsonElement(it.request.paramsJson).jsonObject.getValue("pane_id").jsonPrimitive.content
        })
    }

    @Test
    fun aRefusedTextSendsNoEnter() = runTest {
        val herdr = FakeHerdr(this, answerMs = 50, refuseText = true)
        val steps = PasteThenEnter.send(
            paneId = "w1:p2",
            text = "go on",
            requestId = "reply",
            transport = herdr::send,
            accepted = ::accepted,
            enterDelay = 10.milliseconds,
        )

        assertEquals(listOf("pane.send_input"), herdr.calls.map { it.method })
        assertEquals(1, steps.size)
    }
}
