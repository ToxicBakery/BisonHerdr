package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.ssh.SshTransportManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Tests the requests of [AgentAlertAction] and [AgentAlertAction.resultOf]. */
class AgentAlertActionTest {

    @Test
    fun eachActionSendsOneRequestToThePane() {
        assertEquals("pane.send_keys" to listOf("Enter"), keysOf(AgentAlertAction.Approve("w1:p2")))
        assertEquals("pane.send_keys" to listOf("Esc"), keysOf(AgentAlertAction.Reject("w1:p2")))

        val reply = AgentAlertAction.Reply("w1:p2", "use the cache").request
        val params = Json.parseToJsonElement(reply.paramsJson).jsonObject
        assertEquals("pane.send_input", reply.method)
        assertEquals("w1:p2", params.getValue("pane_id").jsonPrimitive.content)
        assertEquals("use the cache", params.getValue("text").jsonPrimitive.content)
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
        assertEquals(AgentAlertAction.Result.Failed("pane w1:p1 not found"), AgentAlertAction.resultOf(request, refused))
    }

    @Test
    fun anAnswerWithNoIdDecidesForTheDemoServer() {
        val request = AgentAlertAction.Reply("w1:p1", "go on").request
        val answer = """{"result":{"type":"agent_prompted","pane_id":"w1:p1"}}"""
        assertEquals(AgentAlertAction.Result.Sent, AgentAlertAction.resultOf(request, answer))
    }

    @Test
    fun noAnswerAndTheErrorOfTheTransportAreFailures() {
        val request = AgentAlertAction.Reject("w1:p1").request
        assertEquals(AgentAlertAction.Result.Failed(null), AgentAlertAction.resultOf(request, null))
        assertEquals(AgentAlertAction.Result.Failed(null), AgentAlertAction.resultOf(request, "\n"))

        // The transport gives this answer when the backend gave no response, for example after a failed reconnect.
        val noResponse = """{"id":"${request.id}","error":{"code":"${SshTransportManager.NO_RESPONSE_CODE}",""" +
            """"message":"The backend gave no response for pane.send_keys"}}"""
        assertEquals(
            AgentAlertAction.Result.Failed("The backend gave no response for pane.send_keys"),
            AgentAlertAction.resultOf(request, noResponse),
        )

        val codeOnly = """{"id":"${request.id}","error":{"code":"${SshTransportManager.NO_RESPONSE_CODE}"}}"""
        assertEquals(
            AgentAlertAction.Result.Failed(SshTransportManager.NO_RESPONSE_CODE),
            AgentAlertAction.resultOf(request, codeOnly),
        )
    }

    @Test
    fun anActionNamesThePaneAndNotTheAgent() {
        val params = Json.parseToJsonElement(AgentAlertAction.Approve("w2:p1").request.paramsJson).jsonObject
        assertEquals(setOf("pane_id", "keys"), params.keys)
        assertEquals(
            HerdrRpcBuilder.paneSendKeys("w2:p1", listOf("Enter"), requestId = "agent_alert_approve"),
            AgentAlertAction.Approve("w2:p1").request,
        )
    }

    private fun keysOf(action: AgentAlertAction): Pair<String, List<String>> {
        val params = Json.parseToJsonElement(action.request.paramsJson).jsonObject
        assertEquals(action.paneId, params.getValue("pane_id").jsonPrimitive.content)
        return action.request.method to params.getValue("keys").jsonArray.map { it.jsonPrimitive.content }
    }
}
