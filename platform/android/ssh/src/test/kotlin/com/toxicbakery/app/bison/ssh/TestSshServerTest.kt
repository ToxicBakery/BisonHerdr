package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.apache.sshd.common.config.keys.PublicKeyEntry

/**
 * Tests the [TestSshServer] features that later tests use: a restart on the same port and a new host key.
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
