package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SecretStoreTest {

    @Test
    fun secretRefFormatsAndParsesAValidReference() {
        val value = SecretRef.format("password-0a1b")

        assertEquals("secret:password-0a1b", value)
        assertTrue(SecretRef.hasPrefix(value))
        assertEquals("password-0a1b", SecretRef.parse(value))
    }

    @Test
    fun secretRefRefusesAReferenceThatIsNotAFileName() {
        assertFailsWith<IllegalArgumentException> { SecretRef.format("../machines.json") }
        assertNull(SecretRef.parse("secret:../machines.json"))
        assertNull(SecretRef.parse("secret:"))
        assertNull(SecretRef.parse("secret:" + "a".repeat(SecretRef.MAX_LENGTH + 1)))
        assertNull(SecretRef.parse("plain text password"))
        assertFalse(SecretRef.hasPrefix("plain text password"))
    }

    @Test
    fun newPasswordRefsAreValidAndDifferent() {
        val refs = List(32) { SecretRef.newPasswordRef() }

        assertTrue(refs.all(SecretRef::isValid), "$refs")
        assertEquals(refs.size, refs.toSet().size)
    }

    @Test
    fun inMemoryStoreKeepsACopyOfTheSecret() = runTest {
        val store = InMemorySecretStore()
        val secret = "hunter2".encodeToByteArray()

        store.put("password-a", secret)
        secret.fill(0)
        val first = assertNotNull(store.get("password-a"))
        first.fill(0)

        assertContentEquals("hunter2".encodeToByteArray(), store.get("password-a"))
        assertEquals(setOf("password-a"), store.refs)
    }

    @Test
    fun inMemoryStoreGivesNullForAnUnknownReferenceAndRemoves() = runTest {
        val store = InMemorySecretStore()
        store.put("password-a", "hunter2".encodeToByteArray())

        assertNull(store.get("password-b"))
        store.remove("password-a")
        store.remove("password-b")

        assertNull(store.get("password-a"))
        assertTrue(store.refs.isEmpty())
        assertFailsWith<IllegalArgumentException> { store.put("bad/ref", byteArrayOf(1)) }
    }

    @Test
    fun migrationMovesAPlainTextPasswordIntoTheStore() = runTest {
        val store = InMemorySecretStore()
        val migration = PasswordSecretMigration(store)

        val migrated = migration.migrate(passwordMachine("hunter2"))

        val value = (migrated.authMethod as SshAuthMethod.Password).encryptedPasswordRef
        val ref = assertNotNull(SecretRef.parse(value), value)
        assertContentEquals("hunter2".encodeToByteArray(), store.get(ref))
        assertFalse("hunter2" in migrated.toString(), "The machine still holds the password")
    }

    @Test
    fun migrationKeepsReferencesEmptyValuesAndKeyMachines() = runTest {
        val store = InMemorySecretStore()
        val migration = PasswordSecretMigration(store)
        val machines = listOf(
            passwordMachine(SecretRef.format("password-a")),
            passwordMachine(""),
            passwordMachine("hunter2").copy(id = "key", authMethod = SshAuthMethod.KeystoreKey()),
        )

        assertEquals(machines, migration.migrate(machines))
        assertTrue(store.refs.isEmpty())
    }

    @Test
    fun migrationKeepsThePlainTextWhenTheStoreFails() = runTest {
        val machine = passwordMachine("hunter2")
        val failing = object : SecretStore {
            override suspend fun put(ref: String, secret: ByteArray) = throw IllegalStateException("no key store")
            override suspend fun get(ref: String): ByteArray? = null
            override suspend fun remove(ref: String) = Unit
        }

        assertSame(machine, PasswordSecretMigration(failing).migrate(machine))
    }

    @Test
    fun credentialStateNeedsSignInOnlyWhenACredentialIsMissing() {
        assertFalse(CredentialState.Ready.needsSignIn)
        assertTrue(CredentialState.PasswordMissing.needsSignIn)
        val keyMissing = CredentialState.KeyMissing("bison_default_ed25519")
        assertTrue(keyMissing.needsSignIn)
        assertEquals(
            "The key bison_default_ed25519 does not exist. Open the machine settings and copy the public key.",
            keyMissing.message,
        )
        assertNotEquals<CredentialState>(CredentialState.Ready, keyMissing)
    }

    private fun passwordMachine(value: String): HostMachine = HostMachine(
        id = "workbox",
        name = "workbox",
        hostname = "100.64.0.12",
        username = "ian",
        authMethod = SshAuthMethod.Password(encryptedPasswordRef = value),
    )
}
