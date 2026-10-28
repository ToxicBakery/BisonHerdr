package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.server.forward.RejectAllForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
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
 * - [KEY_USER] with a key in [authorizedKeys] (`publickey` method). The constructor makes the [KEY_ALIAS]
 *   key of [keyManager] and adds it. The JVM has no AndroidKeyStore, so the default [keyManager] makes
 *   software keys.
 * - [INTERACTIVE_USER] with the answers of [interactivePrompts] (`keyboard-interactive` method).
 *
 * An `exec` request runs `sh -c <command>` on the test machine, with [name] in the environment variable
 * [NAME_VARIABLE]. A test with two servers can thus tell which server ran a command. The command gets only these
 * environment variables: [NAME_VARIABLE], `HOME`, and `PATH`. `HOME` is [homeDirectory]. The `PATH` starts with the
 * `.local/bin` directory of [homeDirectory], then [binDirectory], then a directory with a `herdr` that fails each
 * command, then the `PATH` of the test machine. A test can put a fake `herdr` first on the `PATH` with
 * [installHerdr]. Thus no test can run the `herdr` of the test machine and reach a Herdr server of the user. When
 * [allowTcpForwarding] is true, the server opens `direct-tcpip` channels, so a test can use it as a ProxyJump
 * bastion. [directTcpipTargets] records the `host:port` of each channel that a client asked for.
 *
 * [credentialChecks] counts the login requests that get to an authenticator. A test uses it to prove that a
 * client sent no credentials, for example after a host key check stopped the connection.
 */
