package lian.core.engine.link

import org.junit.jupiter.api.Test
import lian.core.engine.TestIntel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Algorithm tests on a fixture list; the real list is checked against the official test_psl.txt in :core:intel. */
class PublicSuffixListTest {
    private val psl = TestIntel.publicSuffixList
    private fun registrable(domain: String) = psl.match(domain)?.registrable

    @Test
    fun `longest rule wins`() {
        assertEquals("example.co.uk", registrable("www.example.co.uk"))
        assertEquals("example.com", registrable("a.b.example.com"))
        assertNull(registrable("co.uk"))
    }

    @Test
    fun `wildcard and exception rules`() {
        assertNull(registrable("abc.ck"))              // *.ck: abc.ck is itself a suffix
        assertEquals("site.abc.ck", registrable("www.site.abc.ck"))
        assertEquals("www.ck", registrable("www.www.ck"))   // !www.ck
    }

    @Test
    fun `unlisted TLDs fall back to the implicit star rule`() {
        assertEquals("example.example", registrable("a.example.example"))
        assertNull(registrable("example"))
    }

    @Test
    fun `leading dots and empty labels are rejected`() {
        assertNull(psl.match(".example.com"))
        assertNull(psl.match("a..com"))
    }

    @Test
    fun `ACE labels match Unicode rules and keep their form`() {
        assertEquals("xn--80aswg.xn--p1ai", registrable("www.xn--80aswg.xn--p1ai"))   // сайт.рф
        assertEquals("сайт.рф", registrable("www.сайт.рф"))
    }

    @Test
    fun `private section marks shared hosting`() {
        val m = psl.match("paypal-login.github.io")!!
        assertEquals("paypal-login.github.io", m.registrable)
        assertTrue(m.sharedHosting)
        assertFalse(psl.match("example.com")!!.sharedHosting)
    }
}
