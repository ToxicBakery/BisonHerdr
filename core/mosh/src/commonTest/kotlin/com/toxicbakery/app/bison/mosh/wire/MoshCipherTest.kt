package com.toxicbakery.app.bison.mosh.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The platform [MoshCipher] against the sample results in Appendix A of RFC 7253. The Mosh datagram has no
 * associated data, so the test uses the six samples with an empty A.
 */
class MoshCipherTest {

    private val cipher = MoshCipher(MoshKey.of("000102030405060708090A0B0C0D0E0F".hexToByteArray()))

    @Test
    fun eachSampleWithNoAssociatedDataSealsToTheRfcCiphertext() {
        for (sample in samples) {
            assertContentEquals(
                sample.ciphertext.hexToByteArray(),
                cipher.seal(sample.nonce.hexToByteArray(), sample.plaintext.hexToByteArray()),
                "nonce ${sample.nonce}",
            )
        }
    }

    @Test
    fun eachSampleCiphertextOpensToItsPlaintext() {
        for (sample in samples) {
            assertContentEquals(
                sample.plaintext.hexToByteArray(),
                cipher.open(sample.nonce.hexToByteArray(), sample.ciphertext.hexToByteArray()),
                "nonce ${sample.nonce}",
            )
        }
    }

    @Test
    fun aChangedBitOrADifferentNonceDoesNotOpen() {
        val sample = samples.last()
        val sealed = sample.ciphertext.hexToByteArray()
        for (index in sealed.indices) {
            val changed = sealed.copyOf().also { bytes -> bytes[index] = (bytes[index].toInt() xor 1).toByte() }
            assertNull(cipher.open(sample.nonce.hexToByteArray(), changed), "byte $index")
        }
        assertNull(cipher.open(samples.first().nonce.hexToByteArray(), sealed))
    }

    @Test
    fun aSealedValueShorterThanTheTagDoesNotOpen() {
        for (size in 0 until MoshCipher.TAG_SIZE) {
            assertNull(cipher.open(samples.first().nonce.hexToByteArray(), ByteArray(size)))
        }
    }

    @Test
    fun aDifferentKeyDoesNotOpen() {
        val other = MoshCipher(MoshKey.of(ByteArray(MoshKey.SIZE)))
        val sample = samples.last()
        assertNull(other.open(sample.nonce.hexToByteArray(), sample.ciphertext.hexToByteArray()))
    }

    @Test
    fun aNonceOfADifferentSizeIsAnError() {
        assertFailsWith<IllegalArgumentException> { cipher.seal(ByteArray(8), ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { cipher.open(ByteArray(15), ByteArray(MoshCipher.TAG_SIZE)) }
    }

    private class Sample(val nonce: String, val plaintext: String, val ciphertext: String)

    private companion object {
        val samples = listOf(
            Sample(
                nonce = "BBAA99887766554433221100",
                plaintext = "",
                ciphertext = "785407BFFFC8AD9EDCC5520AC9111EE6",
            ),
            Sample(
                nonce = "BBAA99887766554433221103",
                plaintext = "0001020304050607",
                ciphertext = "45DD69F8F5AAE72414054CD1F35D82760B2CD00D2F99BFA9",
            ),
            Sample(
                nonce = "BBAA99887766554433221106",
                plaintext = "000102030405060708090A0B0C0D0E0F",
                ciphertext = "5CE88EC2E0692706A915C00AEB8B2396F40E1C743F52436BDF06D8FA1ECA343D",
            ),
            Sample(
                nonce = "BBAA99887766554433221109",
                plaintext = "000102030405060708090A0B0C0D0E0F1011121314151617",
                ciphertext = "221BD0DE7FA6FE993ECCD769460A0AF2D6CDED0C395B1C3C" +
                    "E725F32494B9F914D85C0B1EB38357FF",
            ),
            Sample(
                nonce = "BBAA9988776655443322110C",
                plaintext = "000102030405060708090A0B0C0D0E0F1011121314151617" +
                    "18191A1B1C1D1E1F",
                ciphertext = "2942BFC773BDA23CABC6ACFD9BFD5835BD300F0973792EF4" +
                    "6040C53F1432BCDFB5E1DDE3BC18A5F840B52E653444D5DF",
            ),
            Sample(
                nonce = "BBAA9988776655443322110F",
                plaintext = "000102030405060708090A0B0C0D0E0F1011121314151617" +
                    "18191A1B1C1D1E1F2021222324252627",
                ciphertext = "4412923493C57D5DE0D700F753CCE0D1D2D95060122E9F15" +
                    "A5DDBFC5787E50B5CC55EE507BCB084E479AD363AC366B95" +
                    "A98CA5F3000B1479",
            ),
        )
    }
}
