package com.toxicbakery.app.bison.store

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MachineStoreTest {

    private fun sampleMachine(
        id: String = "workbox",
        name: String = "workbox",
        hostname: String = "100.64.0.12",
        username: String = "ian",
    ): HostMachine = HostMachine(
        id = id,
        name = name,
        hostname = hostname,
        username = username,
        transportMode = TransportMode.AutoPreferMosh,
    )

    @Test
    fun isLoadedAndAwaitLoadedCompleteAfterDiskRead() = runTest {
        val initialJson = appJson.encodeToString(listOf(sampleMachine()))
        val io = InMemoryStoreIOProvider(mapOf(MachineStore.MACHINES_FILE_NAME to initialJson))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = MachineStore(
            storeIOProvider = io,
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        assertFalse(store.isLoaded.value)
        assertTrue(store.knownMachines.value.isEmpty())

        store.awaitLoaded()

        assertTrue(store.isLoaded.value)
        assertEquals(1, store.knownMachines.value.size)
        assertEquals("workbox", store.getMachine("workbox")?.name)
    }

    @Test
    fun upsertAndRemovePersistAtomicallyWithoutLeavingTempFile() = runTest {
        val io = InMemoryStoreIOProvider()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = MachineStore(
            storeIOProvider = io,
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        store.awaitLoaded()

        val workbox = sampleMachine(id = "workbox", name = "Workbox Primary")
        val gpuRig = sampleMachine(id = "gpu-rig", name = "GPU Rig", hostname = "100.64.0.25")
        store.upsertMachine(workbox)
        store.upsertMachine(gpuRig)

        assertEquals(listOf("workbox", "gpu-rig"), store.knownMachines.value.map { it.id })
        assertEquals(setOf(MachineStore.MACHINES_FILE_NAME), io.files.keys)

        // Update existing machine in-place
        store.upsertMachine(workbox.copy(name = "Workbox Updated"))
        assertEquals(2, store.knownMachines.value.size)
        assertEquals("Workbox Updated", store.getMachine("workbox")?.name)

        // Remove machine
        store.removeMachine("workbox")
        assertNull(store.getMachine("workbox"))
        assertEquals(listOf("gpu-rig"), store.knownMachines.value.map { it.id })

        val persisted = appJson.decodeFromString<List<HostMachine>>(
            assertNotNull(io.readText(MachineStore.MACHINES_FILE_NAME))
        )
        assertEquals(listOf("gpu-rig"), persisted.map { it.id })
    }

    @Test
    fun updateFontSizeAndThemeOverridePersistChanges() = runTest {
        val io = InMemoryStoreIOProvider()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = MachineStore(
            storeIOProvider = io,
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        store.awaitLoaded()
        store.upsertMachine(sampleMachine(id = "workbox"))

        store.updateFontSize("workbox", 18.5f)
        store.updateThemeOverride("workbox", "tokyo-night")

        val updated = assertNotNull(store.getMachine("workbox"))
        assertEquals(18.5f, updated.fontSizeSp)
        assertEquals("tokyo-night", updated.themeOverrideId)

        // Clear theme override
        store.updateThemeOverride("workbox", null)
        assertNull(store.getMachine("workbox")?.themeOverrideId)
    }

    @Test
    fun corruptJsonIsMovedAsideAndDoesNotBlockCleanWrites() = runTest {
        val corruptPayload = "[{\"id\": \"workbox\", truncated"
        val io = InMemoryStoreIOProvider(mapOf(MachineStore.MACHINES_FILE_NAME to corruptPayload))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = MachineStore(
            storeIOProvider = io,
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        store.awaitLoaded()

        assertTrue(store.isLoaded.value)
        assertTrue(store.knownMachines.value.isEmpty())

        val corruptBackupKey = io.files.keys.single { it.startsWith("${MachineStore.MACHINES_FILE_NAME}.corrupt.") }
        assertEquals(corruptPayload, io.readText(corruptBackupKey))

        // Writing a new machine succeeds while preserving the corrupt backup
        store.upsertMachine(sampleMachine(id = "recovered"))
        assertEquals(corruptPayload, io.readText(corruptBackupKey))
        assertTrue(io.readText(MachineStore.MACHINES_FILE_NAME)?.contains("recovered") == true)
    }

    @Test
    fun failedCorruptQuarantineBlocksWritesToPreserveUserData() = runTest {
        val corruptPayload = "{invalid-json"
        val backing = InMemoryStoreIOProvider(mapOf(MachineStore.MACHINES_FILE_NAME to corruptPayload))
        val failingIo = object : StoreIOProvider by backing {
            override fun moveCorruptAside(fileName: String) {
                throw IllegalStateException("Disk read-only")
            }
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = MachineStore(
            storeIOProvider = failingIo,
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        store.awaitLoaded()

        store.upsertMachine(sampleMachine(id = "new-host"))
        assertEquals(corruptPayload, backing.readText(MachineStore.MACHINES_FILE_NAME))
    }

    @Test
    fun loadMigrationIsPublishedAndWrittenBeforeLoadCompletes() = runTest {
        val original = sampleMachine(name = "old")
        val io = InMemoryStoreIOProvider(
            mapOf(MachineStore.MACHINES_FILE_NAME to appJson.encodeToString(listOf(original))),
        )
        val store = MachineStore(
            storeIOProvider = io,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            loadMigration = { machines -> machines.map { it.copy(name = "migrated") } },
        )

        store.awaitLoaded()

        assertEquals("migrated", store.getMachine("workbox")?.name)
        val writtenJson = assertNotNull(io.textAt(MachineStore.MACHINES_FILE_NAME))
        val written = appJson.decodeFromString<List<HostMachine>>(writtenJson)
        assertEquals(listOf(original.copy(name = "migrated")), written)
    }

    @Test
    fun aFailedLoadMigrationKeepsTheLoadedMachines() = runTest {
        val original = sampleMachine()
        val originalJson = appJson.encodeToString(listOf(original))
        val io = InMemoryStoreIOProvider(mapOf(MachineStore.MACHINES_FILE_NAME to originalJson))
        val store = MachineStore(
            storeIOProvider = io,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            loadMigration = { throw IllegalStateException("no key store") },
        )

        store.awaitLoaded()

        assertEquals(listOf(original), store.knownMachines.value)
        assertEquals(originalJson, io.textAt(MachineStore.MACHINES_FILE_NAME))
    }

    @Test
    fun firstStartWithMachinesSetsRestoredRemovesCorruptCopiesAndWritesTheMarker() = runTest {
        val io = InMemoryStoreIOProvider(
            mapOf(
                MachineStore.MACHINES_FILE_NAME to appJson.encodeToString(listOf(sampleMachine())),
                "${MachineStore.MACHINES_FILE_NAME}.corrupt.1700000000000" to "[{\"password\":\"hunter2\"",
                "${MachineStore.MACHINES_FILE_NAME}.corrupt.1700000000001" to "{",
                "settings.json" to "{}",
            ),
        )
        val noBackup = InMemoryStoreIOProvider()
        val store = MachineStore(
            storeIOProvider = io,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            noBackupStoreIOProvider = noBackup,
        )

        store.awaitLoaded()

        assertTrue(store.restoredFromBackup.value)
        assertEquals(setOf(MachineStore.MACHINES_FILE_NAME, "settings.json"), io.files.keys)
        assertTrue(assertNotNull(noBackup.textAt(MachineStore.DEVICE_MARKER_FILE_NAME)).isNotBlank())
    }

    @Test
    fun firstStartWithoutMachinesWritesTheMarkerAndIsNotARestore() = runTest {
        val noBackup = InMemoryStoreIOProvider()
        val store = MachineStore(
            storeIOProvider = InMemoryStoreIOProvider(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            noBackupStoreIOProvider = noBackup,
        )

        store.awaitLoaded()

        assertFalse(store.restoredFromBackup.value)
        assertNotNull(noBackup.textAt(MachineStore.DEVICE_MARKER_FILE_NAME))
    }

    @Test
    fun aLoadWithTheMarkerIsNotARestoreAndKeepsTheCorruptCopies() = runTest {
        val corruptName = "${MachineStore.MACHINES_FILE_NAME}.corrupt.1700000000000"
        val io = InMemoryStoreIOProvider(
            mapOf(
                MachineStore.MACHINES_FILE_NAME to appJson.encodeToString(listOf(sampleMachine())),
                corruptName to "{",
            ),
        )
        val noBackup = InMemoryStoreIOProvider(mapOf(MachineStore.DEVICE_MARKER_FILE_NAME to "0a1b"))
        val store = MachineStore(
            storeIOProvider = io,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            noBackupStoreIOProvider = noBackup,
        )

        store.awaitLoaded()

        assertFalse(store.restoredFromBackup.value)
        assertNotNull(io.textAt(corruptName))
        assertEquals("0a1b", noBackup.textAt(MachineStore.DEVICE_MARKER_FILE_NAME))
    }

    @Test
    fun withoutANoBackupDirectoryTheStoreKeepsTheCorruptCopies() = runTest {
        val corruptName = "${MachineStore.MACHINES_FILE_NAME}.corrupt.1700000000000"
        val io = InMemoryStoreIOProvider(mapOf(corruptName to "{"))
        val store = MachineStore(storeIOProvider = io, dispatcher = UnconfinedTestDispatcher(testScheduler))

        store.awaitLoaded()

        assertFalse(store.restoredFromBackup.value)
        assertNotNull(io.textAt(corruptName))
    }

    @Test
    fun deleteCorruptCopiesRemovesOnlyTheCopiesOfTheFile() {
        val io = InMemoryStoreIOProvider(
            mapOf(
                "machines.json" to "[]",
                "machines.json.corrupt.1" to "{",
                "settings.json.corrupt.1" to "{",
            ),
        )

        io.deleteCorruptCopies("machines.json")

        assertEquals(setOf("machines.json", "settings.json.corrupt.1"), io.files.keys)
    }
}
