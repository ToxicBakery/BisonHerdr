package com.toxicbakery.app.bison.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests [HostMachine.isValidHerdrSessionName] against the rule of Herdr 0.9.5. */
class HerdrSessionNameTest {

    @Test
    fun herdrAcceptsLettersDigitsDotsUnderscoresAndHyphens() {
        listOf("work", "default", "Work-2", "a.b_c-d", "...", ".hidden", "x", "a".repeat(64)).forEach { name ->
            assertTrue(HostMachine.isValidHerdrSessionName(name), name)
        }
    }

    @Test
    fun herdrRefusesOtherNames() {
        listOf(
            "",
            ".",
            "..",
            "a".repeat(65),
            "my work",
            " work",
            "it's",
            "work;id",
            "a/b",
            "\$HOME",
            "café",
            "tab\tname",
        ).forEach { name ->
            assertFalse(HostMachine.isValidHerdrSessionName(name), name)
        }
    }
}
