package com.toxicbakery.app.bison.ssh

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live end-to-end check of the terminal cursor position against an `agy` (Antigravity CLI) agent
 * that runs in a real Herdr session.
 *
 * The host harness `scripts/e2e/agy-cursor-e2e.sh` starts the Herdr session, starts `agy`, starts a
 * private `sshd`, and boots a headless emulator. The tests skip when the harness arguments are not set.
 *
 * The test types a marker into the agy prompt through the real [SshTransportManager] path. The agy
 * caret is directly after the marker. The test makes sure that the frame cursor is at that cell,
 * because `TerminalSurfaceCanvas` draws the cursor from `frame.cursor`. The test also makes sure
 * that the local typing echo shows the marker on the prompt row.
 */
@RunWith(AndroidJUnit4::class)
class AgyCursorPlacementE2ETest {

    private val arguments: Bundle = InstrumentationRegistry.getArguments()

    private fun harnessArgument(name: String): String? =
        arguments.getString(name)?.takeIf { it.isNotBlank() }

    /**
     * Step 1 of the harness. Creates the AndroidKeyStore key and writes its public key to logcat.
     * The harness adds this key to the `authorized_keys` file of the private `sshd`.
     */
    @Test
    fun exportPublicKey() {
        assumeTrue("Run through scripts/e2e/agy-cursor-e2e.sh", harnessArgument(ARG_HOST) != null)
        val exported = AndroidKeystoreKeyManager().getOrCreatePublicKey(
            keyAlias = KEY_ALIAS,
            algorithm = KEY_ALGORITHM,
            comment = KEY_COMMENT,
        )
        Log.i(TAG, "$PUBKEY_LOG_PREFIX${exported.openSshAuthorizedKeyLine}")
    }

    /**
     * Step 2 of the harness. Connects to the live Herdr session and compares the frame cursor
     * with the agy prompt caret.
     */
    @Test
    fun frameCursorFollowsAgyPromptCaret(): Unit = runBlocking {
        val host = harnessArgument(ARG_HOST)
        assumeTrue("Run through scripts/e2e/agy-cursor-e2e.sh", host != null)
        val port = harnessArgument(ARG_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        val user = assertNotNull(harnessArgument(ARG_USER), "Missing -e $ARG_USER")

        val debugLog = SshDebugLog()
        val stateManager = SshStateManager()
        val surfaceBus = PaneSurfaceBus()
        val transport = SshTransportManager(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = surfaceBus),
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            endpointSnapshotBus = EndpointSnapshotBus(),
            paneSurfaceBus = surfaceBus,
            semanticNotificationBus = SemanticNotificationBus(),
        )
        val machine = HostMachine(
            id = MACHINE_ID,
            name = "agy cursor e2e",
            hostname = assertNotNull(host),
            port = port,
            username = user,
            authMethod = SshAuthMethod.KeystoreKey(keyAlias = KEY_ALIAS, algorithm = KEY_ALGORITHM),
            transportMode = TransportMode.SshEndpointOnly,
        )

        fun debugLogDump(): String =
            debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

        try {
            assertTrue(transport.connect(machine), "SSH connect failed:\n${debugLogDump()}")

            val readyFrame = assertNotNull(
                awaitFrame(surfaceBus) { frame -> frameRows(frame).any { it.contains(AGY_BANNER) } },
                "No frame showed the agy banner '$AGY_BANNER':\n${debugLogDump()}",
            )
            val paneId = readyFrame.panes.firstOrNull { it.focused }?.paneId
                ?: readyFrame.panes.first().paneId
            report("ready", readyFrame)

            assertTrue(
                transport.sendPaneInput(machine.id, paneId, ClientPaneInputEvent.TextCommit(MARKER)),
                "Sending the marker failed:\n${debugLogDump()}",
            )
            val echoFrame = assertNotNull(surfaceBus.surfaceFor(machine.id), "No frame after the input")
            report("optimistic-echo", echoFrame)

            // Wait until the optimistic echo expires, so the frame shows only the Herdr pane content.
            delay(SETTLE_AFTER_INPUT_MS)
            val confirmedFrame = assertNotNull(
                awaitFrame(surfaceBus) { frame -> frameRows(frame).any { it.contains(MARKER) } },
                "Herdr did not echo the marker '$MARKER':\n${debugLogDump()}",
            )
            report("confirmed", confirmedFrame)

            val rows = frameRows(confirmedFrame)
            val markerRow = rows.indexOfFirst { it.contains(MARKER) }
            val caretCol = rows[markerRow].indexOf(MARKER) + MARKER.length
            val cursor = assertNotNull(confirmedFrame.frame.cursor, "The frame has no cursor")
            val summary = "agy caret=(col=$caretCol, row=$markerRow) " +
                "frame cursor=(col=${cursor.x}, row=${cursor.y})\n${renderFrame(confirmedFrame)}"
            Log.i(TAG, "RESULT $summary")

            assertEquals(markerRow, cursor.y.toInt(), "Cursor row is not on the agy prompt row. $summary")
            assertEquals(caretCol, cursor.x.toInt(), "Cursor column is not after the typed text. $summary")

            // The local echo shows the typed text before Herdr confirms it. It must be on the prompt row too.
            val echoRow = frameRows(echoFrame).indexOfFirst { it.contains(MARKER) }
            assertEquals(
                markerRow,
                echoRow,
                "The typing echo is not on the agy prompt row.\n${renderFrame(echoFrame)}",
            )
        } finally {
            transport.disconnect(machine.id)
        }
    }

