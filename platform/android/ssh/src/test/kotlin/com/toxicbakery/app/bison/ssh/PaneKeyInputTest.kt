package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientKeyEvent
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests the key forms of [HerdrKeys] and the pane input lines of [RealHerdrSshClient].
 *
 * Herdr 0.9.5 has no `pane.send_keys` name for Home, End, PageUp, PageDown, Delete, and Insert. For a name that it
 * does not know, Herdr answers `invalid_key` and writes no key of the request. `pane.send_text` writes its text with
 * no change. `pane.send_input` puts its text in a bracketed paste when the pane has bracketed paste on. Herdr drops a
 * request line over 1 MiB with no answer.
 */
class PaneKeyInputTest {

    /** One pane input request: its method, its text, and its key names. */
    private data class InputRequest(val method: String, val text: String = "", val keys: List<String> = emptyList())

    /**
     * A Herdr 0.9.5 server for the input lane. It does not accept a request with a key name in [unknownKeys], and it
     * writes nothing of that request. Like the batch script, it then does not run the next pane input lines of the
     * batch. When [holdFirstBatch] is true, it holds the first batch until the test completes [release]. It gives no
     * answer to a request with a method in [silentMethods].
     */
    private class FakeHerdr(
        private val unknownKeys: Set<String> = emptySet(),
        private val holdFirstBatch: Boolean = false,
        private val silentMethods: Set<String> = emptySet(),
    ) {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val written = StringBuilder()
        private val notAccepted = mutableListOf<InputRequest>()
        private var batches = 0

        /** The input that the pane got. A named key shows as `<name>`, and a paste as `(paste:text)`. */
        val paneInput: String
            get() = synchronized(this) { written.toString() }

        /** The requests that Herdr did not accept. */
        val notAcceptedRequests: List<InputRequest>
            get() = synchronized(this) { notAccepted.toList() }

        suspend fun execute(commands: List<RealHerdrSshClient.RemoteShellCommand>): String {
            val first = synchronized(this) { batches++ == 0 }
            if (first && holdFirstBatch) {
                held.complete(Unit)
                release.await()
            }
            return synchronized(this) { answer(commands) }
        }

        private fun answer(commands: List<RealHerdrSshClient.RemoteShellCommand>): String = buildString {
            var stopped = false
            for (cmd in commands) {
                if (cmd !is RealHerdrSshClient.RemoteShellCommand.ApiBridgeLine) continue
                if (cmd.paneInput && stopped) continue
                val id = requestId(cmd.ndjsonLine)
                val request = inputRequest(cmd.ndjsonLine)
                when {
                    request.method in silentMethods -> Unit

                    request.method == "pane.read" -> appendLine(
                        """{"id":"$id","result":{"type":"pane_read","read":{"pane_id":"w1:p1","text":"$ "}}}""",
                    )

                    request.keys.any { it in unknownKeys } -> {
                        stopped = true
                        notAccepted += request
                        val key = request.keys.first { it in unknownKeys }
                        appendLine("""{"id":"$id","error":{"code":"invalid_key","message":"unsupported key $key"}}""")
                    }

                    else -> {
                        if (request.method == "pane.send_input") {
                            written.append("(paste:").append(request.text).append(')')
                        } else {
                            written.append(request.text)
                        }
                        request.keys.forEach { written.append('<').append(it).append('>') }
                        appendLine("""{"id":"$id","result":{"type":"ok"}}""")
                    }
                }
            }
        }
    }

    @Test
    fun theSixKeysWithNoHerdrNameGoAsXtermBytes() {
        assertEquals(HerdrKeyInput.Raw("$ESC[H"), HerdrKeys.encode(key(ClientKeyCode.Home)))
        assertEquals(HerdrKeyInput.Raw("$ESC[F"), HerdrKeys.encode(key(ClientKeyCode.End)))
        assertEquals(HerdrKeyInput.Raw("$ESC[2~"), HerdrKeys.encode(key(ClientKeyCode.Insert)))
        assertEquals(HerdrKeyInput.Raw("$ESC[3~"), HerdrKeys.encode(key(ClientKeyCode.Delete)))
        assertEquals(HerdrKeyInput.Raw("$ESC[5~"), HerdrKeys.encode(key(ClientKeyCode.PageUp)))
        assertEquals(HerdrKeyInput.Raw("$ESC[6~"), HerdrKeys.encode(key(ClientKeyCode.PageDown)))

        // A modifier adds the xterm parameter 1 + shift (1) + alt (2) + ctrl (4).
        assertEquals(HerdrKeyInput.Raw("$ESC[1;5H"), HerdrKeys.encode(key(ClientKeyCode.Home, ctrl = true)))
        assertEquals(HerdrKeyInput.Raw("$ESC[1;2F"), HerdrKeys.encode(key(ClientKeyCode.End, shift = true)))
        assertEquals(HerdrKeyInput.Raw("$ESC[5;3~"), HerdrKeys.encode(key(ClientKeyCode.PageUp, alt = true)))
        assertEquals(
            HerdrKeyInput.Raw("$ESC[3;8~"),
            HerdrKeys.encode(key(ClientKeyCode.Delete, ctrl = true, alt = true, shift = true)),
        )
    }

