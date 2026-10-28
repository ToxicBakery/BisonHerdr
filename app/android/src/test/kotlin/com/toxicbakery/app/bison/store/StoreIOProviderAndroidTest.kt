package com.toxicbakery.app.bison.store

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests [StoreIOProviderAndroid] on the JVM with a temporary directory in place of an app directory. */
class StoreIOProviderAndroidTest {

    private val root: File = createTempDirectory("store-io-android").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun theProviderMakesItsDirectoryAtTheFirstWrite() {
        val secretsDirectory = File(root, "secrets")
        val provider = StoreIOProviderAndroid(directory = { secretsDirectory })

        provider.writeAtomic("password-a", "c2VjcmV0")

        assertEquals("c2VjcmV0", provider.readText("password-a"))
        assertEquals(listOf("password-a"), secretsDirectory.list()?.toList())
    }

    @Test
    fun deleteCorruptCopiesRemovesOnlyTheCopiesOfTheFile() {
        val provider = StoreIOProviderAndroid(directory = { root })
        provider.writeAtomic("machines.json", "[{")
        provider.moveCorruptAside("machines.json")
        provider.writeAtomic("machines.json", "[]")
        provider.writeAtomic("settings.json", "{")
        provider.moveCorruptAside("settings.json")

        provider.deleteCorruptCopies("machines.json")

        val names = root.list().orEmpty().toSet()
        assertTrue("machines.json" in names, "$names")
        assertTrue(names.none { it.startsWith("machines.json.corrupt.") }, "$names")
        assertTrue(names.any { it.startsWith("settings.json.corrupt.") }, "$names")
    }
}
