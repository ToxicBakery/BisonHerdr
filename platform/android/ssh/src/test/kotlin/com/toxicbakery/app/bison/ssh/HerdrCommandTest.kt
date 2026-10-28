package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests the Herdr session of each `herdr` command of [RealHerdrSshClient]. A machine with a Herdr session name gets
 * `--session <name>`. A machine with no name gets no `--session`. [HerdrSessionTest] runs the same commands on a real
 * SSH server.
 */
class HerdrCommandTest {

    @Test
    fun aMachineWithNoSessionNameUsesTheDefaultSession() {
        assertEquals("herdr", HerdrCommand.forSession(null))
        assertEquals("herdr", HerdrCommand.forSession(""))
        assertEquals("herdr", HerdrCommand.forSession("   "))
        assertEquals("herdr", HerdrCommand.of(machine(sessionName = null)))
    }

    @Test
    fun aSessionNameAddsTheSessionFlagBeforeTheSubcommand() {
        assertEquals("herdr --session 'work'", HerdrCommand.forSession("work"))
        assertEquals("herdr --session 'work'", HerdrCommand.forSession("  work "))
        assertEquals("herdr --session 'work'", HerdrCommand.of(machine(sessionName = "work")))
    }

    @Test
    fun theShellGivesAQuotedValueToTheCommandAsOneArgument() {
        val value = "it's \$HOME; `id` \"a b\" \\ * \$(id)"
        val process = ProcessBuilder("sh", "-c", "printf '%s' ${HerdrCommand.shellQuote(value)}").start()
        val output = process.inputStream.readBytes().decodeToString()

        assertEquals(0, process.waitFor())
        assertEquals(value, output)
    }

    @Test
    fun theWorkerScriptStartsEachApiBridgeCallWithTheSessionOfTheMachine() {
        val (script, _) = client().buildShellBatchScript(BATCH_ID, BATCH, HerdrCommand.forSession("work"))

        assertEquals(
            listOf(
                "herdr --session 'work' remote-api-bridge <<'__BISON_EOF_7_1__'",
                "herdr --session 'work' remote-api-bridge <<'__BISON_EOF_7_2__'",
            ),
            herdrLines(script),
        )
    }

    @Test
    fun theWorkerScriptHasNoSessionFlagForAMachineWithNoSessionName() {
        val (script, _) = client().buildShellBatchScript(BATCH_ID, BATCH, HerdrCommand.forSession(null))

        assertEquals(
            listOf(
                "herdr remote-api-bridge <<'__BISON_EOF_7_1__'",
                "herdr remote-api-bridge <<'__BISON_EOF_7_2__'",
            ),
            herdrLines(script),
        )
    }

    @Test
    fun theOneShotScriptStartsEachApiBridgeCallWithTheSessionOfTheMachine() {
        val script = client().buildOneShotBatchScript(BATCH_ID, BATCH, HerdrCommand.forSession("work"))

        assertEquals(
            listOf(
                "herdr --session 'work' remote-api-bridge <<'__BISON_EOF_7_1__'",
                "herdr --session 'work' remote-api-bridge <<'__BISON_EOF_7_2__'",
            ),
            herdrLines(script),
        )
    }

    @Test
    fun theOneShotScriptHasNoSessionFlagForAMachineWithNoSessionName() {
        val script = client().buildOneShotBatchScript(BATCH_ID, BATCH, HerdrCommand.forSession(null))

        assertEquals(
            listOf(
                "herdr remote-api-bridge <<'__BISON_EOF_7_1__'",
                "herdr remote-api-bridge <<'__BISON_EOF_7_2__'",
            ),
            herdrLines(script),
        )
    }

    @Test
    fun theConnectCheckAndTheSnapshotUseTheSessionOfTheMachine() = runTest {
        assertEquals(
            listOf("herdr --session 'work' --version || true", "herdr --session 'work' api snapshot"),
            execCommands(machine(sessionName = "work")),
        )
    }

    @Test
    fun theConnectCheckAndTheSnapshotHaveNoSessionFlagForAMachineWithNoSessionName() = runTest {
        assertEquals(
            listOf("herdr --version || true", "herdr api snapshot"),
            execCommands(machine(sessionName = null)),
        )
    }

    @Test
    fun aPollWithNoSnapshotTellsWhenHerdrDoesNotAcceptTheSessionName() = runTest {
        val debugLog = SshDebugLog()
        val machine = machine(sessionName = "my work")
        // Herdr writes the error only to standard error, so the batch has no output.
        val client = client(debugLog) { "" }

        assertFalse(client.refreshSnapshotAndSurface(machine, ProxyJumpTunnel().resolveRoute(machine)))

        assertEquals(
            listOf(
                "No snapshot from `herdr --session 'my work' api snapshot`: Herdr does not accept the session name " +
                    "\"my work\". Change the name in the machine settings.",
            ),
            snapshotErrors(debugLog),
        )
    }