    @Test
    fun eachKeyWithEachModifierHasAFormThatHerdrAccepts() {
        val flags = listOf(false, true)
        for (code in ClientKeyCode.entries) for (ctrl in flags) for (alt in flags) for (shift in flags) {
            val event = key(code, ctrl = ctrl, alt = alt, shift = shift)
            when (val input = assertNotNull(HerdrKeys.encode(event), "No form for $event")) {
                is HerdrKeyInput.Named -> assertTrue(HerdrKeys.isSupportedName(input.spec), input.spec)
                is HerdrKeyInput.Raw -> assertTrue(input.bytes.startsWith("$ESC["), input.bytes)
            }
        }
        assertEquals(HerdrKeyInput.Named("Enter"), HerdrKeys.encode(key(ClientKeyCode.Enter)))
        assertEquals(HerdrKeyInput.Named("Esc"), HerdrKeys.encode(key(ClientKeyCode.Escape)))
        assertEquals(HerdrKeyInput.Named("shift+Tab"), HerdrKeys.encode(key(ClientKeyCode.Tab, shift = true)))
        assertEquals(
            HerdrKeyInput.Named("ctrl+alt+Right"),
            HerdrKeys.encode(key(ClientKeyCode.Right, ctrl = true, alt = true)),
        )
        // The client sends no shift with a letter key.
        assertEquals(
            HerdrKeyInput.Named("ctrl+c"),
            HerdrKeys.encode(key(ClientKeyCode.CharC, ctrl = true, shift = true)),
        )
    }

    @Test
    fun theNameCheckAcceptsTheNamesThatHerdrAccepts() {
        // Herdr 0.9.5 accepted each of these names in `pane.send_keys`. Herdr 0.9.0 does not accept "ctrl++" and
        // "ctrl+shift+alt++", because it has no rule for a modifier and then "++".
        val accepted = listOf(
            "Enter", "Esc", "Tab", "Backspace", "Up", "F5", "f12", "f0", "f255", "f", "shift+Tab", "ctrl+Right",
            "alt+Left", "shift+Up", "ctrl+alt+c", "ctrl+space", "Space", "ctrl++", "ctrl+shift+alt++", "+", "C-c",
            "c-c", "a", "A", "é", "ctrl+é", "control+c", "meta+x", "super+x", "hyper+x", "cmd+x", "option+x", " Up ",
            "ctrl + c", "return", "bs", "double-quote", "ctrl+plus", "f0005",
        )
        accepted.forEach { assertTrue(HerdrKeys.isSupportedName(it), "\"$it\"") }
    }

    @Test
    fun theNameCheckDoesNotAcceptTheNamesThatHerdrDoesNotAccept() {
        // Herdr 0.9.5 answered `invalid_key` for each of these names in `pane.send_keys`.
        val notAccepted = listOf(
            "Home", "End", "PageUp", "PageDown", "Delete", "Insert", "home", "end", "pageup", "pagedown", "pgup",
            "pgdn", "delete", "del", "insert", "ins", "page_up", "page-up", "shift+Home", "ctrl+Home", "ctrl+ ",
            "alt+ ", "f256", "++", "ctrl", "ctrl+", "shift", "a+b", "", " ", "f\u0665", "f\u0660", "F\u0665",
        )
        notAccepted.forEach { assertFalse(HerdrKeys.isSupportedName(it), "\"$it\"") }
    }

    @Test
    fun aBatchWithNamedKeysAndKeyBytesKeepsTheTypedOrder() {
        val lines = client(FakeHerdr()).buildCoalescedInputCommands(
            listOf(text("a"), keyInput(ClientKeyCode.Up), keyInput(ClientKeyCode.Home), text("b")),
        )
        assertEquals(
            listOf(
                InputRequest("pane.send_text", text = "a"),
                InputRequest("pane.send_keys", keys = listOf("Up")),
                InputRequest("pane.send_text", text = "$ESC[Hb"),
            ),
            lines.map { inputRequest(it.ndjsonLine) },
        )
        assertTrue(lines.all { it.paneInput })
    }