internal class TestSshServer(
    val keyManager: AndroidKeystoreKeyManager = AndroidKeystoreKeyManager(allowSoftwareKeys = true),
    val keyAlgorithm: KeyAlgorithm = KeyAlgorithm.Ed25519,
    private val allowTcpForwarding: Boolean = false,
    val name: String = "test-ssh-server",
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

    /**
     * More host keys that the server offers to each new session after [hostKey]. A test sets them to add a key of
     * a new type to a server that the client knows already.
     */
    @Volatile
    var extraHostKeys: List<KeyPair> = emptyList()

    /**
     * A directory at the start of the `PATH` of each `exec` command, or null. A test puts a script in it, so that a
     * command finds a program that the test machine does not have, for example `mosh-server`.
     */
    @Volatile
    var binDirectory: File? = null

    /** The temporary directory of the server. [close] deletes it. */
    private val serverDirectory: File = createTempDirectory("bison-test-ssh-server").toFile()

    /**
     * The `HOME` of each `exec` command. The default is a new empty directory. The workers of the client put
     * `$HOME/.local/bin` first on the `PATH`. Thus a command finds a program in the `.local/bin` of this directory
     * first. A program of the same name on the test machine, for example `herdr`, comes after it.
     */
    @Volatile
    var homeDirectory: File = File(serverDirectory, "home").apply { mkdirs() }

    /** A directory on the `PATH` of each `exec` command with a `herdr` that fails each command. */
    private val stubDirectory: File = File(serverDirectory, "stub-bin")

    /** The keys that [KEY_USER] can log in with. */
    val authorizedKeys: MutableList<PublicKey> = CopyOnWriteArrayList()

    /** The prompts that the server sends to [INTERACTIVE_USER]. The login passes when all answers are correct. */
    @Volatile
    var interactivePrompts: List<InteractivePrompt> = listOf(InteractivePrompt(prompt = "Password: ", answer = PASSWORD))

    /**
     * When false, the server does not offer the `password` method, so a client can log in to [INTERACTIVE_USER] only
     * with the `keyboard-interactive` method. A change has an effect at the next [start].
     */
    @Volatile
    var offerPasswordMethod: Boolean = true

    private val credentialCheckCount = AtomicInteger()

    private val directTcpipRequests = CopyOnWriteArrayList<String>()

    /**
     * The `host:port` of each `direct-tcpip` channel that a client asked for, in sequence, with the host name that the
     * client sent. The server records a request only when [allowTcpForwarding] is true.
     */
    val directTcpipTargets: List<String>
        get() = directTcpipRequests.toList()

    /** The number of login requests that got to an authenticator of the server, for all users and methods. */
    val credentialChecks: Int
        get() = credentialCheckCount.get()

    /** The number of open sessions on the server. It is 0 when the server is stopped. */
    val activeSessionCount: Int
        get() = sshd?.activeSessions?.size ?: 0

    /** The port of the server. It is 0 until the first [start]. */
    var port: Int = 0
        private set

    /** The [SshAuthMethod] that logs in as [KEY_USER] with the [KEY_ALIAS] key of [keyManager]. */
    val keyAuthMethod: SshAuthMethod.KeystoreKey
        get() = SshAuthMethod.KeystoreKey(keyAlias = KEY_ALIAS, algorithm = keyAlgorithm)

    private var sshd: SshServer? = null

    init {
        writeHerdr(stubDirectory, STUB_HERDR)
        authorizeKey(keyManager.getOrCreatePublicKey(keyAlias = KEY_ALIAS, algorithm = keyAlgorithm).openSshAuthorizedKeyLine)
    }

    /**
     * Writes [script] as the `herdr` program in the `.local/bin` directory of [homeDirectory]. The `PATH` of each
     * `exec` command finds it before all other programs.
     */
    fun installHerdr(script: String) {
        writeHerdr(File(homeDirectory, ".local/bin"), script)
    }

    private fun writeHerdr(directory: File, script: String) {
        directory.mkdirs()
        File(directory, "herdr").apply {
            writeText(script)
            setExecutable(true)
        }
    }

    /** Adds the OpenSSH `authorized_keys` line [authorizedKeyLine] to [authorizedKeys]. */
    fun authorizeKey(authorizedKeyLine: String) {
        authorizedKeys += AuthorizedKeyEntry.parseAuthorizedKeyEntry(authorizedKeyLine)
            .resolvePublicKey(null, PublicKeyEntryResolver.FAILING)
    }

    /**
     * Returns the [KnownHost] entry for the current [hostKey] at [HOST] and [port]. The MINA SSHD key code
     * makes the key type, the Base64 key, and the OpenSSH `SHA256:` fingerprint, so a test can compare
     * the values of the app with a second implementation.
     */
    fun knownHost(): KnownHost {
        val (keyType, publicKey) = PublicKeyEntry.toString(hostKey.public).split(' ')
        return KnownHost(
            host = HOST,
            port = port,
            keyType = keyType,
            fingerprint = KeyUtils.getFingerPrint(hostKey.public),
            publicKey = publicKey,
        )
    }

    /** Starts the server. Returns this server, so that a test can write `TestSshServer().start().use { }`. */
    fun start(): TestSshServer {
        check(sshd == null) { "The server runs already" }
        val server = SshServer.setUpDefaultServer().apply {
            host = HOST
            port = this@TestSshServer.port
            keyPairProvider = KeyPairProvider { listOf(this@TestSshServer.hostKey) + extraHostKeys }
            passwordAuthenticator = if (offerPasswordMethod) {
                PasswordAuthenticator { username, password, _ ->
                    credentialCheckCount.incrementAndGet()
                    username == PASSWORD_USER && password == PASSWORD
                }
            } else {
                null
            }
            setPublickeyAuthenticator { username, key, _ ->
                credentialCheckCount.incrementAndGet()
                username == KEY_USER && authorizedKeys.any { KeyUtils.compareKeys(it, key) }
            }
            keyboardInteractiveAuthenticator = InteractiveAuthenticator()
            commandFactory = CommandFactory { _, command -> InvertedShellWrapper(ProcessShell(shellCommand(command))) }
            forwardingFilter = if (allowTcpForwarding) {
                RecordingForwardingFilter()
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

    /** Closes all open sessions at once. The server stays on its [port] and accepts new sessions. */
    fun closeSessions() {
        sshd?.activeSessions?.forEach { it.close(true) }
    }

    /** Stops the server and deletes its temporary directory. A [homeDirectory] that a test set stays. */
    override fun close() {
        stop()
        serverDirectory.deleteRecursively()
    }

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

    /** Returns the process that runs [command]: `sh -c` with only the environment variables in the class comment. */
    private fun shellCommand(command: String): List<String> = buildList {
        val path = listOfNotNull(
            File(homeDirectory, ".local/bin").absolutePath,
            binDirectory?.absolutePath,
            stubDirectory.absolutePath,
            System.getenv("PATH"),
        )
        add("env")
        add("-i")
        add("$NAME_VARIABLE=$name")
        add("HOME=${homeDirectory.absolutePath}")
        add("PATH=${path.joinToString(File.pathSeparator)}")
        addAll(listOf("sh", "-c", command))
    }

    /** Accepts each forwarding request, and records each `direct-tcpip` request in [directTcpipTargets]. */
    private inner class RecordingForwardingFilter : ForwardingFilter by AcceptAllForwardingFilter.INSTANCE {
        override fun canConnect(type: TcpForwardingFilter.Type, address: SshdSocketAddress, session: Session): Boolean {
            if (type == TcpForwardingFilter.Type.Direct) directTcpipRequests += "${address.hostName}:${address.port}"
            return true
        }
    }

    private inner class InteractiveAuthenticator : KeyboardInteractiveAuthenticator {
        override fun generateChallenge(
            session: ServerSession?,
            username: String?,
            lang: String?,
            subMethods: String?,
        ): InteractiveChallenge? {
            credentialCheckCount.incrementAndGet()
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
        ): Boolean {
            credentialCheckCount.incrementAndGet()
            return username == INTERACTIVE_USER && responses == interactivePrompts.map { it.answer }
        }
    }

    companion object {
        const val HOST: String = "127.0.0.1"
        const val PASSWORD_USER: String = "bison-password"
        const val PASSWORD: String = "correct horse battery staple"
        const val KEY_USER: String = "bison-key"
        const val KEY_ALIAS: String = "test_ssh_server_key"
        const val INTERACTIVE_USER: String = "bison-interactive"

        /** The environment variable that has the [name] of the server for each `exec` command. */
        const val NAME_VARIABLE: String = "BISON_TEST_SSH_SERVER"

        /** Makes a new in-memory `ecdsa-sha2-nistp256` host key. */
        fun newHostKey(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

        /** Makes a new in-memory 2048-bit `ssh-rsa` host key. */
        fun newRsaHostKey(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply {
                initialize(RSA_KEY_BITS)
            }.generateKeyPair()

        private const val RSA_KEY_BITS = 2048

        /** The default `herdr` of each server. It fails each command. */
        private const val STUB_HERDR = "#!/bin/sh\necho 'herdr: this test has no fake Herdr' >&2\nexit 127\n"
    }
}
