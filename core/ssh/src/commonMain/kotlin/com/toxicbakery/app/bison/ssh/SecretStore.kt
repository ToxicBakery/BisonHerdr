package com.toxicbakery.app.bison.ssh

import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Keeps secrets, for example SSH passwords, out of `machines.json`.
 *
 * A machine keeps only a reference in the form `secret:<ref>`. See [SecretRef]. The platform layer supplies an
 * implementation that encrypts each secret with a device key that never leaves the device.
 *
 * A [ref] has 1 to [SecretRef.MAX_LENGTH] characters: letters, digits, `-`, and `_`. See [SecretRef.isValid].
 * A secret is a [ByteArray], so that the caller can clear it with zeros after use.
 */
interface SecretStore {

    /**
     * Encrypts [secret] and keeps it for [ref]. It replaces the old secret of [ref].
     * The store keeps no reference to [secret], so the caller can clear it after the call.
     *
     * Throws an [IllegalArgumentException] when [ref] is not valid. Throws a different exception when the
     * device cannot encrypt or write the secret.
     */
    suspend fun put(ref: String, secret: ByteArray)

    /**
     * Returns a new copy of the secret of [ref]. Clear the copy with zeros after use.
     *
     * Returns null when [ref] is not valid, when the store has no secret for [ref], or when the secret does not
     * decrypt, for example after a restore to a different device.
     */
    suspend fun get(ref: String): ByteArray?

    /** Removes the secret of [ref]. It does nothing when the store has no secret for [ref]. */
    suspend fun remove(ref: String)
}

/**
 * The form of a secret reference in [com.toxicbakery.app.bison.model.SshAuthMethod.Password.encryptedPasswordRef].
 *
 * The stored value is [PREFIX] and then the reference of the [SecretStore], for example `secret:password-0a1b`.
 * A value that does not start with [PREFIX] is a plain-text password from an earlier version.
 */
object SecretRef {

    /** The start of each stored value that refers to a [SecretStore] entry. */
    const val PREFIX: String = "secret:"

    /** The maximum length of a reference. The reference is also a file name. */
    const val MAX_LENGTH: Int = 64

    private const val PASSWORD_REF_PREFIX = "password-"
    private const val RANDOM_BYTES = 16

    /** Returns true when [value] starts with [PREFIX]. Such a value never holds a password. */
    fun hasPrefix(value: String): Boolean = value.startsWith(PREFIX)

    /** Returns the reference in [value], or null when [value] has no [PREFIX] or the reference is not valid. */
    fun parse(value: String): String? =
        if (hasPrefix(value)) value.removePrefix(PREFIX).takeIf(::isValid) else null

    /** Returns the stored value for [ref]. Throws an [IllegalArgumentException] when [ref] is not valid. */
    fun format(ref: String): String {
        require(isValid(ref)) { "The secret reference is not valid." }
        return PREFIX + ref
    }

    /** Returns a new random reference for a password. */
    fun newPasswordRef(): String =
        PASSWORD_REF_PREFIX + Random.nextBytes(RANDOM_BYTES).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    /** Returns true when [ref] has 1 to [MAX_LENGTH] characters: letters, digits, `-`, and `_`. */
    fun isValid(ref: String): Boolean =
        ref.length in 1..MAX_LENGTH && ref.all { char ->
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '_'
        }
}

/**
 * A [SecretStore] that keeps each secret only in memory. It does not encrypt and does not write a file.
 *
 * Use it for tests and for hosts that have no device key store. The secrets go away when the process stops.
 */
class InMemorySecretStore : SecretStore {

    private val secrets = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** The references that have a secret. */
    val refs: Set<String>
        get() = secrets.value.keys

    override suspend fun put(ref: String, secret: ByteArray) {
        require(SecretRef.isValid(ref)) { "The secret reference is not valid." }
        val copy = secret.copyOf()
        val old = secrets.getAndUpdate { current -> current + (ref to copy) }
        old[ref]?.fill(0)
    }

    override suspend fun get(ref: String): ByteArray? = secrets.value[ref]?.copyOf()

    override suspend fun remove(ref: String) {
        val old = secrets.getAndUpdate { current -> current - ref }
        old[ref]?.fill(0)
    }
}
