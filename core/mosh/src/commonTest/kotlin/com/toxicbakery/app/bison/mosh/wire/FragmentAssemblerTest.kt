package com.toxicbakery.app.bison.mosh.wire

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The join of fragments with order, loss, duplicates, new IDs for resends, and the limits (wire fact 5). */
class FragmentAssemblerTest {

    private val stream = ByteArray(95) { index -> index.toByte() }

    @Test
    fun fragmentsInOrderGiveTheStreamWithTheLastFragment() {
        val assembler = FragmentAssembler()
        val fragments = Fragment.split(1uL, stream, maxContentSize = 10)
        for (fragment in fragments.dropLast(1)) assertNull(assembler.add(fragment))
        assertContentEquals(stream, assembler.add(fragments.last()))
        assertEquals(0, assembler.pendingCount)
    }

    @Test
    fun fragmentsInEachOrderGiveTheStream() {
        val random = Random(SEED)
        repeat(20) {
            val assembler = FragmentAssembler()
            val fragments = Fragment.split(1uL, stream, maxContentSize = 7).shuffled(random)
            val results = fragments.map { fragment -> assembler.add(fragment) }
            results.dropLast(1).forEach { result -> assertNull(result) }
            assertContentEquals(stream, results.last())
        }
    }

    @Test
    fun aDuplicateFragmentHasNoEffect() {
        val assembler = FragmentAssembler()
        val fragments = Fragment.split(1uL, stream, maxContentSize = 10)
        assertNull(assembler.add(fragments[0]))
        assertNull(assembler.add(fragments[0]))
        assertNull(assembler.add(fragments.last()))
        assertNull(assembler.add(fragments.last()))
        for (fragment in fragments.subList(1, fragments.lastIndex - 1)) assertNull(assembler.add(fragment))
        assertContentEquals(stream, assembler.add(fragments[fragments.lastIndex - 1]))
    }

    @Test
    fun aLostFragmentStopsItsIdAndTheResendWithANewIdCompletes() {
        val assembler = FragmentAssembler()
        val first = Fragment.split(1uL, stream, maxContentSize = 10)
        first.filter { fragment -> fragment.number != 4 }.forEach { fragment -> assertNull(assembler.add(fragment)) }
        val resend = Fragment.split(2uL, stream, maxContentSize = 10)
        resend.dropLast(1).forEach { fragment -> assertNull(assembler.add(fragment)) }
        assertContentEquals(stream, assembler.add(resend.last()))
        assertEquals(1, assembler.pendingCount)
    }

    @Test
    fun theFragmentsOfTwoIdsCanMix() {
        val assembler = FragmentAssembler()
        val other = ByteArray(33) { 0x41 }
        val a = Fragment.split(5uL, stream, maxContentSize = 10)
        val b = Fragment.split(6uL, other, maxContentSize = 10)
        val results = (a.zip(b) { first, second -> listOf(first, second) }.flatten() + a.drop(b.size))
            .mapNotNull { fragment -> assembler.add(fragment)?.let { joined -> fragment.instructionId to joined } }
        assertEquals(listOf(6uL, 5uL), results.map { (id, _) -> id })
        assertContentEquals(other, results[0].second)
        assertContentEquals(stream, results[1].second)
    }

    @Test
    fun aFragmentNumberAtTheLimitIsNotKept() {
        val assembler = FragmentAssembler(maxFragments = 4)
        assertNull(assembler.add(Fragment(1uL, 4, isFinal = true, content = byteArrayOf(1))))
        assertEquals(0, assembler.pendingCount)
        val fragments = Fragment.split(2uL, ByteArray(4) { it.toByte() }, maxContentSize = 1)
        fragments.dropLast(1).forEach { fragment -> assertNull(assembler.add(fragment)) }
        assertContentEquals(byteArrayOf(0, 1, 2, 3), assembler.add(fragments.last()))
    }

