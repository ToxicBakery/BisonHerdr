package com.toxicbakery.app.bison.composite.util

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.GradleException
import org.w3c.dom.Element

/**
 * Reads and inspects an Android `strings.xml` file.
 */
object StringResource {

    private val FORMAT_SPECIFIER =
        Regex("""%%|%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?([bBhHsScCdoxXeEfgGaAtTn])""")

    /** One format specifier. [index] is null when the specifier gives no argument position. */
    data class Specifier(val index: Int?, val conversion: Char) {
        override fun toString(): String = if (index == null) "%$conversion" else "%$index\$$conversion"
    }

    /** The `<string>` elements of one file, and the names that the file repeats. */
    class Parsed(val strings: Map<String, String>, val duplicates: List<String>)

    fun read(file: File): Parsed {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = try {
            factory.newDocumentBuilder().parse(file)
        } catch (error: Exception) {
            throw GradleException("Cannot parse ${file.path}: ${error.message}", error)
        }
        val nodes = document.getElementsByTagName("string")
        val strings = LinkedHashMap<String, String>(nodes.length)
        val duplicates = mutableListOf<String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            if (strings.put(name, element.textContent) != null) duplicates += name
        }
        return Parsed(strings, duplicates)
    }

    fun formatSpecifiers(value: String): List<Specifier> = FORMAT_SPECIFIER
        .findAll(value)
        .filterNot { match -> match.value == "%%" }
        .map { match ->
            Specifier(
                index = match.groupValues[1].toIntOrNull(),
                conversion = match.groupValues[2].first()
            )
        }
        .toList()

    fun hasUnescapedApostrophe(value: String): Boolean {
        var index = 0
        var quoted = false
        while (index < value.length) {
            when (value[index]) {
                '\\' -> index += 2
                '"' -> {
                    quoted = !quoted
                    index += 1
                }
                '\'' -> if (quoted) index += 1 else return true
                else -> index += 1
            }
        }
        return false
    }

    fun isTranslatable(value: String): Boolean =
        FORMAT_SPECIFIER.replace(value, "").any { character -> character.isLetter() }
}
