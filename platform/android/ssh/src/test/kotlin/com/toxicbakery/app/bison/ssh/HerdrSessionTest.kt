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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Tests the Herdr session of each `herdr` call that [RealHerdrSshClient] makes on a real SSH server ([TestSshServer]).
 *
 * The server runs each command with [home] as `HOME`. The workers of the client put `$HOME/.local/bin` first on the
 * `PATH`, so they find the fake `herdr` of [FAKE_HERDR] and not a `herdr` of the test machine. The fake writes the
 * arguments of each call to [callLog] and gives a small answer.
 */
class HerdrSessionTest {

    private val home = createTempDirectory("bison-herdr-home").toFile()
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
        File(home, ".local/bin/herdr").apply {
            writeText(FAKE_HERDR)
            setExecutable(true)
        }
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

    /**
     * Connects a machine with the Herdr session [sessionName], runs one poll, and sends one key to the pane. Returns
     * the arguments of each `herdr` call, in order.
     */
    private suspend fun runControlLane(sessionName: String?): List<List<String>> {
        val machine = server.machine(
            id = "session-${machines.size}",
            username = TestSshServer.KEY_USER,
            authMethod = server.keyAuthMethod,
        ).copy(herdrSessionName = sessionName)
        machines += machine
        val route = ProxyJumpTunnel().resolveRoute(machine)

        assertEquals(SshAuthResult.Success, backend.authenticate(machine, route), debugLogDump())
        assertTrue(backend.client.refreshSnapshotAndSurface(machine, route), debugLogDump())
        val answer = backend.sendEndpointCommand(
            machine = machine,
            route = route,
            request = ClientShellEndpointRequest(
                id = "keys",
                method = "pane.send_keys",
                paramsJson = """{"pane_id":"w1:p1","keys":["Enter"]}""",
            ),
            isMoshActive = false,
        )
        assertNotNull(answer, debugLogDump())
        return readCalls()
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

    private fun debugLogDump(): String = debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        /** The line after the arguments of one call in [callLog]. */
        const val CALL_END = "@end"

        /** The answer of `pane.read` for the pane `w1:p1`. */
        const val PANE_READ_JSON =
            """{"id":"read_w1:p1","result":{"type":"pane_read","read":{"pane_id":"w1:p1","text":"ready"}}}"""

        /**
         * A fake `herdr` for POSIX `sh`. It writes its arguments to `$HOME/herdr-calls`, one line for each argument.
         * It answers `--version`, `api snapshot`, and one request of `remote-api-bridge`.
         */
        val FAKE_HERDR = """
            #!/bin/sh
            printf '%s\n' "${'$'}@" '$CALL_END' >> "${'$'}HOME/herdr-calls"
            case " ${'$'}* " in
                *" --version "*) echo 'herdr 0.9.5' ;;
                *" api snapshot "*) cat "${'$'}HOME/snapshot.json" ;;
                *" remote-api-bridge "*)
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
