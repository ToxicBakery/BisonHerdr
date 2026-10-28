package com.toxicbakery.app.bison.composite

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The composite build has a copy of the daemon JVM criteria of the root build, so that both
 * builds run on the same JDK. The updateDaemonJvm task changes only the root file. This test
 * fails when the copy has different properties. The comments of the two files can be different.
 */
class DaemonJvmPropertiesParityTest {

    @Test
    fun `the composite build has the same daemon JVM criteria as the root build`() {
        val rootCriteria = load(File("../gradle/gradle-daemon-jvm.properties"))
        val compositeCriteria = load(File("gradle/gradle-daemon-jvm.properties"))
        assertTrue(rootCriteria.isNotEmpty(), "The root file has no properties")
        assertEquals(rootCriteria, compositeCriteria)
    }

    private fun load(file: File): Map<String, String> {
        check(file.isFile) { "No file at ${file.path}" }
        val properties = Properties()
        file.reader().use { reader -> properties.load(reader) }
        return properties.stringPropertyNames()
            .associateWith { name -> properties.getProperty(name) }
            .toSortedMap()
    }
}
