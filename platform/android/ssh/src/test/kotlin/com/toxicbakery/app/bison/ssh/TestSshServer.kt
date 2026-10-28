package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.RejectAllForwardingFilter
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.InvertedShellWrapper
import org.apache.sshd.server.shell.ProcessShell

/**
 * An Apache MINA SSHD server on [HOST] for JVM tests of the real SSH path.
 *
 * Each instance has its own in-memory [hostKey]. The first [start] binds a random free port. A [start]
 * after [stop] binds the same [port] again.
 *
 * The server accepts three users:
 * - [PASSWORD_USER] with [PASSWORD] (`password` method).
 * - [KEY_USER] with a key in [authorizedKeys] (`publickey` method). The constructor adds the [KEY_ALIAS]
 *   key of [keyManager]. On the JVM, [AndroidKeystoreKeyManager] makes this key with its JCA fallback.
 * - [INTERACTIVE_USER] with the answers of [interactivePrompts] (`keyboard-interactive` method).
 *
 * An `exec` request runs `sh -c <command>` on the test machine. When [allowTcpForwarding] is true, the
 * server opens `direct-tcpip` channels, so a test can use it as a ProxyJump bastion.
 */
internal class TestSshServer(
    val keyManager: AndroidKeystoreKeyManager = AndroidKeystoreKeyManager(),
    val keyAlgorithm: KeyAlgorithm = KeyAlgorithm.Ed25519,
    private val allowTcpForwarding: Boolean = false,
) : AutoCloseable {

    /** One `keyboard-interactive` prompt and the answer that the server accepts. */
    data class InteractivePrompt(
        val prompt: String,
        val answer: String,
        val echo: Boolean = false,
    )

    /** The host key that the server sends to each new session. A test can set a new key at any time. */
    @Volatile
    var hostKey: KeyPair = newHostKey()

    /** The keys that [KEY_USER] can log in with. */
    val authorizedKeys: MutableList<PublicKey> = CopyOnWriteArrayList()

    /** The prompts that the server sends to [INTERACTIVE_USER]. The login passes when all answers are correct. */
    @Volatile
    var interactivePrompts: List<InteractivePrompt> = listOf(InteractivePrompt(prompt = "Password: ", answer = PASSWORD))

    /** The port of the server. It is 0 until the first [start]. */
    var port: Int = 0
        private set

    /** The [SshAuthMethod] that logs in as [KEY_USER] with the [KEY_ALIAS] key of [keyManager]. */
    val keyAuthMethod: SshAuthMethod.KeystoreKey
        get() = SshAuthMethod.KeystoreKey(keyAlias = KEY_ALIAS, algorithm = keyAlgorithm)

    private var sshd: SshServer? = null

    init {
        authorizeKey(keyManager.getOrCreatePublicKey(keyAlias = KEY_ALIAS, algorithm = keyAlgorithm).openSshAuthorizedKeyLine)
    }

    /** Adds the OpenSSH `authorized_keys` line [authorizedKeyLine] to [authorizedKeys]. */
    fun authorizeKey(authorizedKeyLine: String) {
        authorizedKeys += AuthorizedKeyEntry.parseAuthorizedKeyEntry(authorizedKeyLine)
            .resolvePublicKey(null, PublicKeyEntryResolver.FAILING)
    }

    /** Starts the server. Returns this server, so that a test can write `TestSshServer().start().use { }`. */
    fun start(): TestSshServer {
        check(sshd == null) { "The server runs already" }
        val server = SshServer.setUpDefaultServer().apply {
            host = HOST
            port = this@TestSshServer.port
            keyPairProvider = KeyPairProvider { listOf(this@TestSshServer.hostKey) }
            setPasswordAuthenticator { username, password, _ -> username == PASSWORD_USER && password == PASSWORD }
            setPublickeyAuthenticator { username, key, _ ->
                username == KEY_USER && authorizedKeys.any { KeyUtils.compareKeys(it, key) }
            }
            keyboardInteractiveAuthenticator = InteractiveAuthenticator()
            commandFactory = CommandFactory { _, command -> InvertedShellWrapper(ProcessShell("sh", "-c", command)) }
            forwardingFilter = if (allowTcpForwarding) {
                AcceptAllForwardingFilter.INSTANCE
            } else {
                RejectAllForwardingFilter.INSTANCE
            }
        }
        server.start()
        port = (server.boundAddresses.single() as InetSocketAddress).port
        sshd = server
        return this
    }

    /** Stops the server and closes all sessions at once. A later [start] uses the same [port]. */
    fun stop() {
        sshd?.stop(true)
        sshd = null
    }

    override fun close() = stop()

    /** Returns a [HostMachine] that connects to this server as [username] with [authMethod]. */
    fun machine(
        id: String,
        username: String,
        authMethod: SshAuthMethod,
        transportMode: TransportMode = TransportMode.SshEndpointOnly,
    ): HostMachine = HostMachine(
        id = id,
        name = id,
        hostname = HOST,
        port = port,
        username = username,
        authMethod = authMethod,
        transportMode = transportMode,
    )

    private inner class InteractiveAuthenticator : KeyboardInteractiveAuthenticator {
        override fun generateChallenge(
            session: ServerSession?,
            username: String?,
            lang: String?,
            subMethods: String?,
        ): InteractiveChallenge? {
            if (username != INTERACTIVE_USER) return null
            return InteractiveChallenge().apply {
                interactionName = "TestSshServer"
                interactionInstruction = ""
                languageTag = ""
                interactivePrompts.forEach { addPrompt(it.prompt, it.echo) }
            }
        }

        override fun authenticate(
            session: ServerSession?,
            username: String?,
            responses: List<String>?,
        ): Boolean = username == INTERACTIVE_USER && responses == interactivePrompts.map { it.answer }
    }

    companion object {
        const val HOST: String = "127.0.0.1"
        const val PASSWORD_USER: String = "bison-password"
        const val PASSWORD: String = "correct horse battery staple"
        const val KEY_USER: String = "bison-key"
        const val KEY_ALIAS: String = "test_ssh_server_key"
        const val INTERACTIVE_USER: String = "bison-interactive"

        /** Makes a new in-memory `ecdsa-sha2-nistp256` host key. */
        fun newHostKey(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
    }
}
