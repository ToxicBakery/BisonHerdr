package com.toxicbakery.app.bison.composite.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseClassesTest {

    private val temporaryFiles = mutableListOf<File>()

    @AfterTest
    fun deleteTemporaryFiles() {
        temporaryFiles.forEach { file -> file.deleteRecursively() }
    }

    @Test
    fun `a class file path gives the class name`() {
        assertEquals("com.example.Debug", ReleaseClasses.classNameOf("com/example/Debug.class"))
        assertEquals("com.example.Debug\$Inner", ReleaseClasses.classNameOf("com/example/Debug\$Inner.class"))
        assertEquals("com.example.Debug", ReleaseClasses.classNameOf("com\\example\\Debug.class"))
    }

    @Test
    fun `a file that is not a class gives no class name`() {
        assertNull(ReleaseClasses.classNameOf("com/example/strings.xml"))
        assertNull(ReleaseClasses.classNameOf("module-info.class"))
        assertNull(ReleaseClasses.classNameOf("com/example/package-info.class"))
        assertNull(ReleaseClasses.classNameOf("META-INF/versions/9/com/example/Debug.class"))
    }

    @Test
    fun `the classes of a jar and of a directory are read`() {
        val jar = File.createTempFile("classes", ".jar").also { temporaryFiles += it }
        ZipOutputStream(jar.outputStream()).use { zip ->
            listOf("com/example/Main.class", "com/example/Main\$1.class", "META-INF/MANIFEST.MF").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.closeEntry()
            }
        }
        val directory = createTempDirectory()
        directory.resolve("com/example").mkdirs()
        directory.resolve("com/example/DebugKt.class").writeText("")
        directory.resolve("com/example/notes.txt").writeText("")

        assertEquals(listOf("com.example.Main", "com.example.Main\$1"), ReleaseClasses.classesInJar(jar).sorted())
        assertEquals(listOf("com.example.DebugKt"), ReleaseClasses.classesInDirectory(directory))
    }

    @Test
    fun `the mapping gives the classes and the classes of inlined members`() {
        val mapping = ReleaseClasses.readMapping(
            """
            # compiler: R8
            # {"id":"com.android.tools.r8.mapping","version":"2.2"}
            com.example.Main -> com.example.Main:
                com.example.Debug field -> a
                1:1:void <init>():12:12 -> <init>
                1:4:void com.example.Debug.run(com.example.Other):8:11 -> b
                5:5:com.example.Other com.example.Helper${'$'}Inner.make():3:3 -> b
                # {"id":"sourceFile","fileName":"Main.kt"}
            com.example.Other -> a:
            """.trimIndent().lineSequence()
        )

        assertEquals(setOf("com.example.Main", "com.example.Other"), mapping.classes)
        assertEquals(setOf("com.example.Debug", "com.example.Helper\$Inner"), mapping.inlinedFrom)
    }

    @Test
    fun `a name matches the class, its nested classes, and its package`() {
        assertTrue(ReleaseClasses.matches("com.example.Debug", "com.example.Debug"))
        assertTrue(ReleaseClasses.matches("com.example.Debug\$Companion", "com.example.Debug"))
        assertTrue(ReleaseClasses.matches("com.example.fake.Server", "com.example.fake"))
        assertFalse(ReleaseClasses.matches("com.example.DebugModule", "com.example.Debug"))
        assertFalse(ReleaseClasses.matches("com.example.fakery.Server", "com.example.fake"))
    }

    @Test
    fun `a release build with no debug-only class passes`() {
        val result = check()

        assertTrue(result.isEmpty, result.toString())
    }

    @Test
    fun `a debug-only class in the release mapping fails`() {
        val result = check(
            mappingClasses = setOf("com.example.Real", "com.example.Simulated\$Companion"),
            inlinedFrom = setOf("com.example.fake.Server"),
            releaseClasses = RELEASE_CLASSES + "com.example.Simulated",
        )

        assertEquals(listOf("com.example.Simulated\$Companion", "com.example.fake.Server"), result.shipped)
    }

    @Test
    fun `a debug-only class with no name fails`() {
        val result = check(debugClasses = DEBUG_CLASSES + "com.example.NewHelper" + "com.example.NewHelper\$1")

        assertEquals(listOf("com.example.NewHelper"), result.undeclared)
    }

    @Test
    fun `a class with a release twin and a class outside the package need no name`() {
        val result = check(
            debugClasses = DEBUG_CLASSES + "com.example.DebugModuleKt\$debug\$1" + "androidx.tooling.Preview",
        )

        assertTrue(result.undeclared.isEmpty(), result.toString())
    }

    @Test
    fun `a name that matches no debug class fails`() {
        val result = check(debugOnlyNames = DEBUG_ONLY_NAMES + "com.example.Renamed")

        assertEquals(listOf("com.example.Renamed"), result.unused)
    }

    @Test
    fun `a required class that is only inlined is missing`() {
        val result = check(mappingClasses = setOf("com.example.Main"), inlinedFrom = setOf("com.example.Real"))

        assertEquals(listOf("com.example.Real"), result.missing)
    }

    private fun check(
        mappingClasses: Set<String> = setOf("com.example.Main", "com.example.Real", "com.example.DebugModuleKt"),
        inlinedFrom: Set<String> = emptySet(),
        debugClasses: Set<String> = DEBUG_CLASSES,
        releaseClasses: Set<String> = RELEASE_CLASSES,
        debugOnlyNames: Set<String> = DEBUG_ONLY_NAMES,
    ): ReleaseClasses.Result = ReleaseClasses.check(
        mapping = ReleaseClasses.Mapping(classes = mappingClasses, inlinedFrom = inlinedFrom),
        debugClasses = debugClasses,
        releaseClasses = releaseClasses,
        debugOnlyNames = debugOnlyNames,
        checkedPackage = "com.example",
        requiredClasses = setOf("com.example.Real"),
    )

    private fun createTempDirectory(): File =
        File.createTempFile("classes", "").also { file ->
            file.delete()
            file.mkdirs()
            temporaryFiles += file
        }

    private companion object {
        val RELEASE_CLASSES = setOf("com.example.Main", "com.example.Real", "com.example.DebugModuleKt")
        val DEBUG_CLASSES = RELEASE_CLASSES + setOf(
            "com.example.Simulated",
            "com.example.Simulated\$Companion",
            "com.example.fake.Server",
        )
        val DEBUG_ONLY_NAMES = setOf("com.example.Simulated", "com.example.fake")
    }
}
