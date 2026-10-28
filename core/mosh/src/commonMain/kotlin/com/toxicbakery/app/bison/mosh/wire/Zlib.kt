package com.toxicbakery.app.bison.mosh.wire

/** Compresses and expands the zlib stream (RFC 1950) that holds one instruction (wire fact 5). */
interface Zlib {

    /** Returns the zlib stream of [data]. */
    fun compress(data: ByteArray): ByteArray

    /**
     * Returns the expanded bytes of [data]. Returns null when [data] is not exactly one complete zlib stream, or
     * when the expanded bytes are more than [maxSize]. The expansion stops at [maxSize], so a small bad stream
     * cannot fill the memory. A negative [maxSize] is an error ([IllegalArgumentException]).
     */
    fun decompress(data: ByteArray, maxSize: Int): ByteArray?
}

/** Makes the platform [Zlib]. */
expect fun Zlib(): Zlib