    private suspend fun awaitFrame(
        surfaceBus: PaneSurfaceBus,
        predicate: (PaneSurfaceFrame) -> Boolean,
    ): PaneSurfaceFrame? = withTimeoutOrNull(FRAME_TIMEOUT_MS) {
        surfaceBus.surfaces
            .mapNotNull { surfaces -> surfaces[MACHINE_ID] }
            .first(predicate)
    }

    /** Returns one string for each frame row. Each character is one cell, so a string index is a column. */
    private fun frameRows(frame: PaneSurfaceFrame): List<String> {
        val cols = frame.frame.width.toInt()
        val rows = frame.frame.height.toInt()
        return List(rows) { row ->
            buildString(cols) {
                for (col in 0 until cols) {
                    val symbol = frame.frame.cells.getOrNull(row * cols + col)?.symbol.orEmpty()
                    append(if (symbol.length == 1) symbol[0] else if (symbol.isEmpty()) ' ' else '?')
                }
            }
        }
    }

    /** Draws the frame as text and puts a `█` in the cell where the frame cursor is. */
    private fun renderFrame(frame: PaneSurfaceFrame): String {
        val cursor = frame.frame.cursor
        return frameRows(frame).mapIndexed { row, text ->
            val line = if (cursor != null && cursor.y.toInt() == row) {
                val col = cursor.x.toInt().coerceIn(0, text.length)
                text.substring(0, col) + "█" + text.substring((col + 1).coerceAtMost(text.length))
            } else {
                text
            }
            "%02d|%s".format(row, line.trimEnd())
        }.joinToString("\n")
    }

    private fun report(stage: String, frame: PaneSurfaceFrame) {
        Log.i(TAG, "FRAME[$stage] cursor=${frame.frame.cursor}\n${renderFrame(frame)}")
    }

    private companion object {
        const val TAG = "BisonAgyCursorE2E"
        const val PUBKEY_LOG_PREFIX = "PUBKEY "
        const val ARG_HOST = "bisonHost"
        const val ARG_PORT = "bisonPort"
        const val ARG_USER = "bisonUser"
        const val DEFAULT_PORT = 2299
        const val MACHINE_ID = "agy-cursor-e2e"
        const val KEY_ALIAS = "bison_agy_cursor_e2e_p256"

        // Use ECDSA P-256. The AndroidKeyStore Ed25519 key of AndroidKeystoreKeyManager cannot sign
        // at this time, because its KeyGenParameterSpec does not set DIGEST_NONE.
        val KEY_ALGORITHM = KeyAlgorithm.EcdsaP256
        const val KEY_COMMENT = "bison-agy-cursor-e2e"
        const val AGY_BANNER = "Antigravity CLI"
        const val MARKER = "bisoncursorprobe"
        const val SETTLE_AFTER_INPUT_MS = 2_500L
        const val FRAME_TIMEOUT_MS = 30_000L
    }
}