    @Test
    fun typedTextGoesToPaneSendTextAndNeverToAPaste() {
        // In vim, typed "dd" and then Up must arrive as keys. As a bracketed paste, vim puts "dd" in the text.
        val lines = client(FakeHerdr()).buildCoalescedInputCommands(
            listOf(text("ls"), keyInput(ClientKeyCode.Enter), text("dd"), keyInput(ClientKeyCode.Up)),
        )
        assertEquals(
            listOf(
                InputRequest("pane.send_text", text = "ls"),
                InputRequest("pane.send_keys", keys = listOf("Enter")),
                InputRequest("pane.send_text", text = "dd"),
                InputRequest("pane.send_keys", keys = listOf("Up")),
            ),
            lines.map { inputRequest(it.ndjsonLine) },
        )
    }

    @Test
    fun aPasteGoesToPaneSendInputWithNoKeys() {
        val lines = client(FakeHerdr()).buildCoalescedInputCommands(
            listOf(text("a"), paste("one\n"), paste("two"), keyInput(ClientKeyCode.Enter)),
        )
        // Each paste stays one paste.
        assertEquals(
            listOf(
                InputRequest("pane.send_text", text = "a"),
                InputRequest("pane.send_input", text = "one\n"),
                InputRequest("pane.send_input", text = "two"),
                InputRequest("pane.send_keys", keys = listOf("Enter")),
            ),
            lines.map { inputRequest(it.ndjsonLine) },
        )
    }

    @Test
    fun aLongTypedTextGoesInLinesBelowTheHerdrLimit() {
        val lines = client(FakeHerdr()).buildCoalescedInputCommands(listOf(text(LONG_TEXT)))

        assertTrue(lines.size > 1)
        lines.forEach { line ->
            assertTrue(line.ndjsonLine.encodeToByteArray().size < RealHerdrSshClient.MAX_HERDR_REQUEST_BYTES)
            val request = inputRequest(line.ndjsonLine)
            assertEquals("pane.send_text", request.method)
            // A split never cuts a code point.
            assertFalse(request.text.first().isLowSurrogate())
            assertFalse(request.text.last().isHighSurrogate())
        }
        assertEquals(LONG_TEXT, lines.joinToString("") { inputRequest(it.ndjsonLine).text })
    }

    @Test
    fun aPasteStaysWholeAndAPasteOverTheRequestLimitIsNotSent() {
        val refused = mutableListOf<Int>()
        // This paste is over the limit of a typed line, but its request is below the limit of a paste.
        val large = "p".repeat(RealHerdrSshClient.MAX_INPUT_JSON_BYTES + 1000)
        val lines = client(FakeHerdr()).buildCoalescedInputCommands(
            batch = listOf(paste(large), paste(LONG_TEXT), text("x")),
            onRefusedPaste = { refused += it },
        )

        assertEquals(
            listOf(InputRequest("pane.send_input", text = large), InputRequest("pane.send_text", text = "x")),
            lines.map { inputRequest(it.ndjsonLine) },
        )
        assertEquals(1, refused.size)
        assertTrue(refused.single() > RealHerdrSshClient.MAX_PASTE_REQUEST_BYTES)
    }

    @Test
    fun theBytesOfOneKeyNeverGoInTwoLines() {
        val filler = "a".repeat(RealHerdrSshClient.MAX_INPUT_JSON_BYTES - 1)
        val lines = client(FakeHerdr()).buildCoalescedInputCommands(listOf(text(filler), keyInput(ClientKeyCode.Home)))
        assertEquals(listOf(filler, "$ESC[H"), lines.map { inputRequest(it.ndjsonLine).text })
    }

