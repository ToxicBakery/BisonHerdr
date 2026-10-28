package com.toxicbakery.app.bison.store

import com.toxicbakery.app.bison.model.KnownHost
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class KnownHostsStoreTest {

    private fun entry(
        host: String = "devbox.tailnet.ts.net",
        port: Int = 22,
        keyType: String = "ssh-ed25519",
        fingerprint: String = "SHA256:first",
        publicKey: String = "AAAAC3NzaC1lZDI1NTE5AAAAIFirst",
    ): KnownHost = KnownHost(
        host = host,
        port = port,
        keyType = keyType,
        fingerprint = fingerprint,
        publicKey = publicKey,
    )

    private fun TestScope.store(io: StoreIOProvider): KnownHostsStore = KnownHostsStore(
        storeIOProvider = io,
        json = appJson,
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun putPersistsAndANewStoreReadsTheSameEntries() = runTest {
        val io = InMemoryStoreIOProvider()
        val first = store(io)
        first.awaitLoaded()

        val devbox = entry()
        val bastion = entry(host = "bastion.example.com", port = 2222, keyType = "ecdsa-sha2-nistp256")
        first.put(devbox)
        first.put(bastion)

        assertEquals(setOf(KnownHostsStore.KNOWN_HOSTS_FILE_NAME), io.files.keys)

        val second = store(io)
        second.awaitLoaded()
        assertEquals(listOf(devbox, bastion), second.knownHosts.value)
        assertEquals(bastion, second.find("bastion.example.com", 2222))
    }

    @Test
    fun findMatchesHostAndPortAndIgnoresLetterCase() = runTest {
        val store = store(InMemoryStoreIOProvider())
        store.awaitLoaded()
        val devbox = entry(host = "DevBox.Local", port = 22)
        store.put(devbox)

        assertEquals(devbox, store.find("devbox.local", 22))
        assertEquals(devbox, store.find(" DEVBOX.LOCAL ", 22))
        assertNull(store.find("devbox.local", 2222))
        assertNull(store.find("other.local", 22))
    }

    @Test
    fun putReplacesTheEntryForTheSameHostAndPort() = runTest {
        val io = InMemoryStoreIOProvider()
        val store = store(io)
        store.awaitLoaded()
        store.put(entry(fingerprint = "SHA256:first"))
        store.put(entry(port = 2222, fingerprint = "SHA256:other-port"))

        val replacement = entry(keyType = "ssh-rsa", fingerprint = "SHA256:second", publicKey = "AAAAB3NzaC1yc2E")
        store.put(replacement)

        assertEquals(2, store.knownHosts.value.size)
        assertEquals(replacement, store.find("devbox.tailnet.ts.net", 22))
        assertEquals("SHA256:other-port", store.find("devbox.tailnet.ts.net", 2222)?.fingerprint)
        val persisted = appJson.decodeFromString<List<KnownHost>>(
            assertNotNull(io.readText(KnownHostsStore.KNOWN_HOSTS_FILE_NAME))
        )
        assertEquals(store.knownHosts.value, persisted)
    }

    @Test
    fun removeDeletesOnlyTheEntryForTheHostAndPort() = runTest {
        val io = InMemoryStoreIOProvider()
        val store = store(io)
        store.awaitLoaded()
        store.put(entry(port = 22))
        store.put(entry(port = 2222))

        assertTrue(store.remove("devbox.tailnet.ts.net", 22))
        assertFalse(store.remove("devbox.tailnet.ts.net", 22))

        assertNull(store.find("devbox.tailnet.ts.net", 22))
        assertNotNull(store.find("devbox.tailnet.ts.net", 2222))
        val persisted = appJson.decodeFromString<List<KnownHost>>(
            assertNotNull(io.readText(KnownHostsStore.KNOWN_HOSTS_FILE_NAME))
        )
        assertEquals(listOf(2222), persisted.map { it.port })
    }

    @Test
    fun findReturnsNullUntilTheLoadCompletes() = runTest {
        val saved = entry()
        val io = InMemoryStoreIOProvider(
            mapOf(KnownHostsStore.KNOWN_HOSTS_FILE_NAME to appJson.encodeToString(listOf(saved)))
        )
        val store = KnownHostsStore(
            storeIOProvider = io,
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )

        assertFalse(store.isLoaded.value)
        assertNull(store.find(saved.host, saved.port))

        store.awaitLoaded()

        assertTrue(store.isLoaded.value)
        assertEquals(saved, store.find(saved.host, saved.port))
    }

    @Test
    fun corruptJsonIsMovedAsideAndTheStoreStartsEmpty() = runTest {
        val corruptPayload = "[{\"host\": \"devbox\", truncated"
        val io = InMemoryStoreIOProvider(mapOf(KnownHostsStore.KNOWN_HOSTS_FILE_NAME to corruptPayload))
        val store = store(io)
        store.awaitLoaded()

        assertTrue(store.knownHosts.value.isEmpty())
        val corruptBackupKey = io.files.keys.single {
            it.startsWith("${KnownHostsStore.KNOWN_HOSTS_FILE_NAME}.corrupt.")
        }
        assertEquals(corruptPayload, io.readText(corruptBackupKey))

        store.put(entry())
        assertEquals(corruptPayload, io.readText(corruptBackupKey))
        assertNotNull(store.find("devbox.tailnet.ts.net", 22))
    }

    @Test
    fun hostPortPutsAnIpv6AddressInBrackets() {
        assertEquals("devbox.local:22", entry(host = "devbox.local").hostPort)
        assertEquals("[fd7a:115c:a1e0::1]:2222", entry(host = "fd7a:115c:a1e0::1", port = 2222).hostPort)
    }
}
