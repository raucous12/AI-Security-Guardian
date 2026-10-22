package lian.core.engine.apk

import org.junit.jupiter.api.Test
import lian.core.engine.EvalContext
import lian.core.engine.app.AppBand
import lian.core.engine.app.AppTestIntel
import lian.core.engine.app.Capability
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApkParserTest {
    @Test
    fun `reads package, sdk, permissions, launcher and declared services from binary XML`() {
        val apk = apk(manifest(packageName = "com.example.spy", hideLauncher = false, a11y = true))
        val facts = ApkParser.parse(ByteArraySource(apk))
        assertEquals("com.example.spy", facts.packageName)
        assertEquals(7L, facts.versionCode)
        assertEquals("1.2", facts.versionName)
        assertEquals(26, facts.minSdk)
        assertEquals(22, facts.targetSdk)
        assertEquals(listOf("android.permission.RECEIVE_SMS", "android.permission.SYSTEM_ALERT_WINDOW"), facts.permissions)
        assertTrue(facts.hasLauncher)
        assertEquals(setOf(Capability.ACCESSIBILITY), facts.declared)
        assertEquals("PayPal", facts.label)
        assertEquals(emptySet(), facts.signing.schemes)
    }

    @Test
    fun `facts survive the isolated-process encoding`() {
        val facts = ApkParser.parse(ByteArraySource(apk(manifest(label = "Tab\there\nnewline"))))
        assertEquals(facts, ApkFacts.decode(facts.encode()))
    }

    @Test
    fun `rules flag an unsigned impostor that asks for accessibility plus sms and hides its icon`() {
        val facts = ApkParser.parse(ByteArraySource(apk(manifest(packageName = "com.secure.update", hideLauncher = true, a11y = true))))
        val report = ApkAnalyzer(AppTestIntel).analyze(facts, InstalledContext(null, null), EvalContext(nowEpochMs = 0))
        val ids = report.findings.map { it.rule.value }
        assertTrue(listOf("apk.impostor.label", "apk.combo.trojan", "apk.stealth", "apk.sig.none", "apk.sdk.legacy").all { it in ids }, ids.toString())
        assertEquals(AppBand.CRITICAL, report.band)
    }

    @Test
    fun `an update signed by someone else is critical`() {
        val facts = ApkParser.parse(ByteArraySource(apk(manifest()))).copy(
            signing = SigningFacts(setOf(2), true, null, listOf(CertificateId("11".repeat(32), "22".repeat(20), "CN=Someone"))),
        )
        val report = ApkAnalyzer(AppTestIntel).analyze(facts, InstalledContext(listOf("33".repeat(32)), null), EvalContext(nowEpochMs = 0))
        assertTrue("apk.update.signer_mismatch" in report.findings.map { it.rule.value })
    }

    @Test
    fun `hostile archives are refused instead of exhausting memory`() {
        assertFailsWith<ApkFormatException> { ApkParser.parse(ByteArraySource(ByteArray(100))) }
        assertFailsWith<ApkFormatException> { ApkParser.parse(ByteArraySource(apk(null))) }   // no manifest
        val good = apk(manifest())
        assertFailsWith<ApkFormatException> { ApkParser.parse(ByteArraySource(good.copyOf(good.size / 2))) }
        // A 64 MB run of zeros compresses to ~64 KB; declared as the manifest it must be refused, not inflated.
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(64 * 1024 * 1024)); z.closeEntry() }
        }.toByteArray()
        assertFailsWith<ApkFormatException> { ApkParser.parse(ByteArraySource(bomb)) }
    }

    @Test
    fun `random corruption never escapes as an unexpected exception`() {
        val good = apk(manifest())
        val random = java.util.Random(20261001)
        repeat(300) {
            val bad = good.copyOf()
            repeat(1 + random.nextInt(8)) { bad[random.nextInt(bad.size)] = random.nextInt(256).toByte() }
            try {
                ApkParser.parse(ByteArraySource(bad))
            } catch (_: ApkFormatException) {
                // expected for most mutations
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Minimal writers for test fixtures: a binary-XML (AXML) manifest and a ZIP with resources.arsc.

    private fun apk(manifest: ByteArray?): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            if (manifest != null) { z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry() }
            val arsc = arsc("PayPal")
            z.putNextEntry(ZipEntry("resources.arsc").apply { method = ZipEntry.STORED; size = arsc.size.toLong(); crc = CRC32().apply { update(arsc) }.value })
            z.write(arsc); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(64)); z.closeEntry()
        }
    }.toByteArray()

    private class Attr(val name: String, val resId: Long, val type: Int, val value: Any)
    private class El(val name: String, val attrs: List<Attr>, val children: List<El> = emptyList())

    private fun manifest(packageName: String = "com.example.app", hideLauncher: Boolean = false, a11y: Boolean = false, label: String? = null): ByteArray {
        val labelAttr = if (label != null) Attr("label", 0x01010001, 0x03, label) else Attr("label", 0x01010001, 0x01, 0x7f010000L)
        val activity = El(
            "activity", listOf(Attr("name", 0x01010003, 0x03, ".Main")),
            if (hideLauncher) emptyList() else listOf(
                El("intent-filter", emptyList(), listOf(
                    El("action", listOf(Attr("name", 0x01010003, 0x03, "android.intent.action.MAIN"))),
                    El("category", listOf(Attr("name", 0x01010003, 0x03, "android.intent.category.LAUNCHER"))),
                )),
            ),
        )
        val service = El("service", listOf(Attr("name", 0x01010003, 0x03, ".Svc"), Attr("permission", 0x01010006, 0x03, "android.permission.BIND_ACCESSIBILITY_SERVICE")))
        val root = El(
            "manifest",
            listOf(Attr("versionCode", 0x0101021b, 0x10, 7L), Attr("versionName", 0x0101021c, 0x03, "1.2"), Attr("package", 0, 0x03, packageName)),
            listOf(
                El("uses-sdk", listOf(Attr("minSdkVersion", 0x0101020c, 0x10, 26L), Attr("targetSdkVersion", 0x01010270, 0x10, 22L))),
                El("uses-permission", listOf(Attr("name", 0x01010003, 0x03, "android.permission.SYSTEM_ALERT_WINDOW"))),
                El("uses-permission", listOf(Attr("name", 0x01010003, 0x03, "android.permission.RECEIVE_SMS"))),
                El("application", listOf(labelAttr), listOf(activity) + if (a11y) listOf(service) else emptyList()),
            ),
        )
        return axml(root)
    }

    private fun axml(root: El): ByteArray {
        // String pool: attribute names first (so they line up with the resource map), then everything else.
        val strings = ArrayList<String>()
        val resIds = ArrayList<Long>()
        fun collectAttrs(e: El) { e.attrs.forEach { a -> if (a.resId != 0L && a.name !in strings) { strings += a.name; resIds += a.resId } }; e.children.forEach(::collectAttrs) }
        collectAttrs(root)
        fun idx(s: String): Int { val i = strings.indexOf(s); if (i >= 0) return i; strings += s; return strings.size - 1 }
        val body = ByteArrayOutputStream()
        fun walk(e: El) {
            val attrBytes = e.attrs.map { a ->
                val nameIdx = idx(a.name)
                val (raw, data) = if (a.type == 0x03) idx(a.value as String).let { it to it.toLong() } else -1 to (a.value as Long)
                le(intArrayOf(-1, nameIdx, raw)) + le16(8) + byteArrayOf(0, a.type.toByte()) + le(intArrayOf(data.toInt()))
            }
            val ext = le(intArrayOf(-1, idx(e.name))) + le16(20) + le16(20) + le16(e.attrs.size) + le16(0) + le16(0) + le16(0)
            val content = ext + attrBytes.fold(ByteArray(0)) { acc, b -> acc + b }
            body.write(chunk(0x0102, 16, le(intArrayOf(1, -1)) + content))
            e.children.forEach(::walk)
            body.write(chunk(0x0103, 16, le(intArrayOf(1, -1)) + le(intArrayOf(-1, idx(e.name)))))
        }
        walk(root)
        val pool = stringPool(strings)
        val resMap = chunk(0x0180, 8, resIds.fold(ByteArray(0)) { a, id -> a + le(intArrayOf(id.toInt())) })
        val inner = pool + resMap + body.toByteArray()
        return chunk(0x0003, 8, inner)
    }

    /** resources.arsc with one package (0x7f) and one string resource 0x7f010000 in the default config. */
    private fun arsc(value: String): ByteArray {
        val global = stringPool(listOf(value))
        val config = ByteArray(64).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(64) }
        val entry = le16(8) + le16(0) + le(intArrayOf(0)) + le16(8) + byteArrayOf(0, 0x03) + le(intArrayOf(0))
        val typeHeader = byteArrayOf(1, 0) + le16(0) + le(intArrayOf(1, 20 + 64 + 4)) + config
        val type = chunk(0x0201, 20 + 64, typeHeader + le(intArrayOf(0)) + entry)
        val pkgHeader = le(intArrayOf(0x7f)) + ByteArray(256) + le(intArrayOf(0, 0, 0, 0, 0))
        val pkg = chunk(0x0200, 8 + pkgHeader.size, pkgHeader + type)
        return chunk(0x0002, 12, le(intArrayOf(1)) + global + pkg)
    }

    private fun stringPool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val off = data.size()
            data.write(le16(s.length)); s.forEach { data.write(le16(it.code)) }; data.write(le16(0))
            off
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val header = le(intArrayOf(strings.size, 0, 0, stringsStart, 0))
        return chunk(0x0001, headerSize, header + le(offsets.toIntArray()) + data.toByteArray())
    }

    private fun chunk(type: Int, headerSize: Int, payload: ByteArray): ByteArray = le16(type) + le16(headerSize) + le(intArrayOf(8 + payload.size)) + payload
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le(values: IntArray): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
}
