package lian.core.engine.apk

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Random-access bytes: a file descriptor in the isolated process, a byte array in tests. */
interface ByteSource {
    val size: Long

    /** Reads exactly [length] bytes at [position] or throws [ApkFormatException]. */
    fun read(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (position < 0 || position + length > bytes.size) throw ApkFormatException("read past end of file")
        System.arraycopy(bytes, position.toInt(), buffer, offset, length)
    }
}

class ApkFormatException(message: String) : Exception(message)

internal fun ByteSource.bytes(position: Long, length: Int): ByteArray = ByteArray(length).also { read(position, it) }

internal fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
internal fun le32(b: ByteArray, i: Int): Long = le16(b, i).toLong() or (le16(b, i + 2).toLong() shl 16)
internal fun le64(b: ByteArray, i: Int): Long = le32(b, i) or (le32(b, i + 4) shl 32)

/**
 * A ZIP central-directory reader with hard limits, for files that may be hostile: no ZIP64, at most
 * [MAX_ENTRIES] entries, bounded central directory, and every inflate capped by the caller.
 */
class ZipArchive(private val source: ByteSource) {
    class Entry(val name: String, val method: Int, val compressedSize: Long, val uncompressedSize: Long, val localHeaderOffset: Long, val encrypted: Boolean)

    val entries: List<Entry>
    val centralDirectoryOffset: Long
    val centralDirectorySize: Long
    val eocdOffset: Long

    init {
        if (source.size < 22) throw ApkFormatException("too small to be an APK")
        val tailSize = minOf(source.size, 22L + 65_535L).toInt()
        val tail = source.bytes(source.size - tailSize, tailSize)
        val eocd = (tailSize - 22 downTo 0).firstOrNull { le32(tail, it) == EOCD_SIG && it + 22 + le16(tail, it + 20) == tailSize }
            ?: (tailSize - 22 downTo 0).firstOrNull { le32(tail, it) == EOCD_SIG }
            ?: throw ApkFormatException("not a ZIP archive")
        eocdOffset = source.size - tailSize + eocd
        val count = le16(tail, eocd + 10)
        centralDirectorySize = le32(tail, eocd + 12)
        centralDirectoryOffset = le32(tail, eocd + 16)
        if (count == 0xFFFF || centralDirectoryOffset == 0xFFFFFFFFL) throw ApkFormatException("ZIP64 archives are not supported")
        if (count > MAX_ENTRIES) throw ApkFormatException("too many entries ($count)")
        if (centralDirectorySize > MAX_CD_BYTES || centralDirectoryOffset + centralDirectorySize > eocdOffset) throw ApkFormatException("bad central directory")

        val cd = source.bytes(centralDirectoryOffset, centralDirectorySize.toInt())
        var p = 0
        entries = List(count) {
            if (p + 46 > cd.size || le32(cd, p) != CEN_SIG) throw ApkFormatException("corrupt central directory")
            val flags = le16(cd, p + 8)
            val method = le16(cd, p + 10)
            val compressed = le32(cd, p + 20)
            val uncompressed = le32(cd, p + 24)
            val nameLength = le16(cd, p + 28)
            val extraLength = le16(cd, p + 30)
            val commentLength = le16(cd, p + 32)
            val local = le32(cd, p + 42)
            if (p + 46 + nameLength > cd.size) throw ApkFormatException("corrupt central directory")
            val name = String(cd, p + 46, nameLength, Charsets.UTF_8)
            p += 46 + nameLength + extraLength + commentLength
            Entry(name, method, compressed, uncompressed, local, flags and 1 != 0)
        }
    }

    fun find(name: String): Entry? = entries.firstOrNull { it.name == name }

    /** The entry's uncompressed bytes, refusing anything larger than [maxBytes] (zip-bomb guard). */
    fun read(entry: Entry, maxBytes: Int): ByteArray {
        if (entry.encrypted) throw ApkFormatException("${entry.name} is encrypted")
        if (entry.uncompressedSize > maxBytes || entry.compressedSize > maxBytes) throw ApkFormatException("${entry.name} is too large")
        val local = source.bytes(entry.localHeaderOffset, 30)
        if (le32(local, 0) != LOC_SIG) throw ApkFormatException("corrupt local header for ${entry.name}")
        val dataStart = entry.localHeaderOffset + 30 + le16(local, 26) + le16(local, 28)
        if (dataStart + entry.compressedSize > centralDirectoryOffset) throw ApkFormatException("${entry.name} overlaps the central directory")
        val raw = source.bytes(dataStart, entry.compressedSize.toInt())
        return when (entry.method) {
            STORED -> raw
            DEFLATED -> inflate(raw, entry.uncompressedSize.toInt(), maxBytes)
            else -> throw ApkFormatException("unsupported compression ${entry.method}")
        }
    }

    private fun inflate(raw: ByteArray, expected: Int, maxBytes: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected && !inflater.finished()) {
                val r = inflater.inflate(out, n, expected - n)
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += r
            }
            if (n != expected) throw ApkFormatException("inflated size doesn't match the directory")
            if (!inflater.finished() && inflater.inflate(ByteArray(1)) > 0) throw ApkFormatException("entry larger than declared (max $maxBytes)")
            return out
        } catch (_: DataFormatException) {
            throw ApkFormatException("corrupt compressed data")
        } finally {
            inflater.end()
        }
    }

    companion object {
        const val MAX_ENTRIES = 30_000
        private const val MAX_CD_BYTES = 16L * 1024 * 1024
        const val STORED = 0
        const val DEFLATED = 8
        const val EOCD_SIG = 0x06054b50L
        private const val CEN_SIG = 0x02014b50L
        private const val LOC_SIG = 0x04034b50L
    }
}
