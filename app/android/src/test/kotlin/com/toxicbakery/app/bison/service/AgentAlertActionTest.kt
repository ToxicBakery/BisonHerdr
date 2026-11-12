package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.ssh.PasteThenEnter
import com.toxicbakery.app.bison.ssh.SshTransportManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Tests the requests of [AgentAlertAction] and [AgentAlertAction.resultOf]. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentAlertActionTest {

    /** A request that the fake transport got: the virtual time, the method, the text, and the keys. */
    private data class Call(val atMs: Long, val method: String, val text: String, val keys: List<String>)

    @Test
    fun approveAndRejectSendOneKeyToThePane() = runTest {
        val calls = mutableListOf<Call>()
        val transport = recordingTransport(calls) { ok(it) }

        assertEquals(AgentAlertAction.Result.Sent, AgentAlertAction.Approve("w1:p2").perform(transport))
        assertEquals(AgentAlertAction.Result.Sent, AgentAlertAction.Reject("w1:p2").perform(transport))
        assertEquals(
            listOf(
                Call(0, "pane.send_keys", "", listOf("Enter")),
                Call(0, "pane.send_keys", "", listOf("Esc")),
            ),
            calls,
        )
        assertEquals(
            HerdrRpcBuilder.paneSendKeys("w1:p2", listOf("Enter"), requestId = "agent_alert_approve"),
            AgentAlertAction.Approve("w1:p2").request,
        )
    }

    @Test
    fun replySendsTheTextAndThenEnterAfterTheGap() = runTest {
        val calls = mutableListOf<Call>()
        val result = AgentAlertAction.Reply("w1:p2", "use the cache").perform(recordingTransport(calls) { ok(it) })

        assertEquals(AgentAlertAction.Result.Sent, result)
        assertEquals(
            listOf(
                Call(0, "pane.send_input", "use the cache", emptyList()),
                Call(PasteThenEnter.ENTER_DELAY.inWholeMilliseconds, "pane.send_keys", "", listOf("Enter")),
            ),
            calls,
        )
    }

    @Test
    fun aReplyWithARefusedTextSendsNoEnterAndFails() = runTest {
        val calls = mutableListOf<Call>()
        val result = AgentAlertAction.Reply("w1:p2", "go on").perform(
            recordingTransport(calls) { """{"id":"${it.id}","error":{"code":"pane_not_found","message":"no pane"}}""" },
        )

        assertEquals(AgentAlertAction.Result.Failed("no pane"), result)
        assertEquals(listOf("pane.send_input"), calls.map { it.method })
    }

    @Test
    fun theAnswerWithTheIdOfTheRequestDecides() {
        val request = AgentAlertAction.Approve("w1:p1").request
        // The real client sends a pane.read after the input, and returns both answers.
        val sent = """
            {"id":"${request.id}","result":{"type":"ok"}}
            {"id":"pane_read","error":{"code":"pane_not_found","message":"no pane"}}
        """.trimIndent()
        assertEquals(AgentAlertAction.Result.Sent, AgentAlertAction.resultOf(request, sent))

        val refused = """
            some shell text
            {"id":"pane_read","result":{"type":"pane_read"}}
            {"id":"${request.id}","error":{"code":"pane_not_found","message":"pane w1:p1 not found"}}
        """.trimIndent()
        assertEquals(
            AgentAlertAction.Result.Failed("pane w1:p1 not found"),
            AgentAlertAction.resultOf(request, refused),
        )
    }

    @Test
    fun anAnswerWithNoIdDecidesForTheDemoServer() {
        val request = AgentAlertAction.Reject("w1:p1").request
        val answer = """{"result":{"type":"ok"}}"""
        assertEquals(AgentAlertAction.Result.Sent, AgentAlertAction.resultOf(request, answer))
    }

    @Test
    fun noAnswerAndNoResponseOfTheTransportAreFailuresWithNoCause() {
        val request = AgentAlertAction.Reject("w1:p1").request
        assertEquals(AgentAlertAction.Result.Failed(null), AgentAlertAction.resultOf(request, null))
        assertEquals(AgentAlertAction.Result.Failed(null), AgentAlertAction.resultOf(request, "\n"))

        // The transport gives this answer when the backend gave no response, for example after a failed reconnect.
        // The service then shows its own text, and not the English text of the backend.
        val noResponse = """{"id":"${request.id}","error":{"code":"${SshTransportManager.NO_RESPONSE_CODE}",""" +
            """"message":"The backend gave no response for pane.send_keys"}}"""
        assertEquals(AgentAlertAction.Result.Failed(null), AgentAlertAction.resultOf(request, noResponse))
    }

    @Test
    fun anActionNamesThePaneAndNotTheAgent() {
        val params = Json.parseToJsonElement(AgentAlertAction.Approve("w2:p1").request.paramsJson).jsonObject
        assertEquals(setOf("pane_id", "keys"), params.keys)
    }

    /** Returns a transport that records each request in [calls] with the virtual time, and answers with [answer]. */
    private fun TestScope.recordingTransport(
        calls: MutableList<Call>,
        answer: (ClientShellEndpointRequest) -> String,
    ): suspend (ClientShellEndpointRequest) -> String? = { request ->
        val params = Json.parseToJsonElement(request.paramsJson).jsonObject
        calls += Call(
            atMs = currentTime,
            method = request.method,
            text = params["text"]?.jsonPrimitive?.content.orEmpty(),
            keys = params["keys"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
        answer(request)
    }

    private fun ok(request: ClientShellEndpointRequest): String = """{"id":"${request.id}","result":{"type":"ok"}}"""
}
