package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Tests the Herdr session of each `herdr` call that [RealHerdrSshClient] makes on a real SSH server ([TestSshServer]).
 *
 * The server runs each command with [home] as `HOME`. The workers of the client put `$HOME/.local/bin` first on the
 * `PATH`. Thus they find the fake `herdr` of [FAKE_HERDR], and not a `herdr` of the test machine. Each test also
 * checks this path before it sends a request to Herdr. The fake writes the arguments of each call to [callLog] and
 * gives a small answer.
 */
class HerdrSessionTest {

    private val home = createTempDirectory("bison-herdr-home").toFile()
    private val fakeHerdr = File(home, ".local/bin/herdr")
    private val callLog = File(home, "herdr-calls")
    private val server = TestSshServer().apply { homeDirectory = home }.start()
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply {
        runBlocking { put(server.knownHost()) }
    }
    private val debugLog = SshDebugLog()
    private val backend = RealSshBackend(
        localNetworkGuard = permittedLocalNetwork(),
        sshStateManager = SshStateManager(),
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = knownHostsStore,
        secretStore = InMemorySecretStore(),
    )
    private val machines = mutableListOf<HostMachine>()

    init {
        File(home, ".local/bin").mkdirs()
        fakeHerdr.writeText(FAKE_HERDR)
        // A command must not find a different herdr, for example when the temporary directory does not allow programs.
        check(fakeHerdr.setExecutable(true)) { "Cannot make $fakeHerdr executable" }
        check(fakeHerdr.canExecute()) { "Cannot run $fakeHerdr" }
        File(home, "snapshot.json").writeText(HerdrCommandTest.SNAPSHOT_JSON + "\n")
        File(home, "read.json").writeText(PANE_READ_JSON + "\n")
    }

    @AfterTest
    fun tearDown() {
        machines.forEach { backend.disconnect(it.id) }
        server.close()
        home.deleteRecursively()
    }

    @Test
    fun eachHerdrCallOfANamedMachineSelectsItsSession() = runTest {
        val calls = runControlLane(sessionName = "work")

        assertEquals(
            listOf(
                listOf("--session", "work", "--version"),
                listOf("--session", "work", "api", "snapshot"),
                // The poll reads the pane.
                listOf("--session", "work", "remote-api-bridge"),
                // The input lane sends the keys and reads the pane.
                listOf("--session", "work", "remote-api-bridge"),
                listOf("--session", "work", "remote-api-bridge"),
            ),
            calls,
            debugLogDump(),
        )
    }

    @Test
    fun eachHerdrCallOfAMachineWithNoSessionNameUsesTheDefaultSession() = runTest {
        val calls = runControlLane(sessionName = null)

        assertEquals(
            listOf(
                listOf("--version"),
                listOf("api", "snapshot"),
                listOf("remote-api-bridge"),
                listOf("remote-api-bridge"),
                listOf("remote-api-bridge"),
            ),
            calls,
            debugLogDump(),
        )
    }

    @Test
    fun aSessionNameWithShellCharactersGetsToHerdrAsOneArgument() = runTest {
        val name = "it's \$HOME; `true`"
        val calls = runControlLane(sessionName = name)

        assertEquals(5, calls.size, debugLogDump())
        calls.forEach { call -> assertEquals(listOf("--session", name), call.take(2), debugLogDump()) }
    }

    @Test
    fun theNewWorkerAndTheOneShotFallbackKeepTheSessionOfTheMachine() = runTest {
        val (machine, route) = connect(sessionName = "work")
        // The fake stops the worker in the first two calls of remote-api-bridge.
        File(home, "kill-workers").writeText("2\n")

        val answer = backend.sendEndpointCommand(machine, route, SEND_ENTER, isMoshActive = false)

        assertNotNull(answer, debugLogDump())
        assertEquals(
            listOf(
                listOf("--session", "work", "--version"),
                // The fake stops the Input worker. The client opens a new worker and sends the batch again.
                listOf("--session", "work", "remote-api-bridge"),
                // The fake stops the new worker too. The client sends the batch on a one-shot channel.
                listOf("--session", "work", "remote-api-bridge"),
                listOf("--session", "work", "remote-api-bridge"),
                listOf("--session", "work", "remote-api-bridge"),
            ),
            readCalls(),
            debugLogDump(),
        )
    }

    @Test
    fun theDebugLogShowsTheErrorThatHerdrWritesToStandardError() = runTest {
        val (machine, route) = connect(sessionName = "work")
        val error = File(home, "snapshot-error")
        error.writeText(SERVER_NOT_RUNNING_JSON + "\n")

        // Herdr fails two polls. The client writes one error for the two polls.
        assertFalse(backend.client.refreshSnapshotAndSurface(machine, route))
        assertFalse(backend.client.refreshSnapshotAndSurface(machine, route))
        val prefix = "No snapshot from `herdr --session 'work' api snapshot`: "
        assertEquals(listOf(prefix + "no herdr server is running"), snapshotErrors(), debugLogDump())

        // A poll with a snapshot ends the series, also with a warning on standard error.
        error.delete()
        File(home, "snapshot-warning").writeText("warning: unknown key in config\n")
        assertTrue(backend.client.refreshSnapshotAndSurface(machine, route), debugLogDump())

        // The next poll that fails writes a new error. With no JSON answer, the error is the last line of text.
        error.writeText("error: no server for this test\n")
        assertFalse(backend.client.refreshSnapshotAndSurface(machine, route))
        assertEquals(
            listOf(prefix + "no herdr server is running", prefix + "error: no server for this test"),
            snapshotErrors(),
            debugLogDump(),
        )
    }