    @Test
    fun aPollWithNoSnapshotWritesTheErrorOfHerdrOnceForEachSeries() = runTest {
        val debugLog = SshDebugLog()
        val machine = machine(sessionName = "work")
        val route = ProxyJumpTunnel().resolveRoute(machine)
        var snapshotAnswer = SERVER_NOT_RUNNING_JSON
        val client = client(debugLog) { batch ->
            if (batch.any { it is RealHerdrSshClient.RemoteShellCommand.Exec }) snapshotAnswer + "\n" else ""
        }
        val expected = "No snapshot from `herdr --session 'work' api snapshot`: no herdr server is running"

        assertFalse(client.refreshSnapshotAndSurface(machine, route))
        assertFalse(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(listOf(expected), snapshotErrors(debugLog))

        // A poll with a snapshot ends the series. The next poll that fails writes a new error.
        snapshotAnswer = SNAPSHOT_JSON
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        snapshotAnswer = SERVER_NOT_RUNNING_JSON
        assertFalse(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(listOf(expected, expected), snapshotErrors(debugLog))
    }

    /** Returns the errors in [debugLog] for a poll with no snapshot. */
    private fun snapshotErrors(debugLog: SshDebugLog): List<String> =
        debugLog.entries.value.filter { it.isError && it.message.startsWith("No snapshot") }.map { it.message }

    /** Runs the connect check and one poll of [machine]. Returns the text of each `Exec` command, in order. */
    private suspend fun execCommands(machine: HostMachine): List<String> {
        val commands = CopyOnWriteArrayList<RealHerdrSshClient.RemoteShellCommand>()
        val client = client { batch ->
            commands += batch
            if (batch.any { it is RealHerdrSshClient.RemoteShellCommand.Exec && it.command.endsWith(" api snapshot") }) {
                SNAPSHOT_JSON + "\n"
            } else {
                ""
            }
        }
        val route = ProxyJumpTunnel().resolveRoute(machine)

        assertTrue(client.connectAndVerify(machine, route).isSuccess)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        client.disconnect(machine.id)
        return commands.filterIsInstance<RealHerdrSshClient.RemoteShellCommand.Exec>().map { it.command }
    }

    private fun client(
        debugLog: SshDebugLog = SshDebugLog(),
        execute: (List<RealHerdrSshClient.RemoteShellCommand>) -> String = { "" },
    ) = RealHerdrSshClient(
        localNetworkGuard = permittedLocalNetwork(),
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
        secretStore = InMemorySecretStore(),
        remoteBatchExecutor = { _, _, _, commands -> execute(commands) },
    )

    private fun machine(sessionName: String?) = HostMachine(
        id = "box",
        name = "box",
        hostname = "192.168.1.42",
        username = "ian",
        herdrSessionName = sessionName,
    )

    /** Returns the lines of [script] that run `herdr`. */
    private fun herdrLines(script: String): List<String> = script.lines().filter { it.startsWith("herdr") }

    internal companion object {
        const val BATCH_ID = 7L

        /** The answer of `herdr api snapshot` when the session has no server. */
        const val SERVER_NOT_RUNNING_JSON =
            """{"id":"cli:api:snapshot","error":{"code":"server_not_running","message":"no herdr server is running"}}"""

        /** The answer of `herdr api snapshot` for a host with one pane, `w1:p1`. */
        const val SNAPSHOT_JSON =
            """{"id":"snap","result":{"type":"session_snapshot","snapshot":{"focused_workspace_id":"w1",""" +
                """"focused_tab_id":"w1:t1","focused_pane_id":"w1:p1",""" +
                """"workspaces":[{"workspace_id":"w1","active_tab_id":"w1:t1","label":"main","focused":true}],""" +
                """"tabs":[{"tab_id":"w1:t1","workspace_id":"w1","number":1,"label":"1","focused":true}],""" +
                """"panes":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","focused":true}],"agents":[],""" +
                """"layouts":[{"workspace_id":"w1","tab_id":"w1:t1","zoomed":false,"panes":[{"pane_id":"w1:p1",""" +
                """"rect":{"x":0,"y":0,"width":80,"height":24},"focused":true}],"splits":[]}]}}}"""

        /** A batch with one shell command and two Herdr API requests. */
        val BATCH = listOf(
            RealHerdrSshClient.RemoteShellCommand.Exec("printf 'start\\n'"),
            RealHerdrSshClient.RemoteShellCommand.ApiBridgeLine("""{"id":"a","method":"pane.read","params":{}}"""),
            RealHerdrSshClient.RemoteShellCommand.ApiBridgeLine("""{"id":"b","method":"pane.send_keys","params":{}}"""),
        )
    }
}
