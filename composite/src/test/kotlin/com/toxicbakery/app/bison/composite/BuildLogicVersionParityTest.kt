package com.toxicbakery.app.bison.composite

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildLogicVersionParityTest {

    @Test
    fun `the two copies of buildLogicVersion are identical`() {
        val compositeCopy = extractFunction(File("build.gradle.kts"))
        val settingsCopy = extractFunction(File("../settings.gradle.kts"))
        assertEquals(compositeCopy, settingsCopy)
    }

    private fun extractFunction(file: File): String {
        val lines = file.readLines()
        val start = lines.indexOfFirst { line -> line.trim() == SIGNATURE }
        check(start >= 0) { "No `$SIGNATURE` line in ${file.path}" }
        val indent = lines[start].takeWhile { char -> char == ' ' }
        val end = (start + 1 until lines.size).firstOrNull { index -> lines[index] == "$indent}" }
        checkNotNull(end) { "No closing brace for buildLogicVersion() in ${file.path}" }
        return lines.subList(start, end + 1)
            .joinToString("\n") { line -> line.removePrefix(indent) }
            .replace("java.security.MessageDigest", "MessageDigest")
    }

    private companion object {
        const val SIGNATURE = "fun buildLogicVersion(root: File): String {"
    }
}
