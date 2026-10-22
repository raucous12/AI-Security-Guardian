package lian.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.InvalidKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Runs on the JVM with a software AES key; the Keystore key behaves the same through the Cipher API. */
class SealedFileTest {
    @get:Rule val folder = TemporaryFolder()

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `round-trips and never stores plaintext`() {
        val file = folder.root.resolve("history.bin")
        val sealed = SealedFile(file, "history") { key }
        val secret = "com.example.dating.app".toByteArray()
        sealed.write(secret)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("dating"))
        assertTrue(file.readBytes().copyOf(4).contentEquals("LNS1".toByteArray()))
        val result = sealed.read()
        assertTrue(result is SealedFile.ReadResult.Ok)
        assertArrayEquals(secret, (result as SealedFile.ReadResult.Ok).bytes)
    }

    @Test
    fun `each write uses a fresh IV`() {
        val file = folder.root.resolve("a.bin")
        val sealed = SealedFile(file, "p") { key }
        sealed.write(ByteArray(8))
        val first = file.readBytes()
        sealed.write(ByteArray(8))
        assertNotEquals(first.toList(), file.readBytes().toList())
    }

    @Test
    fun `tampered files are discarded`() {
        val file = folder.root.resolve("t.bin")
        SealedFile(file, "p") { key }.write("hello".toByteArray())
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertEquals(SealedFile.ReadResult.Discarded, SealedFile(file, "p") { key }.read())
        assertFalse(file.exists())
    }

    @Test
    fun `a file sealed for another purpose is rejected`() {
        val file = folder.root.resolve("swap.bin")
        SealedFile(file, "watch") { key }.write("x".toByteArray())
        assertEquals(SealedFile.ReadResult.Discarded, SealedFile(file, "history") { key }.read())
    }

    @Test
    fun `malformed headers are discarded, missing files reported`() {
        val file = folder.root.resolve("m.bin")
        assertEquals(SealedFile.ReadResult.Missing, SealedFile(file, "p") { key }.read())
        file.writeBytes("not a sealed file at all, just text".toByteArray())
        assertEquals(SealedFile.ReadResult.Discarded, SealedFile(file, "p") { key }.read())
    }

    /** Phase 4 defect #1: a key that can't be used right now (device locked) must not destroy the data. */
    @Test
    fun `transient key failures keep the file`() {
        val file = folder.root.resolve("locked.bin")
        SealedFile(file, "p") { key }.write("keep me".toByteArray())
        val locked = SealedFile(file, "p") { throw InvalidKeyException("Key user not authenticated") }
        assertEquals(SealedFile.ReadResult.Unavailable, locked.read())
        assertTrue(file.exists())
        assertTrue(SealedFile(file, "p") { key }.read() is SealedFile.ReadResult.Ok)
    }

    @Test
    fun `fingerprints are stable, keyed and unambiguous`() {
        val a = Fingerprinter(ByteArray(32) { 1 })
        val b = Fingerprinter(ByteArray(32) { 2 })
        assertEquals(a.of("com.example", "accessibility"), a.of("com.example", "accessibility"))
        assertNotEquals(a.of("com.example"), b.of("com.example"))
        assertNotEquals(a.of("ab", "c"), a.of("a", "bc"))
        assertEquals(32, a.of("x").length)
    }
}
