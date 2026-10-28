package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.apache.sshd.common.config.keys.PublicKeyEntry

/**
 * Tests the [TestSshServer] features that later tests use: a restart on the same port, a new host key, and the
 * environment of an `exec` command.
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
    fun anExecCommandGetsTheHomeOfTheServerAndTheStubHerdr() {
        val output = exec(
            "echo \"home=\$HOME\"; env | grep -c '^HERDR_' || true; herdr api snapshot; echo \"herdr=\$?\"",
        )
        assertEquals(
            listOf("home=${server.homeDirectory.absolutePath}", "0", "herdr=127"),
            output.lines().filter { it.isNotBlank() },
        )

        server.installHerdr("#!/bin/sh\necho fake \"\$@\"\n")
        assertEquals("fake api snapshot", exec("herdr api snapshot").trim())
    }

    /** Runs [command] on an `exec` channel with plain JSch, and returns its standard output. */
    private fun exec(command: String): String {
        val session = JSch().getSession(TestSshServer.PASSWORD_USER, TestSshServer.HOST, server.port)
        session.setPassword(TestSshServer.PASSWORD.encodeToByteArray())
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        session.connect(CONNECT_TIMEOUT_MS)
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stdout = channel.inputStream
            channel.connect(CONNECT_TIMEOUT_MS)
            return stdout.readBytes().toString(Charsets.UTF_8).also { channel.disconnect() }
        } finally {
            session.disconnect()
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
    }
}
