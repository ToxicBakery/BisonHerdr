package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tests the cursor of the frame after a shell prompt, and the optimistic echo at that cursor (issue #59).
 *
 * Herdr gives no cursor position, so the client puts the cursor after the last text of the pane. The pane texts are
 * the `pane.read` answers (`source: visible`, `format: ansi`) of a real Herdr 0.9.5 with the prompt `$ `.
 */
class PromptCursorTest {

    private val surfaceBus = PaneSurfaceBus()

    /** The text of the pane in the next `pane.read` answer. */
    @Volatile
    private var paneText = ""

    /** Holds the Input lane, so that the server text does not replace the optimistic echo before the check. */
    private val inputLaneGate = CompletableDeferred<Unit>()

    private val client = RealHerdrSshClient(
        sshDebugLog = SshDebugLog(),
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = surfaceBus,
        semanticNotificationBus = SemanticNotificationBus(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
        secretStore = InMemorySecretStore(),
        localNetworkGuard = permittedLocalNetwork(),
        remoteBatchExecutor = { _, _, lane, commands ->
            if (lane == RealHerdrSshClient.WorkerLane.Input) inputLaneGate.await()
            buildString {
                commands.forEach { command ->
                    when (command) {
                        is RealHerdrSshClient.RemoteShellCommand.Exec -> appendLine(SNAPSHOT_JSON)
                        is RealHerdrSshClient.RemoteShellCommand.ApiBridgeLine ->
                            if ("\"pane.read\"" in command.ndjsonLine) appendLine(paneReadAnswer(paneText))
                    }
                }
            }
        },
    )

    private val machine = HostMachine(id = "box", name = "box", hostname = "192.168.1.42", username = "ian")

    @AfterTest
    fun tearDown() {
        client.disconnect(machine.id)
    }

    @Test
    fun theCursorIsAfterTheSpaceOfThePrompt() = runTest {
        assertEquals(2 to 0, cursorAfterPoll("$ "))
    }

    @Test
    fun theCursorIsAfterTheSpaceOfAColoredPrompt() = runTest {
        assertEquals(2 to 0, cursorAfterPoll("\u001b[38;5;2m$\u001b[0m "))
    }

    @Test
    fun theCursorIsAfterTheSpacesThatTheUserTyped() = runTest {
        assertEquals(5 to 0, cursorAfterPoll("$ ab "))
    }

    @Test
    fun theCursorIsAfterThePromptOfTheLastLine() = runTest {
        assertEquals(2 to 2, cursorAfterPoll("$ ab \r\n/bin/sh: 1: ab: not found\r\n$ "))
    }

    @Test
    fun theCursorOfALineWithNoSpaceAtTheEndIsAfterItsText() = runTest {
        assertEquals(1 to 0, cursorAfterPoll("$"))
        assertEquals(4 to 0, cursorAfterPoll("$ ab"))
    }

    @Test
    fun theOptimisticEchoAfterThePromptGoesAfterTheSpace() = runTest {
        cursorAfterPoll("$ ")

        val route = ProxyJumpTunnel().resolveRoute(machine)
        assertTrue(client.sendPaneInput(machine, route, PANE_ID, ClientPaneInputEvent.TextCommit("l")))

        val frame = assertNotNull(surfaceBus.surfaceFor(machine.id))
        assertEquals(" ", frame.frame.cellAt(1, 0)?.symbol)
        assertFalse(frame.frame.cellAt(1, 0)?.isPredictedEcho == true)
        assertEquals("l", frame.frame.cellAt(2, 0)?.symbol)
        assertTrue(frame.frame.cellAt(2, 0)?.isPredictedEcho == true)
        assertEquals(3, frame.frame.cursor?.x?.toInt())
    }

    /** Runs one poll that reads [text] from the pane, and returns the cursor of the new frame as `(col, row)`. */
    private suspend fun cursorAfterPoll(text: String): Pair<Int, Int> {
        paneText = text
        assertTrue(client.refreshSnapshotAndSurface(machine, ProxyJumpTunnel().resolveRoute(machine)))
        val frame: PaneSurfaceFrame = assertNotNull(surfaceBus.surfaceFor(machine.id))
        val cursor = assertNotNull(frame.frame.cursor)
        return cursor.x.toInt() to cursor.y.toInt()
    }

    private companion object {
        const val PANE_ID = "w1:p1"

        /** The answer of `herdr api snapshot` for a host with one pane of 80 by 24 cells. */
        const val SNAPSHOT_JSON =
            """{"id":"snap","result":{"type":"session_snapshot","snapshot":{"focused_workspace_id":"w1",""" +
                """"focused_tab_id":"w1:t1","focused_pane_id":"w1:p1",""" +
                """"workspaces":[{"workspace_id":"w1","active_tab_id":"w1:t1","label":"main","focused":true}],""" +
                """"tabs":[{"tab_id":"w1:t1","workspace_id":"w1","number":1,"label":"1","focused":true}],""" +
                """"panes":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","focused":true}],"agents":[],""" +
                """"layouts":[{"workspace_id":"w1","tab_id":"w1:t1","zoomed":false,"panes":[{"pane_id":"w1:p1",""" +
                """"rect":{"x":0,"y":0,"width":80,"height":24},"focused":true}],"splits":[]}]}}}"""

        /** Returns the `pane.read` answer of Herdr for the pane text [text]. */
        fun paneReadAnswer(text: String): String =
            buildJsonObject {
                put("id", "read_$PANE_ID")
                put(
                    "result",
                    buildJsonObject {
                        put("type", "pane_read")
                        put(
                            "read",
                            buildJsonObject {
                                put("pane_id", PANE_ID)
                                put("text", text)
                            },
                        )
                    },
                )
            }.toString()
    }
}
