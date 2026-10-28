package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.store.appJson
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Tests the one-time move of the plain-text passwords of an earlier version, with [MachineStore],
 * [PasswordSecretMigration], and the encrypted [AesGcmSecretStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordMigrationTest {

    private val secretsIo = InMemoryStoreIOProvider()
    private val secretStore = softwareKeySecretStore(secretsIo)

    @Test
    fun anOldMachinesFileGetsSecretReferencesAndLosesItsCorruptCopies() = runTest {
        val oldMachines = listOf(
            machine("devbox", SshAuthMethod.Password(encryptedPasswordRef = PASSWORD)),
            machine("keybox", SshAuthMethod.KeystoreKey()),
        )
        val corruptCopy = "${MachineStore.MACHINES_FILE_NAME}.corrupt.1700000000000"
        val filesIo = InMemoryStoreIOProvider(
            mapOf(
                MachineStore.MACHINES_FILE_NAME to appJson.encodeToString(oldMachines),
                corruptCopy to appJson.encodeToString(oldMachines).dropLast(3),
            ),
        )
        val noBackupIo = InMemoryStoreIOProvider()

        val store = machineStore(filesIo, noBackupIo)
        store.awaitLoaded()

        val machinesJson = assertNotNull(filesIo.textAt(MachineStore.MACHINES_FILE_NAME))
        assertFalse(PASSWORD in machinesJson, machinesJson)
        val fileNames = filesIo.files.keys
        assertFalse(fileNames.any { it.startsWith("${MachineStore.MACHINES_FILE_NAME}.") }, "$fileNames")
        val migrated = assertNotNull(store.getMachine("devbox"))
        val ref = assertNotNull(SecretRef.parse((migrated.authMethod as SshAuthMethod.Password).encryptedPasswordRef))
        assertContentEquals(PASSWORD.encodeToByteArray(), secretStore.get(ref))
        assertFalse(secretsIo.files.values.any { PASSWORD in it })
        assertEquals(oldMachines[1], store.getMachine("keybox"))
        assertTrue(store.restoredFromBackup.value, "An update from an earlier version has no device marker")
    }

    @Test
    fun aLaterLoadKeepsTheMigratedReference() = runTest {
        val filesIo = InMemoryStoreIOProvider(
            mapOf(
                MachineStore.MACHINES_FILE_NAME to appJson.encodeToString(
                    listOf(machine("devbox", SshAuthMethod.Password(encryptedPasswordRef = PASSWORD))),
                ),
            ),
        )
        val noBackupIo = InMemoryStoreIOProvider()
        val first = machineStore(filesIo, noBackupIo).apply { awaitLoaded() }.getMachine("devbox")

        val second = machineStore(filesIo, noBackupIo)
        second.awaitLoaded()

        assertEquals(first, second.getMachine("devbox"))
        assertFalse(second.restoredFromBackup.value)
        assertEquals(1, secretsIo.files.size)
    }

    private fun TestScope.machineStore(
        filesIo: InMemoryStoreIOProvider,
        noBackupIo: InMemoryStoreIOProvider,
    ): MachineStore {
        val migration = PasswordSecretMigration(secretStore)
        return MachineStore(
            storeIOProvider = filesIo,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            noBackupStoreIOProvider = noBackupIo,
            loadMigration = { machines -> migration.migrate(machines) },
        )
    }

    private fun machine(id: String, authMethod: SshAuthMethod): HostMachine = HostMachine(
        id = id,
        name = id,
        hostname = "$id.local",
        username = "ian",
        authMethod = authMethod,
    )

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}
