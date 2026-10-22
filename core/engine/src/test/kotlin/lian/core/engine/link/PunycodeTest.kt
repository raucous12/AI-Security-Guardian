package lian.core.engine.link

import org.junit.jupiter.api.Test
import lian.core.engine.forAll
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PunycodeTest {
    private fun cps(vararg codePoints: Int) = buildString { codePoints.forEach { appendCodePoint(it) } }

    /** RFC 3492 §7.1 sample strings. */
    private val samples = listOf(
        "egbpdaj6bu4bxfgehfvwxn" to cps(0x0644, 0x064A, 0x0647, 0x0645, 0x0627, 0x0628, 0x062A, 0x0643, 0x0644, 0x0645, 0x0648, 0x0634, 0x0639, 0x0631, 0x0628, 0x064A, 0x061F),
        "ihqwcrb4cv8a8dqg056pqjye" to cps(0x4ED6, 0x4EEC, 0x4E3A, 0x4EC0, 0x4E48, 0x4E0D, 0x8BF4, 0x4E2D, 0x6587),
        "Proprostnemluvesky-uyb24dma41a" to cps(0x50, 0x72, 0x6F, 0x10D, 0x70, 0x72, 0x6F, 0x73, 0x74, 0x11B, 0x6E, 0x65, 0x6D, 0x6C, 0x75, 0x76, 0xED, 0x10D, 0x65, 0x73, 0x6B, 0x79),
        "b1abfaaepdrnnbgefbaDotcwatmq2g4l" to cps(0x043F, 0x043E, 0x0447, 0x0435, 0x043C, 0x0443, 0x0436, 0x0435, 0x043E, 0x043D, 0x0438, 0x043D, 0x0435, 0x0433, 0x043E, 0x0432, 0x043E, 0x0440, 0x044F, 0x0442, 0x043F, 0x043E, 0x0440, 0x0443, 0x0441, 0x0441, 0x043A, 0x0438),
        "3B-ww4c5e180e575a65lsy2b" to cps(0x33, 0x5E74, 0x42, 0x7D44, 0x91D1, 0x516B, 0x5148, 0x751F),
        "-with-SUPER-MONKEYS-pc58ag80a8qai00g7n9n" to cps(0x5B89, 0x5BA4, 0x5948, 0x7F8E, 0x6075) + "-with-SUPER-MONKEYS",
        "Hello-Another-Way--fc4qua05auwb3674vfr0b" to "Hello-Another-Way-" + cps(0x305D, 0x308C, 0x305E, 0x308C, 0x306E, 0x5834, 0x6240),
        "2-u9tlzr9756bt3uc0v" to cps(0x3072, 0x3068, 0x3064, 0x5C4B, 0x6839, 0x306E, 0x4E0B, 0x32),
        "MajiKoi5-783gue6qz075azm5e" to cps(0x4D, 0x61, 0x6A, 0x69, 0x3067, 0x4B, 0x6F, 0x69, 0x3059, 0x308B, 0x35, 0x79D2, 0x524D),
        "de-jg4avhby1noc0d" to cps(0x30D1, 0x30D5, 0x30A3, 0x30FC, 0x64, 0x65, 0x30EB, 0x30F3, 0x30D0),
        "d9juau41awczczp" to cps(0x305D, 0x306E, 0x30B9, 0x30D4, 0x30FC, 0x30C9, 0x3067),
        "-> \$1.00 <--" to "-> \$1.00 <-",
        "bcher-kva" to "bücher",
        "mnchen-3ya" to "münchen",
    )

    @Test
    fun `decodes the RFC 3492 samples`() = samples.forEach { (encoded, decoded) -> assertEquals(decoded, Punycode.decode(encoded), encoded) }

    @Test
    fun `encodes the RFC 3492 samples (mixed-case annotation aside)`() =
        samples.forEach { (encoded, decoded) -> assertEquals(encoded.lowercase(), Punycode.encode(decoded)?.lowercase(), encoded) }

    @Test
    fun `rejects malformed input`() {
        assertNull(Punycode.decode("-abc!"))
        assertNull(Punycode.decode("99999999999999999"))
        assertNull(Punycode.decode("ü-abc"))
    }

    @Test
    fun `label helpers only touch ACE labels`() {
        assertEquals("bücher", Punycode.toUnicodeLabel("XN--bcher-kva"))
        assertEquals("example", Punycode.toUnicodeLabel("example"))
        assertEquals("xn--bcher-kva", Punycode.toAsciiLabel("bücher"))
        assertEquals("example", Punycode.toAsciiLabel("example"))
    }

    @Test
    fun `encode then decode round-trips`() = forAll { random ->
        val ranges = listOf(0x61..0x7A, 0x0400..0x04FF, 0x4E00..0x9FFF, 0x0600..0x06FF, 0x1F300..0x1F5FF)
        val text = buildString {
            repeat(1 + random.nextInt(12)) {
                val range = ranges[random.nextInt(ranges.size)]
                appendCodePoint(range.first + random.nextInt(range.last - range.first + 1))
            }
        }
        assertEquals(text, Punycode.decode(Punycode.encode(text)!!))
    }
}
