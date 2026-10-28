package com.toxicbakery.app.bison.mosh.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The protobuf reader and writer against the examples of the Protocol Buffers wire format page. */
class ProtoTest {

    @Test
    fun theWriterEncodesTheExamplesOfTheWireFormatPage() {
        assertContentEquals("089601".hexToByteArray(), ProtoWriter().varint(1, 150uL).toByteArray())
        assertContentEquals(
            "120774657374696e67".hexToByteArray(),
            ProtoWriter().bytes(2, "testing".encodeToByteArray()).toByteArray(),
        )
    }

    @Test
    fun theWriterEncodesTheSmallestAndTheLargestVarint() {
        assertContentEquals("0800".hexToByteArray(), ProtoWriter().varint(1, 0uL).toByteArray())
        assertContentEquals(
            "08FFFFFFFFFFFFFFFFFF01".hexToByteArray(),
            ProtoWriter().varint(1, ULong.MAX_VALUE).toByteArray(),
        )
    }

    @Test
    fun theReaderReadsWhatTheWriterWrites() {
        val bytes = ProtoWriter()
            .varint(1, 150uL)
            .bytes(2, "testing".encodeToByteArray())
            .varint(ProtoWireType.MAX_FIELD, ULong.MAX_VALUE)
            .toByteArray()
        val reader = ProtoReader(bytes)
        assertTrue(reader.next())
        assertEquals(1, reader.field)
        assertEquals(150uL, reader.varint())
        assertTrue(reader.next())
        assertEquals(2, reader.field)
        assertContentEquals("testing".encodeToByteArray(), reader.bytes())
        assertTrue(reader.next())
        assertEquals(ProtoWireType.MAX_FIELD, reader.field)
        assertEquals(ULong.MAX_VALUE, reader.varint())
        assertFalse(reader.next())
    }

    @Test
    fun theReaderSkipsEachWireTypeThatItKnows() {
        // Field 1 fixed64, field 2 fixed32, field 3 varint, field 4 bytes, and then field 5 varint 7.
        val bytes = "09 0102030405060708 15 01020304 18 AC02 22 02 6869 28 07".replace(" ", "").hexToByteArray()
        val reader = ProtoReader(bytes)
        repeat(4) {
            assertTrue(reader.next())
            reader.skip()
        }
        assertTrue(reader.next())
        assertEquals(5, reader.field)
        assertEquals(7uL, reader.varint())
        assertFalse(reader.next())
    }

    @Test
    fun theReaderRefusesBadInput() {
        val bad = listOf(
            "0896", // The varint has no end.
            "1205616263", // The length goes past the end.
            "0B", // A start group.
            "0C", // An end group.
            "0E00", // Wire type 6.
            "0F00", // Wire type 7.
            "0001", // Field number 0.
            "08FFFFFFFFFFFFFFFFFF02", // A varint of more than 64 bits.
            "08FFFFFFFFFFFFFFFFFF8001", // A varint of 11 bytes.
            "8080808010", // A tag of more than 32 bits.
            "0D010203", // A fixed32 that goes past the end.
        )
        for (hex in bad) {
            assertFailsWith<ProtoException>(hex) {
                val reader = ProtoReader(hex.hexToByteArray())
                while (reader.next()) reader.skip()
            }
        }
    }

    @Test
    fun theReaderRefusesAValueOfADifferentWireType() {
        val reader = ProtoReader("089601".hexToByteArray())
        assertTrue(reader.next())
        assertFailsWith<ProtoException> { reader.bytes() }
        val bytesReader = ProtoReader("120161".hexToByteArray())
        assertTrue(bytesReader.next())
        assertFailsWith<ProtoException> { bytesReader.varint() }
    }
}
