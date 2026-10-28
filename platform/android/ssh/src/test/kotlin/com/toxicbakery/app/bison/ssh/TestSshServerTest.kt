package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.PublicKeyEntry

/**
 * Tests the [TestSshServer] features that later tests use: a restart on the same port, a new host key, and a command
 * environment that never gets to a real `herdr` of the test machine.
 */
class TestSshServerTest {

    private val server = TestSshServer().start()

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun restartKeepsThePort() {
        val firstPort = server.port
        assertEquals(hostKeyBase64(), passwordLoginHostKey())

        server.stop()
        server.start()

        assertEquals(firstPort, server.port)
        assertEquals(hostKeyBase64(), passwordLoginHostKey())
    }

    @Test
    fun newHostKeyGoesToTheNextSession() {
        val firstKey = passwordLoginHostKey()
        assertEquals(hostKeyBase64(), firstKey)

        server.hostKey = TestSshServer.newHostKey()

        val secondKey = passwordLoginHostKey()
        assertEquals(hostKeyBase64(), secondKey)
        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun aCommandOfTheClientFindsOnlyTheHerdrOfTheServerHome() {
        val lines = runOnClientWorker(HERDR_REPORT)

        assertEquals(
            listOf(
                File(server.commandHome, TestHerdr.HERDR_PATH).absolutePath,
                // The herdr of the server acts as a host with no herdr, as on CI.
                "exit=127",
                // The command has no HERDR_SOCKET_PATH or other HERDR_ variable of the test machine.
                "0",
                "home=${server.commandHome.absolutePath}",
                "config=${File(server.commandHome, ".config").absolutePath}",
            ),
            lines,
        )
    }

    @Test
    fun aHomeOfTheTestGetsTheHerdrOfTheServerWhenItHasNoHerdr() {
        val home = createTempDirectory("bison-test-home").toFile()
        try {
            server.homeDirectory = home

            val lines = runOnClientWorker(HERDR_REPORT)

            assertEquals(File(home, TestHerdr.HERDR_PATH).absolutePath, lines.first())
            assertEquals("exit=127", lines[1])
        } finally {
            server.homeDirectory = null
            home.deleteRecursively()
        }
    }

    @Test
    fun theClientWorkerPutsTheHerdrDirectoriesOfTheHomeFirstOnThePath() {
        val home = server.commandHome.absolutePath

        // The server gives each command only the fixed base PATH. Thus only the export of the worker puts the home
        // first.
        assertEquals(TestSshServer.BASE_PATH, execWithJsch(server, "echo \"\$PATH\"").split(':'))
        assertEquals(
            listOf("$home/.local/bin", "$home/.cargo/bin", "/usr/local/bin") + TestSshServer.BASE_PATH,
            runOnClientWorker("echo \"\$PATH\"").single().split(':'),
        )
    }

    @Test
    fun manyFirstCommandsAtTheSameTimeEachFindAnExecutableHerdr() {
        val pool = Executors.newFixedThreadPool(CONCURRENT_COMMANDS)
        try {
            repeat(CONCURRENCY_ROUNDS) { round ->
                val home = createTempDirectory("bison-test-home").toFile()
                try {
                    TestSshServer().start().use { fresh ->
                        // Half of the rounds use a home of the test, which gets the stub when the test sets it.
                        if (round % 2 == 1) fresh.homeDirectory = home
                        val start = CountDownLatch(1)
                        val results = (1..CONCURRENT_COMMANDS).map {
                            pool.submit(
                                Callable {
                                    start.await()
                                    execWithJsch(fresh, "\"\$HOME/.local/bin/herdr\"; echo \"exit=\$?\"")
                                },
                            )
                        }
                        start.countDown()
                        assertEquals(List(CONCURRENT_COMMANDS) { "exit=127" }, results.map { it.get() }, "Round $round")
                    }
                } finally {
                    home.deleteRecursively()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Logs in to [target] as [TestSshServer.PASSWORD_USER] with plain JSch, runs [command] in an `exec` channel, and
     * returns its output with no spaces at the ends. This path has no client worker.
     */
    private fun execWithJsch(target: TestSshServer, command: String): String {
        val session = JSch().getSession(TestSshServer.PASSWORD_USER, TestSshServer.HOST, target.port)
        session.setPassword(TestSshServer.PASSWORD.encodeToByteArray())
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        session.connect(CONNECT_TIMEOUT_MS)
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val output = channel.inputStream
            channel.connect(CONNECT_TIMEOUT_MS)
            return output.readBytes().decodeToString().trim()
        } finally {
            session.disconnect()
        }
    }

    /**
     * Logs in with [RealSshBackend], runs [command] on a worker of the client, and returns the lines of the output that
     * are not blank. The worker puts `$HOME/.local/bin`, `$HOME/.cargo/bin`, and `/usr/local/bin` before the `PATH`
     * of the server.
     */
    private fun runOnClientWorker(command: String): List<String> = runBlocking {
        val backend = RealSshBackend(
            sshStateManager = SshStateManager(),
            sshDebugLog = SshDebugLog(),
            endpointSnapshotBus = EndpointSnapshotBus(),
            paneSurfaceBus = PaneSurfaceBus(),
            semanticNotificationBus = SemanticNotificationBus(),
            keystoreKeyManager = server.keyManager,
            knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply { put(server.knownHost()) },
            secretStore = InMemorySecretStore(),
            localNetworkGuard = permittedLocalNetwork(),
        )
        val machine = server.machine(
            id = "herdr-guard",
            username = TestSshServer.KEY_USER,
            authMethod = server.keyAuthMethod,
        )
        val route = ProxyJumpTunnel().resolveRoute(machine)
        try {
            assertEquals(SshAuthResult.Success, backend.authenticate(machine, route))
            backend.client.runCommand(machine, route, command).lines().filter { it.isNotBlank() }
        } finally {
            backend.disconnect(machine.id)
        }
    }

    /** Logs in as [TestSshServer.PASSWORD_USER] with plain JSch and returns the host key that the server sent. */
    private fun passwordLoginHostKey(): String {
        val session = JSch().getSession(TestSshServer.PASSWORD_USER, TestSshServer.HOST, server.port)
        session.setPassword(TestSshServer.PASSWORD.encodeToByteArray())
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        session.connect(CONNECT_TIMEOUT_MS)
        try {
            return session.hostKey.key
        } finally {
            session.disconnect()
        }
    }

    /** Returns the Base64 key blob of [TestSshServer.hostKey], as JSch shows it. */
    private fun hostKeyBase64(): String =
        PublicKeyEntry.toString(server.hostKey.public).substringAfter(' ')

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000

        /** The commands that start at the same time on a new server. */
        const val CONCURRENT_COMMANDS = 8

        /** The number of new servers in [manyFirstCommandsAtTheSameTimeEachFindAnExecutableHerdr]. */
        const val CONCURRENCY_ROUNDS = 10

        /**
         * Writes the path of `herdr`, its exit status, the number of `HERDR_` variables, `HOME`, and the Herdr
         * configuration home.
         */
        const val HERDR_REPORT = "command -v herdr; herdr --version; echo \"exit=\$?\"; " +
            "env | grep -c '^HERDR_'; echo \"home=\$HOME\"; echo \"config=\$XDG_CONFIG_HOME\""
    }
}
