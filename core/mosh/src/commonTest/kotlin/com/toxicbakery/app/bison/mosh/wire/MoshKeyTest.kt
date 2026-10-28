package com.toxicbakery.app.bison.mosh.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The key text of the `MOSH CONNECT` line (wire fact 1). */
class MoshKeyTest {

    @Test
    fun aKeyOf22Base64CharactersGives16Bytes() {
        val key = assertNotNull(MoshKey.parse("4zq56dj6LEu9rfDSuu4tMA"))
        assertContentEquals("e33ab9e9d8fa2c4bbdadf0d2baee2d30".hexToByteArray(), key.toByteArray())
    }

    @Test
    fun aKeyWithPaddingOrADifferentLengthIsNotValid() {
        assertNull(MoshKey.parse("4zq56dj6LEu9rfDSuu4tMA=="))
        assertNull(MoshKey.parse("4zq56dj6LEu9rfDSuu4tM"))
        assertNull(MoshKey.parse("4zq56dj6LEu9rfDSuu4tMAA"))
        assertNull(MoshKey.parse(""))
    }

    @Test
    fun aKeyWithACharacterOutsideTheAlphabetIsNotValid() {
        assertNull(MoshKey.parse("4zq56dj6LEu9rfDSuu4t-A"))
        assertNull(MoshKey.parse("4zq56dj6LEu9rfDSuu4t A"))
    }

    @Test
    fun aKeyWithBitsAfterTheLastByteIsNotValid() {
        // The last character holds 2 bits of the key and 4 bits that must be 0. "B" sets one of the 4 bits.
        assertNull(MoshKey.parse("4zq56dj6LEu9rfDSuu4tMB"))
    }

    @Test
    fun theTextOfAKeyDoesNotShowTheKey() {
        val key = assertNotNull(MoshKey.parse("4zq56dj6LEu9rfDSuu4tMA"))
        assertFalse(key.toString().contains("4zq56dj6"))
        assertFalse(key.toString().contains("e33ab9e9"))
    }

    @Test
    fun aKeyFromBytesNeeds16BytesAndKeepsACopy() {
        assertFailsWith<IllegalArgumentException> { MoshKey.of(ByteArray(15)) }
        val bytes = ByteArray(MoshKey.SIZE) { index -> index.toByte() }
        val key = MoshKey.of(bytes)
        bytes.fill(0)
        assertContentEquals(ByteArray(MoshKey.SIZE) { index -> index.toByte() }, key.toByteArray())
    }
}