    @Test
    fun theFullLimitOfFragmentsCompletes() {
        val assembler = FragmentAssembler()
        val big = ByteArray(FragmentAssembler.MAX_FRAGMENTS * 3) { index -> (index * 7).toByte() }
        val fragments = Fragment.split(1uL, big, maxContentSize = 3)
        assertEquals(FragmentAssembler.MAX_FRAGMENTS, fragments.size)
        fragments.dropLast(1).forEach { fragment -> assertNull(assembler.add(fragment)) }
        assertContentEquals(big, assembler.add(fragments.last()))
    }

    @Test
    fun anIdWithMoreThanTheStreamLimitIsRemoved() {
        val assembler = FragmentAssembler(maxStreamSize = 20)
        val fragments = Fragment.split(1uL, ByteArray(21), maxContentSize = 10)
        fragments.forEach { fragment -> assertNull(assembler.add(fragment)) }
        assertEquals(0, assembler.pendingCount)
        assertNull(assembler.add(Fragment(2uL, 0, isFinal = true, content = ByteArray(21))))
        assertContentEquals(ByteArray(20), assembler.add(Fragment(3uL, 0, isFinal = true, content = ByteArray(20))))
    }

    @Test
    fun theAssemblerKeepsThePartsOfTheFourNewestIds() {
        val assembler = FragmentAssembler()
        val parts = (1uL..5uL).associateWith { id -> Fragment.split(id, stream, maxContentSize = 50) }
        for (id in 1uL..5uL) assertNull(assembler.add(parts.getValue(id)[0]))
        assertEquals(FragmentAssembler.MAX_INSTRUCTIONS, assembler.pendingCount)
        // ID 5 pushed out ID 1. A new part of ID 1 is older than all kept IDs, so the assembler does not keep it.
        assertNull(assembler.add(parts.getValue(1uL)[1]))
        assertEquals(FragmentAssembler.MAX_INSTRUCTIONS, assembler.pendingCount)
        for (id in 2uL..5uL) assertContentEquals(stream, assembler.add(parts.getValue(id)[1]))
    }

    @Test
    fun aSingleFragmentCompletesAlsoWhenAllSlotsHoldNewerIds() {
        val assembler = FragmentAssembler()
        for (id in 10uL..13uL) assertNull(assembler.add(Fragment.split(id, stream, maxContentSize = 50)[0]))
        assertContentEquals(stream, assembler.add(Fragment(1uL, 0, isFinal = true, content = stream)))
        assertEquals(FragmentAssembler.MAX_INSTRUCTIONS, assembler.pendingCount)
    }

    @Test
    fun partsThatDoNotAgreeRemoveTheirId() {
        // Two final fragments with different numbers.
        FragmentAssembler().run {
            assertNull(add(Fragment(1uL, 2, isFinal = true, content = byteArrayOf(2))))
            assertNull(add(Fragment(1uL, 3, isFinal = true, content = byteArrayOf(3))))
            assertEquals(0, pendingCount)
        }
        // A fragment after the final fragment.
        FragmentAssembler().run {
            assertNull(add(Fragment(1uL, 1, isFinal = true, content = byteArrayOf(1))))
            assertNull(add(Fragment(1uL, 2, isFinal = false, content = byteArrayOf(2))))
            assertEquals(0, pendingCount)
        }
        // A final fragment before a fragment that is already here.
        FragmentAssembler().run {
            assertNull(add(Fragment(1uL, 3, isFinal = false, content = byteArrayOf(3))))
            assertNull(add(Fragment(1uL, 1, isFinal = true, content = byteArrayOf(1))))
            assertEquals(0, pendingCount)
        }
        // A duplicate with a different final flag.
        FragmentAssembler().run {
            assertNull(add(Fragment(1uL, 1, isFinal = false, content = byteArrayOf(1))))
            assertNull(add(Fragment(1uL, 1, isFinal = true, content = byteArrayOf(1))))
            assertEquals(0, pendingCount)
        }
    }

    private companion object {
        const val SEED = 22
    }
}
