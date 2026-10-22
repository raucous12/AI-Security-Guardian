package lian.core.intel

import org.junit.jupiter.api.Test
import lian.core.engine.EvalContext
import lian.core.engine.link.LinkAnalyzer
import lian.core.engine.link.LinkBand
import java.lang.Character.UnicodeScript
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntelPackTest {
    private val intel = IntelPack.load()
    private val psl = intel.publicSuffixList

    @Test
    fun `loads every pack with plausible sizes`() {
        assertTrue(intel.version.startsWith("intel-"))
        assertTrue(psl.size > 9_000, "PSL rules: ${psl.size}")
        assertTrue(intel.confusableCount > 6_000, "confusables: ${intel.confusableCount}")
        assertTrue(intel.brands.all.size >= 60)
        assertEquals("a", intel.confusables.prototype(0x0430))   // CYRILLIC SMALL LETTER A
    }

    /** The PSL project's own test vectors (tests/test_psl.txt at the pinned commit). */
    @Test
    fun `passes the official Public Suffix List tests`() {
        val text = javaClass.getResourceAsStream("/lian/intel/test_psl.txt")!!.readBytes().toString(Charsets.UTF_8)
        val call = Regex("""^checkPublicSuffix\((null|'[^']*'),\s*(null|'[^']*')\);""")
        val cases = text.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") }   // the file keeps some disabled cases as comments
            .mapNotNull { call.find(it) }
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { it.first != "null" }
            .toList()
        assertTrue(cases.size > 60, "parsed ${cases.size} cases")
        val failures = cases.mapNotNull { (input, expected) ->
            val domain = input.trim('\'').lowercase()
            val want = expected.takeUnless { it == "null" }?.trim('\'')
            val got = psl.match(domain)?.registrable
            if (got == want) null else "$domain: expected $want, got $got"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `curated data is consistent with the Public Suffix List`() {
        val problems = buildList {
            for (brand in intel.brands.all) {
                brand.officialDomains.forEach { d -> if (psl.match(d)?.registrable != d) add("${brand.label}: $d is not a registrable domain") }
                if (brand.officialDomains.none { it.substringBefore('.') == brand.label }) add("${brand.label}: no official domain uses the label")
            }
            if (intel.brands.all.map { it.label }.toSet().size != intel.brands.all.size) add("duplicate brand labels")
            listOf("bit.ly", "tinyurl.com", "qrco.de").forEach { if (!intel.isShortener(it)) add("shortener $it missing") }
            listOf(UnicodeScript.CYRILLIC, UnicodeScript.GREEK).flatMap { intel.tldsForScript(it) }
                .forEach { if (!psl.isKnownTld(it)) add("script TLD $it unknown to the PSL") }
            listOf("top", "xyz", "zip", "mov").forEach { if (!psl.isKnownTld(it) || intel.tldRisk(it) == null) add("TLD $it") }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `real-world homographs are caught with the real Unicode table`() {
        val analyzer = LinkAnalyzer(intel)
        val ctx = EvalContext(nowEpochMs = 0)
        fun rules(text: String) = analyzer.analyze(text, ctx).reports.flatMap { r -> r.findings.map { it.rule.value } }.toSet()

        // xn--80ak6aa92e.com renders as "аррӏе.com" in Cyrillic (the 2017 Xudong Zheng demonstration).
        assertTrue("link.brand.homograph" in rules("https://xn--80ak6aa92e.com/"), rules("https://xn--80ak6aa92e.com/").toString())
        assertTrue("link.idn.whole_script" in rules("https://xn--80ak6aa92e.com/"))
        assertTrue("link.brand.homograph" in rules("https://rnicrosoft.com/login"))
        assertTrue("link.brand.homograph" in rules("http://paypa1.com"))
        assertTrue(setOf("link.brand.combo", "link.hosting.shared").all { it in rules("https://paypal-verify.web.app/") })
        assertTrue("link.brand.combo" in rules("https://hmrc-tax-refund.com/claim"))
        assertEquals(LinkBand.NO_WARNING_SIGNS, analyzer.analyze("https://www.paypal.com/uk/signin", ctx).band)
        assertEquals(LinkBand.NO_WARNING_SIGNS, analyzer.analyze("https://www.amazon.co.uk/", ctx).band)
        assertEquals(LinkBand.NO_WARNING_SIGNS, analyzer.analyze("https://github.com/publicsuffix/list", ctx).band)
    }

    @Test
    fun `tampered data is refused`() {
        val genuine = { name: String -> javaClass.getResourceAsStream("/lian/intel/$name")!!.readBytes() }
        assertFailsWith<IntelIntegrityException> {
            IntelPack.load { name -> if (name == "brands.tsv") genuine(name) + "evil\tevil.com\t\t\t\tx\n".toByteArray() else genuine(name) }
        }
    }
}
