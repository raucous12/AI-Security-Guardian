package lian.core.engine.link

import org.junit.jupiter.api.Test
import lian.core.engine.EvalContext
import lian.core.engine.TestIntel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkAnalyzerTest {
    private val analyzer = LinkAnalyzer(TestIntel)
    private val ctx = EvalContext(nowEpochMs = 1_790_000_000_000, regionHint = "GB")

    private fun check(text: String, context: EvalContext = ctx) = analyzer.analyze(text, context)
    private fun rules(text: String) = check(text).reports.flatMap { r -> r.findings.map { it.rule.value } }.toSet()

    @Test
    fun `official domain shows no warning signs`() {
        val result = check("https://www.paypal.com/signin")
        assertEquals(LinkBand.NO_WARNING_SIGNS, result.band)
        assertEquals(setOf("link.official"), rules("https://www.paypal.com/signin"))
        assertEquals(LinkBand.NO_WARNING_SIGNS, check("https://www.amazon.de/").band)   // owns its label across ccTLDs
    }

    @Test
    fun `digit-for-letter homograph is dangerous`() {
        val result = check("http://paypa1.com/login")
        assertEquals(LinkBand.DANGEROUS, result.band)
        assertTrue("link.brand.homograph" in rules("http://paypa1.com/login"))
        assertTrue("link.lure" in rules("http://paypa1.com/login"))   // corroborated by the homograph
    }

    @Test
    fun `m-for-rn and typosquats`() {
        assertTrue("link.brand.homograph" in rules("https://arnazon.com/"))
        assertTrue("link.brand.typo" in rules("https://paypall.com/"))
        assertEquals(LinkBand.NO_WARNING_SIGNS, check("https://paypay.com/").band)   // legitimate near-match
    }

    @Test
    fun `mixed-script and whole-script Cyrillic look-alikes`() {
        val mixed = "https://" + Punycode.toAsciiLabel("pаypal") + ".com/"
        assertTrue(setOf("link.brand.homograph", "link.idn.mixed_script").all { it in rules(mixed) })
        assertEquals(LinkBand.DANGEROUS, check(mixed).band)

        assertTrue("link.idn.whole_script" in rules("https://аррӏе.com/"))
        assertTrue("link.idn.whole_script" !in rules("https://аррӏе.ru/"))   // expected script for that TLD
    }

    @Test
    fun `credentials trick, brand in subdomain, combosquat`() {
        assertEquals(LinkBand.LIKELY_SCAM, check("https://paypal.com@evil.top/").band)
        assertTrue("link.userinfo" in rules("https://paypal.com@evil.top/"))
        assertTrue("link.brand.subdomain" in rules("https://paypal.com.account-verify.top/"))
        assertTrue("link.brand.combo" in rules("https://secure-paypal-login.top/"))
        assertTrue("link.hosting.shared" in rules("https://paypal-login.github.io/"))
    }

    @Test
    fun `obfuscated IPs, shorteners, scripts`() {
        assertEquals(setOf("link.ip.obfuscated", "link.cleartext"), rules("http://0x7f.1/"))
        assertEquals(LinkBand.CAUTION, check("https://bit.ly/3xYz").band)
        assertEquals(LinkBand.DANGEROUS, check("javascript:alert(document.cookie)").band)
        assertEquals(LinkBand.DANGEROUS, check("data:text/html;base64,PHNjcmlwdD4=").band)
    }

    @Test
    fun `QR payloads and phone numbers`() {
        assertEquals(setOf("qr.wifi.open"), rules("WIFI:T:nopass;S:FreeAirport;;"))
        assertTrue("link.phone.premium" in rules("tel:09001234567"))
        assertTrue(check("tel:09001234567", ctx.copy(regionHint = null)).reports.single().findings.isEmpty())
    }

    @Test
    fun `hidden characters are flagged and made visible`() {
        val invisible = check("https://pay‍pal.com/")
        assertTrue(invisible.reports.single().findings.any { it.rule.value == "link.idn.invisible" })
        assertTrue(invisible.reports.single().summary!!.displayHost!!.contains("⟦U+200D⟧"))
        assertTrue("link.bidi" in rules("https://example.com/‮gpj.exe"))
    }

    @Test
    fun `redirects are followed offline and inherit the worst score`() {
        val result = check("https://example.com/r?url=https%3A%2F%2Fpaypa1.com%2Flogin")
        val report = result.reports.single()
        assertTrue(report.findings.any { it.rule.value == "link.redirect.nested" })
        assertEquals(LinkBand.DANGEROUS, report.nested.single().band)
        assertEquals(LinkBand.DANGEROUS, result.band)
    }

    @Test
    fun `free text, bare domains and emails`() {
        val result = check("Hi! Log in at https://www.paypal.com or paypa1.com, or email help@paypa1.com")
        assertEquals(3, result.reports.size)
        assertEquals(LinkBand.DANGEROUS, result.band)
        assertTrue(check("see file.txt and notes.docx").reports.isEmpty())   // not real TLDs
        assertTrue(check("no links here").reports.isEmpty())
    }

    @Test
    fun `identical input gives identical output`() {
        val text = "http://paypa1.com/login https://paypal.com@evil.top/ WIFI"
        assertEquals(check(text), check(text))
        assertEquals(LinkRules.VERSION, check(text).ruleSetVersion)
    }
}
