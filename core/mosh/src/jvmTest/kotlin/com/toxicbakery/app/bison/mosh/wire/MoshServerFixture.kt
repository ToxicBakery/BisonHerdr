package com.toxicbakery.app.bison.mosh.wire

import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.fail
import org.junit.AssumptionViolatedException

/**
 * One real `mosh-server new -i 127.0.0.1 -- <command>` for a test. The server picks a free UDP port.
 *
 * Use the fixture with `use`, so that the server stops also when the test fails. A shutdown hook stops the server
 * when the test JVM exits before [close]. The server also gets `MOSH_SERVER_NETWORK_TMOUT`, so it stops by itself
 * after a silence when both of these steps fail.
 *
 * When `mosh-server` is not on the `PATH`, [start] skips the test. When the environment variable
 * [REQUIRE_VARIABLE] is `1`, [start] fails the test in place of the skip. CI sets it.
 */
class MoshServerFixture private constructor(
    /** The UDP port of the server. */
    val port: Int,
    /** The session key of the server. */
    val key: MoshKey,
    /** The process ID of the detached server. */
    val pid: Long,
) : AutoCloseable {

    /** The address of the server. */
    val address: InetSocketAddress = InetSocketAddress(LOOPBACK, port)

    private val shutdownHook = Thread { stop() }

    /** True when the server process is not alive. */
    val isStopped: Boolean get() = ProcessHandle.of(pid).map { handle -> !handle.isAlive }.orElse(true)

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /** Stops the server and the processes that it started, and waits until they exit. */
    override fun close() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // The JVM shuts down, and the hook runs.
        }
        stop()
    }

    private fun stop() {
        val server = ProcessHandle.of(pid).orElse(null) ?: return
        // Stop only the process that the banner named, and only while it is still a mosh-server.
        if (!server.isMoshServer()) return
        val children = server.descendants().toList()
        (listOf(server) + children).forEach { process -> process.stop() }
    }

    companion object {
        /** When this environment variable is `1`, a test fails when `mosh-server` is absent. */
        const val REQUIRE_VARIABLE: String = "BISON_REQUIRE_MOSH"

        private const val SERVER_NAME = "mosh-server"
        private const val START_TIMEOUT_SECONDS = 10L
        private const val STOP_TIMEOUT_SECONDS = 10L

        /** A server with a silent client stops after this time, also when the test cannot stop it. */
        private const val NETWORK_TIMEOUT_SECONDS = "30"

        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private val CONNECT_LINE = Regex("""^MOSH CONNECT (\d+) (\S+)$""", RegexOption.MULTILINE)
        private val DETACHED_LINE = Regex("""^\[mosh-server detached, pid = (\d+)]$""", RegexOption.MULTILINE)

        /**
         * Starts `mosh-server new -i 127.0.0.1 -- command`. Reads `MOSH CONNECT <port> <key>` from stdout and
         * `[mosh-server detached, pid = <pid>]` from stderr (wire fact 1).
         */
        fun start(vararg command: String): MoshServerFixture {
            val server = serverPath()
            val process = ProcessBuilder(listOf(server, "new", "-i", LOOPBACK.hostAddress, "--") + command)
                .apply {
                    environment()["LANG"] = "C.UTF-8"
                    environment()["LC_ALL"] = "C.UTF-8"
                    environment()["MOSH_SERVER_NETWORK_TMOUT"] = NETWORK_TIMEOUT_SECONDS
                }
                .start()
            process.outputStream.close()
            val stdout = process.inputStream.readInBackground()
            val stderr = process.errorStream.readInBackground()
            val exited = process.waitFor(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val out = stdout.await()
            val err = stderr.await()
            // Find the PID first, so that a later failure cannot leave the server alive.
            val pid = DETACHED_LINE.find(err)?.groupValues?.get(1)?.toLong()
            val connect = CONNECT_LINE.find(out)
            val key = connect?.groupValues?.get(2)?.let(MoshKey::parse)
            if (pid == null || connect == null || key == null || !exited) {
                pid?.let { ProcessHandle.of(it).ifPresent { handle -> if (handle.isMoshServer()) handle.stop() } }
                if (!exited) process.destroyForcibly()
                fail("mosh-server did not start as expected.\nstdout: $out\nstderr: $err")
            }
            return MoshServerFixture(port = connect.groupValues[1].toInt(), key = key, pid = pid)
        }

        private fun serverPath(): String {
            val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { directory -> File(directory, SERVER_NAME) }
                .firstOrNull { file -> file.isFile && file.canExecute() }
            if (path != null) return path.absolutePath
            val message = "$SERVER_NAME is not on the PATH."
            if (System.getenv(REQUIRE_VARIABLE) == "1") fail("$message $REQUIRE_VARIABLE is 1, so the test fails.")
            throw AssumptionViolatedException("$message Set $REQUIRE_VARIABLE=1 to fail in place of a skip.")
        }

        private fun ProcessHandle.isMoshServer(): Boolean =
            info().command().map { command -> File(command).name == SERVER_NAME }.orElse(false)

        private fun ProcessHandle.stop() {
            if (!isAlive) return
            destroy()
            if (awaitExit()) return
            destroyForcibly()
            awaitExit()
        }

        private fun ProcessHandle.awaitExit(): Boolean = try {
            onExit().get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (_: TimeoutException) {
            false
        }

        private fun InputStream.readInBackground(): BackgroundText {
            val result = BackgroundText()
            result.thread = thread(isDaemon = true, name = "mosh-server-output") {
                result.text = use { stream -> stream.readBytes().decodeToString() }
            }
            return result
        }

        private class BackgroundText {
            lateinit var thread: Thread

            @Volatile
            var text: String = ""

            fun await(): String {
                thread.join(TimeUnit.SECONDS.toMillis(START_TIMEOUT_SECONDS))
                return text
            }
        }
    }
}