    /**
     * Connects a machine with the Herdr session [sessionName], runs one poll, and sends one key to the pane. Returns
     * the arguments of each `herdr` call, in order.
     */
    private suspend fun runControlLane(sessionName: String?): List<List<String>> {
        val (machine, route) = connect(sessionName)

        assertTrue(backend.client.refreshSnapshotAndSurface(machine, route), debugLogDump())
        val answer = backend.sendEndpointCommand(machine, route, SEND_ENTER, isMoshActive = false)
        assertNotNull(answer, debugLogDump())
        return readCalls()
    }

    /**
     * Logs in to a new machine with the Herdr session [sessionName]. The login runs `herdr --version`. Then the
     * function checks that the shell of the client finds [fakeHerdr], and not a `herdr` of the test machine.
     */
    private suspend fun connect(sessionName: String?): Pair<HostMachine, ResolvedSshRoute> {
        val machine = server.machine(
            id = "session-${machines.size}",
            username = TestSshServer.KEY_USER,
            authMethod = server.keyAuthMethod,
        ).copy(herdrSessionName = sessionName)
        machines += machine
        val route = ProxyJumpTunnel().resolveRoute(machine)

        assertEquals(SshAuthResult.Success, backend.authenticate(machine, route), debugLogDump())
        assertEquals(fakeHerdr.absolutePath, backend.client.runCommand(machine, route, "command -v herdr").trim())
        return machine to route
    }

    /** Reads [callLog]. The fake writes each argument on its own line, and [CALL_END] after the last argument. */
    private fun readCalls(): List<List<String>> {
        if (!callLog.exists()) return emptyList()
        val calls = mutableListOf<List<String>>()
        var call = mutableListOf<String>()
        callLog.readLines().forEach { line ->
            if (line == CALL_END) {
                calls += call
                call = mutableListOf()
            } else {
                call += line
            }
        }
        return calls
    }

    /** Returns the errors in the debug log for a poll with no snapshot. */
    private fun snapshotErrors(): List<String> =
        debugLog.entries.value.filter { it.isError && it.message.startsWith("No snapshot") }.map { it.message }

    private fun debugLogDump(): String = debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        /** The line after the arguments of one call in [callLog]. */
        const val CALL_END = "@end"

        /** The answer of `pane.read` for the pane `w1:p1`. */
        const val PANE_READ_JSON =
            """{"id":"read_w1:p1","result":{"type":"pane_read","read":{"pane_id":"w1:p1","text":"ready"}}}"""

        /** The answer of `herdr api snapshot` when the session has no server. Herdr writes it to standard error. */
        const val SERVER_NOT_RUNNING_JSON =
            """{"id":"cli:api:snapshot","error":{"code":"server_not_running","message":"no herdr server is running"}}"""

        /** A request that sends Enter to the pane `w1:p1` on the Input lane. */
        val SEND_ENTER = ClientShellEndpointRequest(
            id = "keys",
            method = "pane.send_keys",
            paramsJson = """{"pane_id":"w1:p1","keys":["Enter"]}""",
        )

        /**
         * A fake `herdr` for POSIX `sh`. It writes its arguments to `$HOME/herdr-calls`, one line for each argument.
         * It answers `--version`, `api snapshot`, and one request of `remote-api-bridge`.
         *
         * Three files change its answers:
         * - `$HOME/snapshot-error`: `api snapshot` writes this text to standard error and exits with code 1, as Herdr
         *   does for an error answer.
         * - `$HOME/snapshot-warning`: `api snapshot` writes this text to standard error before a good answer.
         * - `$HOME/kill-workers`: the first N calls of `remote-api-bridge` stop the shell that runs them. The fake
         *   stops only a process with the name `sh`.
         */
        val FAKE_HERDR = """
            #!/bin/sh
            printf '%s\n' "${'$'}@" '$CALL_END' >> "${'$'}HOME/herdr-calls"
            case " ${'$'}* " in
                *" --version "*) echo 'herdr 0.9.5' ;;
                *" api snapshot "*)
                    if [ -e "${'$'}HOME/snapshot-error" ]; then
                        cat "${'$'}HOME/snapshot-error" >&2
                        exit 1
                    fi
                    if [ -e "${'$'}HOME/snapshot-warning" ]; then
                        cat "${'$'}HOME/snapshot-warning" >&2
                    fi
                    cat "${'$'}HOME/snapshot.json"
                    ;;
                *" remote-api-bridge "*)
                    count=${'$'}(( ${'$'}(cat "${'$'}HOME/bridge-count" 2>/dev/null || echo 0) + 1 ))
                    echo "${'$'}count" > "${'$'}HOME/bridge-count"
                    if [ "${'$'}count" -le "${'$'}(cat "${'$'}HOME/kill-workers" 2>/dev/null || echo 0)" ]; then
                        case ${'$'}(ps -o comm= -p "${'$'}PPID") in
                            sh | */sh) kill -KILL "${'$'}PPID" ;;
                        esac
                        exit 1
                    fi
                    IFS= read -r request
                    case ${'$'}request in
                        *'"pane.read"'*) cat "${'$'}HOME/read.json" ;;
                        *) echo '{"id":"keys","result":{"type":"ok"}}' ;;
                    esac
                    ;;
            esac
        """.trimIndent() + "\n"
    }
}
