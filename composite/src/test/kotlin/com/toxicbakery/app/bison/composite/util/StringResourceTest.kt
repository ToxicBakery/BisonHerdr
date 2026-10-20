package com.toxicbakery.app.bison.composite.util

import com.toxicbakery.app.bison.composite.util.StringResource.Specifier
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StringResourceTest {

    private val temporaryFiles = mutableListOf<File>()

    @AfterTest
    fun deleteTemporaryFiles() {
        temporaryFiles.forEach { file -> file.delete() }
    }

    private fun stringsFile(body: String): File {
        val file = File.createTempFile("strings", ".xml")
        temporaryFiles += file
        file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body\n</resources>")
        return file
    }

    @Test
    fun `a value with no format specifier has an empty list`() {
        assertEquals(emptyList(), StringResource.formatSpecifiers("Configure Machine"))
    }

    @Test
    fun `positional specifiers keep their index and conversion`() {
        assertEquals(
            listOf(Specifier(1, 'd'), Specifier(2, 'd')),
            StringResource.formatSpecifiers("%1\$d/%2\$d")
        )
    }

    @Test
    fun `a specifier with no argument position has a null index`() {
        assertEquals(listOf(Specifier(null, 's')), StringResource.formatSpecifiers("Version %s"))
    }

    @Test
    fun `a literal percent sign takes no argument`() {
        assertEquals(
            listOf(Specifier(1, 'd')),
            StringResource.formatSpecifiers("%1\$d%% brightness")
        )
    }

    @Test
    fun `a space before a percent sign is not a specifier`() {
        assertEquals(emptyList(), StringResource.formatSpecifiers("100 % de la batterie"))
        assertEquals(emptyList(), StringResource.formatSpecifiers("Helligkeit 50 % maximal"))
    }

    @Test
    fun `a letter that is not a conversion character is not a specifier`() {
        assertEquals(emptyList(), StringResource.formatSpecifiers("100%zip"))
    }

    @Test
    fun `a percent sign followed by a conversion character is a specifier`() {
        assertEquals(listOf(Specifier(null, 'd')), StringResource.formatSpecifiers("100%des"))
    }

    @Test
    fun `a changed conversion character is a different specifier`() {
        assertNotEquals(
            StringResource.formatSpecifiers("Version %1\$s"),
            StringResource.formatSpecifiers("Version %1\$d")
        )
    }

    @Test
    fun `a repeated argument position appears twice`() {
        assertEquals(
            listOf(Specifier(1, 's'), Specifier(1, 's')),
            StringResource.formatSpecifiers("%1\$s (%1\$s)")
        )
    }

    @Test
    fun `a width and a precision do not change the specifier`() {
        assertEquals(listOf(Specifier(1, 'f')), StringResource.formatSpecifiers("%1\$.2f ms"))
    }

    @Test
    fun `a bare apostrophe is a finding`() {
        assertTrue(StringResource.hasUnescapedApostrophe("Couldn't connect to host"))
    }

    @Test
    fun `a backslash escapes the apostrophe`() {
        assertFalse(StringResource.hasUnescapedApostrophe("Couldn\\'t connect to host"))
    }

    @Test
    fun `a value wrapped in double quotes keeps its apostrophes`() {
        assertFalse(StringResource.hasUnescapedApostrophe("\"Couldn't connect to host\""))
    }

    @Test
    fun `a value that only starts and ends with a quote is not wrapped`() {
        assertTrue(StringResource.hasUnescapedApostrophe("\"a\" and 'b'"))
    }

    @Test
    fun `a value with no apostrophe is clean`() {
        assertFalse(StringResource.hasUnescapedApostrophe("Host konnte nicht verbunden werden"))
        assertFalse(StringResource.hasUnescapedApostrophe("Impossible de lire l’appareil"))
    }

    @Test
    fun `an apos entity decodes to a bare apostrophe and is a finding`() {
        val file = stringsFile("""    <string name="a">Couldn&apos;t connect to host</string>""")
        val value = StringResource.read(file).strings.getValue("a")
        assertEquals("Couldn't connect to host", value)
        assertTrue(StringResource.hasUnescapedApostrophe(value))
    }

    @Test
    fun `a value with words is translatable`() {
        assertTrue(StringResource.isTranslatable("Save Machine"))
        assertTrue(StringResource.isTranslatable("Version %1\$s"))
    }

    @Test
    fun `a value of only specifiers and punctuation is not translatable`() {
        assertFalse(StringResource.isTranslatable("%1\$d/%2\$d"))
        assertFalse(StringResource.isTranslatable("%1\$s • [#%2\$s]"))
        assertFalse(StringResource.isTranslatable("+"))
        assertFalse(StringResource.isTranslatable("-"))
    }

    @Test
    fun `read returns each string in file order and ignores a comment`() {
        val file = stringsFile(
            """
            |    <string name="app_name">BisonHerdr</string>
            |    <!-- A comment must not become a string. -->
            |    <string name="navigation_back">Back</string>
            """.trimMargin()
        )
        val parsed = StringResource.read(file)
        assertEquals(
            mapOf("app_name" to "BisonHerdr", "navigation_back" to "Back"),
            parsed.strings
        )
        assertEquals(emptyList(), parsed.duplicates)
    }

    @Test
    fun `a repeated name is reported`() {
        val file = stringsFile(
            """
            |    <string name="a">First</string>
            |    <string name="a">Second</string>
            """.trimMargin()
        )
        val parsed = StringResource.read(file)
        assertEquals(listOf("a"), parsed.duplicates)
        assertEquals("Second", parsed.strings.getValue("a"))
    }

    @Test
    fun `core resources strings xml is valid and clean`() {
        val file = File("../core/resources/src/commonMain/composeResources/values/strings.xml")
        val parsed = StringResource.read(file)
        assertEquals("BisonHerdr", parsed.strings["app_name"])
        assertEquals(emptyList(), parsed.duplicates)
        parsed.strings.forEach { (name, value) ->
            assertFalse(
                StringResource.hasUnescapedApostrophe(value),
                "Unescaped apostrophe in $name"
            )
            val specifiers = StringResource.formatSpecifiers(value)
            assertFalse(
                specifiers.size > 1 && specifiers.any { it.index == null },
                "Unpositioned specifier in multi-specifier string $name"
            )
        }
    }
}
