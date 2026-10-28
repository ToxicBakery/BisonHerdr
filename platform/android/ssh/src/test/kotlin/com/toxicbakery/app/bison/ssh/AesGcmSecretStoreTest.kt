package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests the file format and the checks of [AesGcmSecretStore] with a software key. [AndroidKeystoreSecretStore] uses
 * the same code with a key in AndroidKeyStore.
 */
class AesGcmSecretStoreTest {

    private val io = InMemoryStoreIOProvider()
    private val keySource = SoftwareSecretKeySource()
    private val store = softwareKeySecretStore(io, keySource)

    @Test
    fun aSecretMakesARoundTrip() = runTest {
        store.put(REF, PASSWORD.encodeToByteArray())

        assertContentEquals(PASSWORD.encodeToByteArray(), store.get(REF))
    }

    @Test
    fun anUnknownReferenceGivesNull() = runTest {
        assertNull(store.get(REF))
        assertNull(store.get("../machines.json"))
    }

    @Test
    fun theFileHasTheIvAndTheCiphertextAndNoPlainText() = runTest {
        store.put(REF, PASSWORD.encodeToByteArray())

        val text = assertNotNull(io.textAt(REF))
        assertFalse(PASSWORD in text)
        val payload = Base64.getDecoder().decode(text)
        // A 12-byte IV, the ciphertext of the same length as the password, and a 16-byte tag.
        assertEquals(IV_BYTES + PASSWORD.length + TAG_BYTES, payload.size)
        val plainText = PASSWORD.encodeToByteArray().toList()
        assertFalse(payload.toList().windowed(plainText.size).any { it == plainText }, "The file has the password")
        assertEquals(setOf(REF), io.files.keys, "The store left a temporary file")
    }

    @Test
    fun eachPutUsesANewIv() = runTest {
        store.put(REF, PASSWORD.encodeToByteArray())
        val first = Base64.getDecoder().decode(io.textAt(REF)).copyOf(IV_BYTES)
        store.put(REF, PASSWORD.encodeToByteArray())
        val second = Base64.getDecoder().decode(io.textAt(REF)).copyOf(IV_BYTES)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun aSecretFileOfADifferentReferenceDoesNotDecrypt() = runTest {
        store.put(REF, PASSWORD.encodeToByteArray())
        io.writeAtomic(OTHER_REF, assertNotNull(io.textAt(REF)))

        // The reference is the additional authenticated data, so the tag check fails.
        assertNull(store.get(OTHER_REF))
    }

    @Test
    fun aChangedFileDoesNotDecrypt() = runTest {
        store.put(REF, PASSWORD.encodeToByteArray())
        val payload = Base64.getDecoder().decode(io.textAt(REF))
        payload[payload.size - 1] = (payload.last().toInt() xor 1).toByte()
        io.writeAtomic(REF, Base64.getEncoder().encodeToString(payload))

        assertNull(store.get(REF))
    }

    @Test
    fun aFileThatIsNotASecretGivesNull() = runTest {
        io.writeAtomic(REF, "not base64 !")
        assertNull(store.get(REF))

        io.writeAtomic(OTHER_REF, Base64.getEncoder().encodeToString(ByteArray(IV_BYTES)))
        assertNull(store.get(OTHER_REF))
    }

    @Test
    fun aRestoredFileDoesNotDecryptWithTheKeyOfADifferentDevice() = runTest {
        store.put(REF, PASSWORD.encodeToByteArray())
        val otherDevice = softwareKeySecretStore(InMemoryStoreIOProvider(io.files), SoftwareSecretKeySource())
        otherDevice.put(OTHER_REF, byteArrayOf(1))

        assertNull(otherDevice.get(REF))
    }

    @Test
    fun aDeviceWithNoKeyGivesNullAndMakesNoKey() = runTest {
        store.put(REF, PASSWORD.encodeToByteArray())
        val noKey = SoftwareSecretKeySource()
        val restored = softwareKeySecretStore(InMemoryStoreIOProvider(io.files), noKey)

        assertNull(restored.get(REF))
        assertNull(noKey.existingKey())
    }

    @Test
    fun removeDeletesTheFile() = runTest {
        store.put(REF, PASSWORD.encodeToByteArray())

        store.remove(REF)
        store.remove(OTHER_REF)

        assertNull(store.get(REF))
        assertTrue(io.files.isEmpty())
    }

    @Test
    fun anInvalidReferenceIsRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { store.put("../machines.json", PASSWORD.encodeToByteArray()) }
        assertTrue(io.files.isEmpty())
        assertNull(keySource.existingKey(), "A refused put made a key")
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val REF = "password-a"
        const val OTHER_REF = "password-b"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