    @Test
    fun anEndpointRequestOverTheHerdrLimitIsNotSent() = runBlocking {
        var batches = 0
        val debugLog = SshDebugLog()
        val client = countingClient(debugLog) { batches++ }
        val emptyRequestBytes = requestBytes(HerdrRpcBuilder.paneSendInput(paneId = PANE, text = ""))
        val atLimit = "x".repeat(RealHerdrSshClient.MAX_HERDR_REQUEST_BYTES - emptyRequestBytes)

        // Herdr reads a line of exactly MAX_HERDR_REQUEST_BYTES bytes, so the client sends it.
        val fits = HerdrRpcBuilder.paneSendInput(paneId = PANE, text = atLimit)
        assertEquals(RealHerdrSshClient.MAX_HERDR_REQUEST_BYTES, requestBytes(fits))
        assertNotNull(client.executeEndpointRpc(MACHINE, ROUTE, fits))
        assertEquals(1, batches)

        val tooLarge = HerdrRpcBuilder.paneSendInput(paneId = PANE, text = atLimit + "x")
        assertNull(client.executeEndpointRpc(MACHINE, ROUTE, tooLarge))
        assertEquals(1, batches)
        assertTrue(debugLog.entries.value.any { it.isError && it.message.contains("did not send it") })
    }

    @Test
    fun aChordWithAKeyNameThatHerdrDoesNotAcceptIsNotSent() = runBlocking {
        var batches = 0
        val debugLog = SshDebugLog()
        val client = countingClient(debugLog) { batches++ }

        val noKey = HerdrRpcBuilder.paneSendKeys(paneId = PANE, keys = listOf("ctrl+ "))
        assertNull(client.executeEndpointRpc(MACHINE, ROUTE, noKey))
        assertNull(
            client.executeEndpointRpc(
                MACHINE,
                ROUTE,
                HerdrRpcBuilder.agentSendKeys(paneId = PANE, name = null, keys = listOf("Enter", "Home")),
            ),
        )
        assertEquals(0, batches)
        assertEquals(2, debugLog.entries.value.count { it.isError && it.message.contains("Herdr has no key named") })

        val space = HerdrRpcBuilder.paneSendKeys(paneId = PANE, keys = listOf("ctrl+space"))
        assertNotNull(client.executeEndpointRpc(MACHINE, ROUTE, space))
        assertEquals(1, batches)
    }

    @Test
    fun aPaneInputLineWithNoAnswerIsAFailureInTheDebugLog() = runBlocking {
        val debugLog = SshDebugLog()
        val client = client(FakeHerdr(silentMethods = setOf("pane.send_text")), debugLog)

        assertTrue(client.sendPaneInput(MACHINE, ROUTE, PANE, text("a").event))

        awaitRealTime { debugLog.entries.value.any { it.message.contains("gave no answer") } }
        assertTrue(debugLog.entries.value.any { it.isError && it.message.contains("gave no answer") })
    }

    @Test
    fun keyBytesNeverGoInAPaneSendInputLine() {
        // pane.send_input can put its text in a bracketed paste. Then the pane gets the bytes as text, not as a key.
        val lines = client(FakeHerdr()).buildCoalescedInputCommands(
            listOf(
                keyInput(ClientKeyCode.Home),
                text("x"),
                keyInput(ClientKeyCode.Up),
                keyInput(ClientKeyCode.Delete),
                keyInput(ClientKeyCode.Down),
            ),
        )
        assertEquals(
            listOf(
                InputRequest("pane.send_text", text = "$ESC[Hx"),
                InputRequest("pane.send_keys", keys = listOf("Up")),
                InputRequest("pane.send_text", text = "$ESC[3~"),
                InputRequest("pane.send_keys", keys = listOf("Down")),
            ),
            lines.map { inputRequest(it.ndjsonLine) },
        )
    }

    @Test
    fun onlyAnUnknownKeyIsDroppedAndTheOtherInputKeepsTheTypedOrder() = runBlocking {
        // This Herdr does not know "Down". The pane must get "Up" of the same request. The text after that request
        // must not get to the pane before "Up".
        val fake = FakeHerdr(unknownKeys = setOf("Down"), holdFirstBatch = true)
        val debugLog = SshDebugLog()
        val client = client(fake, debugLog)

        assertTrue(client.sendPaneInput(MACHINE, ROUTE, PANE, text("a").event))
        withTimeout(TIMEOUT) { fake.held.await() }
        // The first batch is held, so these four events go in one batch.
        for (item in AFTER_HELD_INPUT) {
            assertTrue(client.sendPaneInput(MACHINE, ROUTE, PANE, item.event))
        }
        fake.release.complete(Unit)

        awaitRealTime { fake.paneInput == "a<Up>$ESC[Hb" }
        assertEquals("a<Up>$ESC[Hb", fake.paneInput)
        assertEquals(
            listOf(
                InputRequest("pane.send_keys", keys = listOf("Down", "Up")),
                InputRequest("pane.send_keys", keys = listOf("Down")),
            ),
            fake.notAcceptedRequests,
        )
        assertTrue(debugLog.entries.value.any { it.isError && it.message.contains("unsupported key Down") })
    }

