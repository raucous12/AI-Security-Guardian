package lian.core.storage

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Write to a temp file, fsync, then atomically replace: a crash never leaves a half-written file. */
object AtomicWrite {
    fun write(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile ?: error("no parent directory for $target")
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * One AES-256-GCM encrypted file. Layout: "LNS1" | format version | IV length | IV | ciphertext+tag.
 * The header and the file's [purpose] are authenticated as AAD, so files can't be swapped between uses.
 *
 * Everything stored is re-derivable by running a check again, so unreadable data is simply discarded,
 * but only when it is genuinely unreadable (Phase 4, defect #1): a transient Keystore failure, such
 * as reading while the device is locked, keeps the file.
 */
class SealedFile(private val file: File, private val purpose: String, private val key: () -> SecretKey) {

    sealed interface ReadResult {
        class Ok(val bytes: ByteArray) : ReadResult
        data object Missing : ReadResult
        /** Tampered, corrupt or encrypted with a key that no longer exists; the file was deleted. */
        data object Discarded : ReadResult
        /** The key can't be used right now (e.g. device locked). Data kept; try again later. */
        data object Unavailable : ReadResult
    }

    fun write(plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())   // the Keystore generates the IV (randomized encryption)
        cipher.updateAAD(aad())
        val sealed = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        AtomicWrite.write(file, HEADER + byteArrayOf(iv.size.toByte()) + iv + sealed)
    }

    fun read(): ReadResult {
        if (!file.exists()) return ReadResult.Missing
        val bytes = file.readBytes()
        return try {
            ReadResult.Ok(decrypt(bytes))
        } catch (_: AEADBadTagException) {
            discard()
        } catch (_: KeyPermanentlyInvalidatedException) {
            discard()
        } catch (_: MalformedSealedFile) {
            discard()
        } catch (_: GeneralSecurityException) {
            ReadResult.Unavailable
        }
    }

    fun delete() {
        file.delete()
    }

    private fun discard(): ReadResult {
        file.delete()
        return ReadResult.Discarded
    }

    private fun decrypt(bytes: ByteArray): ByteArray {
        if (bytes.size < HEADER.size + 1 + IV_BYTES + TAG_BYTES || !bytes.copyOf(HEADER.size).contentEquals(HEADER)) {
            throw MalformedSealedFile()
        }
        val ivLength = bytes[HEADER.size].toInt()
        if (ivLength != IV_BYTES) throw MalformedSealedFile()
        val cipherStart = HEADER.size + 1 + ivLength
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, bytes, HEADER.size + 1, ivLength))
        cipher.updateAAD(aad())
        return cipher.doFinal(bytes, cipherStart, bytes.size - cipherStart)
    }

    private fun aad(): ByteArray = HEADER + purpose.toByteArray(Charsets.UTF_8)

    private class MalformedSealedFile : Exception()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        val HEADER = byteArrayOf(0x4C, 0x4E, 0x53, 0x31, 0x01)   // "LNS1" + format version 1
    }
}
