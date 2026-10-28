package com.toxicbakery.app.bison.mosh.wire

// Shared parts of the UserDiff and HostDiff codecs. A size message has the columns in field 5 and the rows in
// field 6, in both directions.

private const val FIELD_COLUMNS = 5
private const val FIELD_ROWS = 6

/** Returns a message that has only the length-delimited [field] with [value]. */
internal fun messageOf(field: Int, value: ByteArray): ByteArray = ProtoWriter().bytes(field, value).toByteArray()

/** Returns the wire form of a size message. */
internal fun encodeSize(columns: Int, rows: Int): ByteArray =
    ProtoWriter()
        .varint(FIELD_COLUMNS, columns.toULong())
        .varint(FIELD_ROWS, rows.toULong())
        .toByteArray()

/** Reads a size message. Returns the columns and the rows. A size that is not in the Int range is not valid. */
internal fun decodeSize(bytes: ByteArray): Pair<Int, Int> {
    var columns = 0
    var rows = 0
    val reader = ProtoReader(bytes)
    while (reader.next()) {
        when {
            reader.field == FIELD_COLUMNS && reader.isVarint -> columns = reader.varint().toCellCount()
            reader.field == FIELD_ROWS && reader.isVarint -> rows = reader.varint().toCellCount()
            else -> reader.skip()
        }
    }
    return columns to rows
}

/**
 * Calls [action] with the field number and the bytes of each length-delimited field of each entry. The entries
 * are the length-delimited [entryField] fields of [bytes].
 */
internal inline fun forEachEntry(bytes: ByteArray, entryField: Int, action: (field: Int, value: ByteArray) -> Unit) {
    val reader = ProtoReader(bytes)
    while (reader.next()) {
        if (reader.field != entryField || !reader.isLengthDelimited) {
            reader.skip()
            continue
        }
        val entry = ProtoReader(reader.bytes())
        while (entry.next()) {
            if (entry.isLengthDelimited) action(entry.field, entry.bytes()) else entry.skip()
        }
    }
}

/** Returns the last length-delimited [field] of the message [bytes], or no bytes when the field is not there. */
internal fun readBytesField(bytes: ByteArray, field: Int): ByteArray {
    var value = ByteArray(0)
    val reader = ProtoReader(bytes)
    while (reader.next()) {
        if (reader.field == field && reader.isLengthDelimited) value = reader.bytes() else reader.skip()
    }
    return value
}

/** Returns the last varint [field] of the message [bytes], or 0 when the field is not there. */
internal fun readVarintField(bytes: ByteArray, field: Int): ULong {
    var value = 0uL
    val reader = ProtoReader(bytes)
    while (reader.next()) {
        if (reader.field == field && reader.isVarint) value = reader.varint() else reader.skip()
    }
    return value
}

private fun ULong.toCellCount(): Int {
    if (this > Int.MAX_VALUE.toULong()) throw ProtoException("The size $this is not valid.")
    return toInt()
}