    @Test
    fun theRealShellPathKeepsTheTypedOrderAndStopsTheBatchAfterAnUnknownKey() = runBlocking {
        withRealShellClient { home, client, machine, route ->
            File(home, "unknown-keys").writeText("Down\n")
            File(home, "hold-patterns").writeText("$HOLD_TEXT_A\n")

            assertTrue(client.sendPaneInput(machine, route, PANE, text("a").event))
            // The fake holds the first request, so the next four events go in one batch.
            awaitFile(File(home, "held-1"))
            for (item in AFTER_HELD_INPUT) {
                assertTrue(client.sendPaneInput(machine, route, PANE, item.event))
            }
            File(home, "release-1").createNewFile()

            awaitRealTime { writtenInput(File(home, "input.log")) == "a<Up>$ESC[Hb" }
            assertEquals("a<Up>$ESC[Hb", writtenInput(File(home, "input.log")))
            assertEquals(
                listOf(
                    InputRequest("pane.send_keys", keys = listOf("Down", "Up")),
                    InputRequest("pane.send_keys", keys = listOf("Down")),
                ),
                File(home, "not-accepted.log").readLines().map(::inputRequest),
            )
        }
    }

    @Test
    fun aChordDuringTheResendOfInputArrivesAfterThatInput() = runBlocking {
        withRealShellClient { home, client, machine, route ->
            File(home, "unknown-keys").writeText("Down\n")
            File(home, "hold-patterns").writeText("$HOLD_TEXT_A\n$HOLD_DOWN_UP\n")

            assertTrue(client.sendPaneInput(machine, route, PANE, text("a").event))
            awaitFile(File(home, "held-1"))
            for (item in AFTER_HELD_INPUT) {
                assertTrue(client.sendPaneInput(machine, route, PANE, item.event))
            }
            File(home, "release-1").createNewFile()
            // The fake holds the request that it does not accept. A chord then waits for the lock of the Input lane.
            awaitFile(File(home, "held-2"))
            val chord = async(Dispatchers.IO) {
                val ctrlC = HerdrRpcBuilder.paneSendKeys(paneId = PANE, keys = listOf("ctrl+c"))
                client.executeEndpointRpc(machine, route, ctrlC)
            }
            delay(CHORD_WAIT)
            File(home, "release-2").createNewFile()

            assertNotNull(withTimeout(TIMEOUT) { chord.await() })
            awaitRealTime { writtenInput(File(home, "input.log")) == "a<Up>$ESC[Hb<ctrl+c>" }
            // The resend of "Up", Home, and "b" keeps the lane lock, so the chord comes after them.
            assertEquals("a<Up>$ESC[Hb<ctrl+c>", writtenInput(File(home, "input.log")))
        }
    }

    /**
     * Runs [block] with a client that is logged in to a [TestSshServer] with the fake Herdr of
     * [TestHerdr.FAKE_HERDR_SCRIPT]. The block gets the home of the fake, where the fake reads and writes its files.
     */
    private suspend fun withRealShellClient(
        block: suspend CoroutineScope.(
            home: File,
            client: RealHerdrSshClient,
            machine: HostMachine,
            route: ResolvedSshRoute,
        ) -> Unit,
    ) {
        TestSshServer().start().use { server ->
            val fakeHerdr = server.installHerdr(TestHerdr.FAKE_HERDR_SCRIPT)
            val backend = realBackend(server)
            val machine = server.machine(
                id = "key-input",
                username = TestSshServer.KEY_USER,
                authMethod = server.keyAuthMethod,
            )
            val route = ProxyJumpTunnel().resolveRoute(machine)
            try {
                assertEquals(SshAuthResult.Success, backend.authenticate(machine, route))
                val herdrPath = backend.client.runCommand(machine, route, "command -v herdr").trim()
                assertEquals(fakeHerdr.absolutePath, herdrPath)
                coroutineScope { block(server.commandHome, backend.client, machine, route) }
            } finally {
                backend.disconnect(machine.id)
            }
        }
    }

    /** Waits in real time until [file] exists, and then asserts it. */
    private suspend fun awaitFile(file: File) {
        awaitRealTime { file.exists() }
        assertTrue(file.exists(), "The fake did not write ${file.name}")
    }

