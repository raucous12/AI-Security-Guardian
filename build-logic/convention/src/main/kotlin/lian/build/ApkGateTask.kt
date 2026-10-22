package lian.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.RandomAccessFile

/**
 * Gate D, run on the packaged APK:
 *  - no third-party machine-learning model formats (TFLite, ONNX, PyTorch, GGUF, …): no opaque models or
 *    ML runtimes can slip in. LIAN's own linear models (.vgm, documented format, integrity-checked at
 *    runtime) are allowed only at the exact paths listed in [allowedModelFiles];
 *  - every native library is stored uncompressed at a 16 KB-aligned offset, and on 64-bit ABIs every
 *    loadable ELF segment is aligned to at least 16 KB (Google Play's 16 KB page-size requirement).
 */
abstract class ApkGateTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Input abstract val allowedModelFiles: SetProperty<String>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val apks = apkDirectory.get().asFile.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        if (apks.isEmpty()) throw GradleException("PRIVACY GATE D: no APK found in ${apkDirectory.get().asFile}")

        val problems = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (apk in apks) {
            lines += "apk: ${apk.name}"
            RandomAccessFile(apk, "r").use { zip ->
                for (entry in readCentralDirectory(zip)) {
                    val lower = entry.name.lowercase()
                    if (MODEL_EXTENSIONS.any { lower.endsWith(it) }) {
                        problems += "${apk.name}: third-party model file ${entry.name} (no opaque models or ML runtimes)"
                    }
                    if (lower.endsWith(".vgm")) {
                        if (entry.name in allowedModelFiles.get()) lines += "  model: ${entry.name} (allow-listed)"
                        else problems += "${apk.name}: unlisted model file ${entry.name}"
                    }
                    if (lower.startsWith("lib/") && lower.endsWith(".so")) {
                        lines += "  native: ${entry.name} ${checkNativeLibrary(zip, entry, problems, apk.name)}"
                    }
                }
            }
        }

        report.get().asFile.writeText(
            (lines + (if (problems.isEmpty()) "RESULT: PASS" else "RESULT: FAIL") + problems.map { "  - $it" })
                .joinToString("\n", postfix = "\n"),
        )
        if (problems.isNotEmpty()) {
            throw GradleException("PRIVACY GATE D FAILED (APK contents)\n" + problems.joinToString("\n") { "  - $it" })
        }
    }

    private fun checkNativeLibrary(zip: RandomAccessFile, entry: ZipEntryInfo, problems: MutableList<String>, apk: String): String {
        if (entry.method != STORED) {
            problems += "$apk: ${entry.name} is compressed; native libraries must be stored uncompressed (useLegacyPackaging = false)"
            return "compressed"
        }
        if (entry.dataOffset % PAGE_16K != 0L) {
            problems += "$apk: ${entry.name} starts at offset ${entry.dataOffset}, not 16 KB-aligned"
        }
        val is64BitAbi = entry.name.startsWith("lib/arm64-v8a/") || entry.name.startsWith("lib/x86_64/")
        val minAlign = elfMinimumLoadAlignment(zip, entry)
        if (is64BitAbi && minAlign != null && minAlign < PAGE_16K) {
            problems += "$apk: ${entry.name} has a LOAD segment aligned to $minAlign bytes; 16384 required"
        }
        return "offset=${entry.dataOffset} minLoadAlign=${minAlign ?: "n/a"}"
    }

    /** Smallest p_align among PT_LOAD program headers, or null if the entry isn't a little-endian ELF. */
    private fun elfMinimumLoadAlignment(zip: RandomAccessFile, entry: ZipEntryInfo): Long? {
        val header = ByteArray(64)
        zip.seek(entry.dataOffset)
        zip.readFully(header)
        if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte() || header[5] != 1.toByte()
        ) return null
        val is64 = header[4] == 2.toByte()
        val phOff = if (is64) le64(header, 0x20) else le32(header, 0x1C)
        val phEntSize = le16(header, if (is64) 0x36 else 0x2A)
        val phNum = le16(header, if (is64) 0x38 else 0x2C)
        if (phNum == 0 || phEntSize == 0 || phOff <= 0 || phOff + phNum.toLong() * phEntSize > entry.compressedSize) return null
        val table = ByteArray(phNum * phEntSize)
        zip.seek(entry.dataOffset + phOff)
        zip.readFully(table)
        return (0 until phNum).mapNotNull { i ->
            val base = i * phEntSize
            if (le32(table, base) != PT_LOAD) null else if (is64) le64(table, base + 0x30) else le32(table, base + 0x1C)
        }.minOrNull()
    }

    private class ZipEntryInfo(val name: String, val method: Int, val compressedSize: Long, val dataOffset: Long)

    private fun readCentralDirectory(zip: RandomAccessFile): List<ZipEntryInfo> {
        val length = zip.length()
        val tailSize = minOf(length, 22L + 65_535L).toInt()
        val tail = ByteArray(tailSize)
        zip.seek(length - tailSize)
        zip.readFully(tail)
        val eocd = (tailSize - 22 downTo 0).firstOrNull { le32(tail, it) == EOCD_SIG }
            ?: throw GradleException("PRIVACY GATE D: not a ZIP archive")
        val count = le16(tail, eocd + 10)
        val cdSize = le32(tail, eocd + 12)
        val cdOffset = le32(tail, eocd + 16)
        if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL) throw GradleException("PRIVACY GATE D: ZIP64 archives are not supported")

        val cd = ByteArray(cdSize.toInt())
        zip.seek(cdOffset)
        zip.readFully(cd)
        val local = ByteArray(30)
        var p = 0
        return List(count) {
            if (le32(cd, p) != CEN_SIG) throw GradleException("PRIVACY GATE D: corrupt central directory")
            val method = le16(cd, p + 10)
            val compressedSize = le32(cd, p + 20)
            val nameLength = le16(cd, p + 28)
            val extraLength = le16(cd, p + 30)
            val commentLength = le16(cd, p + 32)
            val localOffset = le32(cd, p + 42)
            val name = String(cd, p + 46, nameLength, Charsets.UTF_8)
            p += 46 + nameLength + extraLength + commentLength
            zip.seek(localOffset)
            zip.readFully(local)
            if (le32(local, 0) != LOC_SIG) throw GradleException("PRIVACY GATE D: corrupt local header for $name")
            ZipEntryInfo(name, method, compressedSize, localOffset + 30 + le16(local, 26) + le16(local, 28))
        }
    }

    private companion object {
        const val PAGE_16K = 16_384L
        const val STORED = 0
        const val PT_LOAD = 1L
        const val EOCD_SIG = 0x06054b50L
        const val CEN_SIG = 0x02014b50L
        const val LOC_SIG = 0x04034b50L
        val MODEL_EXTENSIONS = listOf(
            ".tflite", ".lite", ".onnx", ".ort", ".pt", ".ptl", ".gguf", ".ggml", ".task", ".safetensors", ".mlmodel", ".pte",
        )

        fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, i: Int): Long = (le16(b, i).toLong()) or (le16(b, i + 2).toLong() shl 16)
        fun le64(b: ByteArray, i: Int): Long = le32(b, i) or (le32(b, i + 4) shl 32)
    }
}
