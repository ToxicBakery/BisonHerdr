package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSchHostKeyException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.security.MessageDigest
import java.util.Base64

/**
 * A JSch [HostKeyRepository] that reads the accepted host keys from [knownHostsStore].
 *
 * [connect] gives the repository to the session and sets `StrictHostKeyChecking=yes`. Thus JSch stops each
 * connection to a `host:port` that has no entry or a different key. JSch stops the connection during the
 * key exchange, before the client sends credentials. JSch does not ask the user on its own thread, and it
 * never adds a key.
 *
 * The repository does not change [knownHostsStore]. JSch calls [add] and [remove] only when it asks the
 * user itself, and `StrictHostKeyChecking=yes` prevents that. The user accepts or removes a key in the UI,
 * and the UI writes the change to [knownHostsStore].
 *
 * One repository can serve more than one session, for example a bastion session and a target session.
 * Each session gives its own `host:port` to [check], and [connect] reads the `host:port` from the session.
 */
class KnownHostsRepository(
    private val knownHostsStore: KnownHostsStore,
) : HostKeyRepository {

    /**
     * Connects [session] with the host key check of this repository. Call [KnownHostsStore.awaitLoaded]
     * first, because the check reads only the loaded entries.
     *
     * When the check stops the connection, this function throws a [HostKeyRejectedException]. The exception
     * tells the key that the server sent and, for a changed key, the saved entry.
     */
    fun connect(session: Session, timeoutMs: Int) {
        session.hostKeyRepository = this
        session.setConfig(STRICT_HOST_KEY_CHECKING, STRICT_HOST_KEY_CHECKING_VALUE)
        try {
            session.connect(timeoutMs)
        } catch (error: JSchHostKeyException) {
            throw rejectionFor(session, error) ?: error
        }
    }

    /**
     * Compares [key] with the entry for [host]. [host] has the JSch form: `name` for port 22, or
     * `[name]:port` for a different port.
     */
    override fun check(host: String, key: ByteArray): Int {
        val (name, port) = parseJschHost(host)
        val known = knownHostsStore.find(name, port) ?: return HostKeyRepository.NOT_INCLUDED
        return if (known.hasKey(key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    /** Does nothing. Only the UI adds a key, through [KnownHostsStore.put]. */
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    /** Does nothing. Only the UI removes a key, through [KnownHostsStore.remove]. */
    override fun remove(host: String?, type: String?) = Unit

    /** Does nothing. Only the UI removes a key, through [KnownHostsStore.remove]. */
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = KnownHostsStore.KNOWN_HOSTS_FILE_NAME

    override fun getHostKey(): Array<HostKey> = getHostKey(host = null, type = null)

    /**
     * Returns the entry for [host] as a JSch [HostKey]. JSch uses it to ask the server for the key type of
     * the entry first. A null [host] gives all entries. A null [type] gives each key type.
     */
    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        val candidates = if (host == null) {
            knownHostsStore.knownHosts.value
        } else {
            val (name, port) = parseJschHost(host)
            listOfNotNull(knownHostsStore.find(name, port))
        }
        return candidates
            .mapNotNull { it.toHostKey() }
            .filter { type == null || it.type == type }
            .toTypedArray()
    }

    /**
     * Returns the rejection for the key that [session] got from the server. Returns null when the session
     * has no server key, or when [knownHostsStore] now has the same key.
     */
    private fun rejectionFor(session: Session, error: JSchHostKeyException): HostKeyRejectedException? {
        val sent = session.hostKey ?: return null
        val sentBytes = runCatching { Base64.getDecoder().decode(sent.key) }.getOrNull() ?: return null
        val presented = knownHostOf(
            host = session.host,
            port = session.port,
            keyType = sent.type,
            publicKey = sentBytes,
        )
        val known = knownHostsStore.find(session.host, session.port)
        val rejection = when {
            known == null -> SshAuthResult.HostKeyUnknown(presented)
            !known.hasKey(sentBytes) -> SshAuthResult.HostKeyChanged(known = known, presented = presented)
            else -> return null
        }
        return HostKeyRejectedException(rejection, error)
    }

    companion object {
        private const val STRICT_HOST_KEY_CHECKING = "StrictHostKeyChecking"
        private const val STRICT_HOST_KEY_CHECKING_VALUE = "yes"
        private const val DEFAULT_SSH_PORT = 22
        private const val FINGERPRINT_PREFIX = "SHA256:"

        /**
         * Returns the OpenSSH fingerprint of the public key blob [publicKey]: `SHA256:` and then the
         * Base64 text of the SHA-256 hash, with no padding. `ssh-keygen -lf` shows the same text.
         */
        fun fingerprint(publicKey: ByteArray): String =
            FINGERPRINT_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicKey),
            )

        /** Returns the [KnownHost] for the key blob [publicKey] of the type [keyType] that [host] and [port] sent. */
        fun knownHostOf(host: String, port: Int, keyType: String, publicKey: ByteArray): KnownHost =
            KnownHost(
                host = host,
                port = port,
                keyType = keyType,
                fingerprint = fingerprint(publicKey),
                publicKey = Base64.getEncoder().encodeToString(publicKey),
            )

        /**
         * Splits the JSch host text [host] into the name and the port. JSch writes `name` for port 22
         * and `[name]:port` for a different port.
         */
        internal fun parseJschHost(host: String): Pair<String, Int> {
            if (host.startsWith("[")) {
                val end = host.lastIndexOf("]:")
                val port = if (end > 0) host.substring(end + 2).toIntOrNull() else null
                if (port != null) return host.substring(1, end) to port
            }
            return host to DEFAULT_SSH_PORT
        }

        /** Returns the JSch host text for [host] and [port]. See [parseJschHost]. */
        internal fun jschHost(host: String, port: Int): String =
            if (port == DEFAULT_SSH_PORT) host else "[$host]:$port"
    }
}

/**
 * The host key check stopped a connection before the client sent credentials. [rejection] tells the
 * key that the server sent.
 */
class HostKeyRejectedException(
    val rejection: SshAuthResult.HostKeyRejected,
    cause: Throwable,
) : Exception(describe(rejection), cause)

private fun describe(rejection: SshAuthResult.HostKeyRejected): String {
    val presented = rejection.presented
    return when (rejection) {
        is SshAuthResult.HostKeyUnknown ->
            "Unknown host key for ${presented.hostPort}: ${presented.keyType} ${presented.fingerprint}"

        is SshAuthResult.HostKeyChanged ->
            "Changed host key for ${presented.hostPort}: saved ${rejection.known.keyType} " +
                "${rejection.known.fingerprint}, sent ${presented.keyType} ${presented.fingerprint}"
    }
}

private fun KnownHost.keyBytes(): ByteArray? =
    runCatching { Base64.getDecoder().decode(publicKey) }.getOrNull()

/** Returns true when [key] is the key of this entry. An entry with a bad Base64 key never agrees. */
private fun KnownHost.hasKey(key: ByteArray): Boolean =
    keyBytes()?.let { MessageDigest.isEqual(it, key) } ?: false

private fun KnownHost.toHostKey(): HostKey? {
    val bytes = keyBytes() ?: return null
    return runCatching {
        HostKey(KnownHostsRepository.jschHost(host, port), HostKey.GUESS, bytes)
    }.getOrNull()
}