    private fun client(fake: FakeHerdr, debugLog: SshDebugLog = SshDebugLog()) = RealHerdrSshClient(
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
        secretStore = InMemorySecretStore(),
        localNetworkGuard = permittedLocalNetwork(),
        remoteBatchExecutor = { _, _, _, commands -> fake.execute(commands) },
    )

    /** Returns a client whose batches go to [onBatch], with an empty output. */
    private fun countingClient(debugLog: SshDebugLog, onBatch: () -> Unit) = RealHerdrSshClient(
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
        secretStore = InMemorySecretStore(),
        localNetworkGuard = permittedLocalNetwork(),
        remoteBatchExecutor = { _, _, _, _ ->
            onBatch()
            ""
        },
    )

    /** Returns the bytes of the request line of [request], with no line end. */
    private fun requestBytes(request: ClientShellEndpointRequest): Int =
        HerdrRpcBuilder.toNdjsonLine(request).trim().encodeToByteArray().size

    /** Returns a [RealSshBackend] that knows the host key of [server] and logs in with its key. */
    private fun realBackend(server: TestSshServer) = RealSshBackend(
        sshStateManager = SshStateManager(),
        sshDebugLog = SshDebugLog(),
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply { runBlocking { put(server.knownHost()) } },
        secretStore = InMemorySecretStore(),
        localNetworkGuard = permittedLocalNetwork(),
    )

    /** Waits in real time, for [TIMEOUT] at most, until [condition] is true. */
    private suspend fun awaitRealTime(condition: () -> Boolean) {
        withTimeoutOrNull(TIMEOUT) {
            while (!condition()) delay(10.milliseconds)
        }
    }

    private companion object {
        const val ESC = '\u001b'
        const val PANE = "w1:p1"
        val TIMEOUT = 10.seconds

        /** The time for a chord to get to the lock of the Input lane. */
        val CHORD_WAIT = 500.milliseconds

        /** A hold pattern of the fake for the request with the typed text "a". */
        const val HOLD_TEXT_A = "\"text\":\"a\""

        /** A hold pattern of the fake for the request with the keys "Down" and "Up". */
        const val HOLD_DOWN_UP = "\"keys\":[\"Down\",\"Up\"]"

        /**
         * A text with a JSON escape or a code point of two chars in each part: 1,200,000 chars give about 2.2 MB of
         * JSON.
         */
        val LONG_TEXT = buildString { repeat(200_000) { append("a\t\"\\\uD83D\uDE00") } }
        val MACHINE = HostMachine(id = "box", name = "Box", hostname = "192.168.1.42", username = "ian")
        val ROUTE = ProxyJumpTunnel().resolveRoute(MACHINE)

        fun key(
            code: ClientKeyCode,
            ctrl: Boolean = false,
            alt: Boolean = false,
            shift: Boolean = false,
        ) = ClientKeyEvent(code = code, ctrl = ctrl, alt = alt, shift = shift)

        fun keyInput(code: ClientKeyCode) =
            RealHerdrSshClient.PendingPaneInput(seq = 0L, paneId = PANE, event = ClientPaneInputEvent.Key(key(code)))

        fun text(text: String) =
            RealHerdrSshClient.PendingPaneInput(seq = 0L, paneId = PANE, event = ClientPaneInputEvent.TextCommit(text))

        fun paste(text: String) =
            RealHerdrSshClient.PendingPaneInput(seq = 0L, paneId = PANE, event = ClientPaneInputEvent.Paste(text))

        fun requestId(line: String): String =
            Json.parseToJsonElement(line).jsonObject.getValue("id").jsonPrimitive.content

        /** The input after the held text "a": an unknown key, a named key, key bytes, and text. */
        val AFTER_HELD_INPUT = listOf(
            keyInput(ClientKeyCode.Down),
            keyInput(ClientKeyCode.Up),
            keyInput(ClientKeyCode.Home),
            text("b"),
        )

        fun inputRequest(line: String): InputRequest {
            val request = Json.parseToJsonElement(line).jsonObject
            val params = request.getValue("params").jsonObject
            return InputRequest(
                method = request.getValue("method").jsonPrimitive.content,
                text = params["text"]?.jsonPrimitive?.content.orEmpty(),
                keys = params["keys"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        }

        /** Returns the input in the requests of [log], in the form of [FakeHerdr.paneInput]. */
        fun writtenInput(log: File): String = if (!log.exists()) {
            ""
        } else {
            log.readLines().joinToString("") { line ->
                val request = inputRequest(line)
                request.text + request.keys.joinToString("") { "<$it>" }
            }
        }
    }
}
