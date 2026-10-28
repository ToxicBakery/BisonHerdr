package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientKeyEvent
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
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
 * Herdr gives no cursor position, so the client puts the cursor after the last text of the pane and its blanks, and
 * moves it back for the spaces of an erase. The pane texts are the `pane.read` answers (`source: visible`,
 * `format: ansi`) of a real Herdr 0.9.5: dash with the prompt `$ `, `read -p` of bash, and `input()` of Python 3.
 */
class PromptCursorTest {

    private val surfaceBus = PaneSurfaceBus()

    /** The text of the pane in the next `pane.read` answer. */
    @Volatile
    private var paneText = ""

    /**
     * Holds the Input lane, so that the server text does not replace the optimistic echo before the check. A test that
     * needs the answers of Herdr completes it.
     */
    @Volatile
    private var inputLaneHold = CompletableDeferred<Unit>()

    private val client = RealHerdrSshClient(
        sshDebugLog = SshDebugLog(),
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = surfaceBus,
        semanticNotificationBus = SemanticNotificationBus(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
        secretStore = InMemorySecretStore(),
        localNetworkGuard = permittedLocalNetwork(),
        remoteBatchExecutor = { _, _, lane, commands ->
            if (lane == RealHerdrSshClient.WorkerLane.Input) inputLaneHold.await()
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
    fun theCursorIsAfterTheSpaceOfAPromptWithABackgroundColor() = runTest {
        assertEquals(2 to 0, cursorAfterPoll("\u001b[0m\u001b[48;5;4m$ \u001b[0m"))
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
    fun theCursorIsAfterTheSpacesThatTheUserTyped() = runTest {
        assertEquals(5 to 0, cursorAfterInput("$ ab ", text("ab ")))
        assertEquals(5 to 0, cursorAfterInput("$    ", text("   ")))
        assertEquals(5 to 0, cursorAfterInput("> a  ", text("a  ")))
        assertEquals(9 to 1, cursorAfterInput(READ_PROMPT_TEXT, text("ab ")))
    }

    @Test
    fun theCursorIsBeforeTheSpaceOfOneBackspaceInDash() = runTest {
        // dash: "abc" and one Backspace. The tty erases with "\b \b", so the text keeps a written space.
        assertEquals(4 to 0, cursorAfterInput("$ ab ", text("abc"), BACKSPACE))
    }

    @Test
    fun theCursorIsBeforeTheSpacesOfThreeBackspacesInDash() = runTest {
        assertEquals(2 to 0, cursorAfterInput("$    ", text("abc"), BACKSPACE, BACKSPACE, BACKSPACE))
    }

    @Test
    fun theCursorIsBeforeTheSpaceOfOneBackspaceInBashRead() = runTest {
        // bash: read -p "Name: " x, then "abc" and one Backspace.
        assertEquals(8 to 1, cursorAfterInput(READ_PROMPT_TEXT, text("abc"), BACKSPACE))
    }

    @Test
    fun theCursorIsBeforeTheSpacesOfTwoBackspacesInPythonInput() = runTest {
        // python3 -c 'input("> ")', then "abc" and two Backspaces.
        assertEquals(3 to 0, cursorAfterInput("> a  ", text("abc"), BACKSPACE, BACKSPACE))
    }

    @Test
    fun aDelInTypedTextIsAnEraseToo() = runTest {
        assertEquals(4 to 0, cursorAfterInput("$ ab ", text("abc\u007f")))
    }

    @Test
    fun aTypedCharacterAfterAnEraseEndsTheErase() = runTest {
        // The user erases "c" and then types a space. The text is the same, but the cursor is after the space.
        assertEquals(5 to 0, cursorAfterInput("$ ab ", text("abc"), BACKSPACE, text(" ")))
    }

    @Test
    fun aBackspaceFromTheKeyBarIsAnErase() = runTest {
        assertEquals(4 to 0, cursorAfterRequest("$ ab ", """{"pane_id":"w1:p1","keys":["Backspace"]}"""))
    }

    @Test
    fun anEraseOfALineFromTheKeyBarPutsTheCursorAfterTheText() = runTest {
        // Ctrl-U in dash writes a space for each erased character. The size of the erase is not known, so the
        // cursor is just after the last text. The real cursor is at column 2.
        assertEquals(1 to 0, cursorAfterRequest("$    ", """{"pane_id":"w1:p1","keys":["ctrl+u"]}"""))
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

    @Test
    fun theOptimisticEchoAfterABackspaceGoesBeforeTheSpaceOfTheErase() = runTest {
        cursorAfterInput("$ ab ", text("abc"), BACKSPACE)

        // The answer of Herdr to "d" does not come in this test, so the frame shows the echo.
        inputLaneHold = CompletableDeferred()
        val route = ProxyJumpTunnel().resolveRoute(machine)
        assertTrue(client.sendPaneInput(machine, route, PANE_ID, ClientPaneInputEvent.TextCommit("d")))

        val frame = assertNotNull(surfaceBus.surfaceFor(machine.id))
        assertEquals("d", frame.frame.cellAt(4, 0)?.symbol)
        assertTrue(frame.frame.cellAt(4, 0)?.isPredictedEcho == true)
    }

    /**
     * Sends [events] to the pane, waits for the answers, and returns the cursor after one poll. Herdr shows [paneText]
     * in each read.
     */
    private suspend fun cursorAfterInput(paneText: String, vararg events: ClientPaneInputEvent): Pair<Int, Int> {
        cursorAfterPoll(paneText)
        inputLaneHold.complete(Unit)
        val route = ProxyJumpTunnel().resolveRoute(machine)
        events.forEach { assertTrue(client.sendPaneInput(machine, route, PANE_ID, it)) }
        client.awaitInputFlush(machine.id)
        return cursorAfterPoll(paneText)
    }

    /**
     * Sends a `pane.send_keys` request with [paramsJson], as the key bar does, and returns the cursor after one poll.
     * Herdr shows [paneText] in each read.
     */
    private suspend fun cursorAfterRequest(paneText: String, paramsJson: String): Pair<Int, Int> {
        cursorAfterPoll(paneText)
        inputLaneHold.complete(Unit)
        val request = ClientShellEndpointRequest(id = "keys", method = "pane.send_keys", paramsJson = paramsJson)
        assertNotNull(client.executeEndpointRpc(machine, ProxyJumpTunnel().resolveRoute(machine), request))
        return cursorAfterPoll(paneText)
    }

    private fun text(value: String) = ClientPaneInputEvent.TextCommit(value)

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

        /**
         * The text of bash after `read -p "Name: " x` and the input "ab ". The same text comes after "abc" and one
         * Backspace.
         */
        const val READ_PROMPT_TEXT = "$ read -p \"Name: \" x\r\nName: ab "

        val BACKSPACE = ClientPaneInputEvent.Key(ClientKeyEvent(code = ClientKeyCode.Backspace))

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
