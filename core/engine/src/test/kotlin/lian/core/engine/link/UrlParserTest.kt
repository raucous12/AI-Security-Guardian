package lian.core.engine.link

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlParserTest {
    private fun parse(s: String) = UrlParser.parse(s)!!

    @Test
    fun `splits a full URL`() {
        val u = parse("https://user:pw@Example.COM:8443/a/b?x=1&y=2#frag")
        assertEquals("https", u.scheme)
        assertEquals("user:pw", u.userinfo)
        assertEquals("example.com", u.host)
        assertEquals(8443, u.port)
        assertEquals("/a/b", u.path)
        assertEquals("x=1&y=2", u.query)
        assertEquals("frag", u.fragment)
        assertFalse(u.schemeMissing)
    }

    @Test
    fun `the last at-sign ends the userinfo`() {
        assertEquals("paypal.com@x", parse("https://paypal.com@x@evil.top/").userinfo)
        assertEquals("evil.top", parse("https://paypal.com@x@evil.top/").host)
    }

    @Test
    fun `browser-tolerated tricks are normalised and flagged`() {
        parse("http:\\\\evil.com\\path").let { assertTrue(it.hasBackslash); assertEquals("evil.com", it.host); assertEquals("/path", it.path) }
        assertTrue(parse("http:/evil.com").slashAnomaly)
        parse("https://ex%61mple.com").let { assertTrue(it.hostPercentEncoded); assertEquals("example.com", it.host) }
        parse("https://example。com").let { assertTrue(it.unicodeDots); assertEquals("example.com", it.host) }
        parse("https://ｐａｙｐａｌ.com").let { assertTrue(it.compatibilityForms); assertEquals("paypal.com", it.host) }
        parse("https://１２７.０.０.１/").let { assertTrue(it.compatibilityForms); assertEquals("127.0.0.1", it.host) }
        parse("https://Example.COM./").let { assertTrue(it.trailingDot); assertEquals("example.com", it.host) }
        assertEquals("evil.com", parse("ht\ttps://evil\n.com").host)
    }

    @Test
    fun `missing scheme, ports and IPv6`() {
        parse("example.com:8080/x").let { assertTrue(it.schemeMissing); assertEquals(8080, it.port); assertEquals("example.com", it.host) }
        parse("localhost:3000").let { assertTrue(it.schemeMissing); assertEquals("localhost", it.host); assertEquals(3000, it.port) }
        parse("https://[::1]:443/").let { assertEquals("[::1]", it.host); assertEquals(443, it.port) }
        assertTrue(parse("https://example.com:99999").portInvalid)
        assertNull(parse("https://example.com:").port)
    }

    @Test
    fun `non-hierarchical schemes keep their opaque part`() {
        parse("javascript:alert(1)").let { assertEquals("javascript", it.scheme); assertEquals("alert(1)", it.opaque); assertFalse(it.hierarchical) }
        assertEquals("+44 20 7946 0000", parse("tel:+44 20 7946 0000").opaque)
        assertEquals("wifi", parse("WIFI:T:WPA;S:x;;").scheme)
    }

    @Test
    fun `query parameters are percent-decoded`() {
        assertEquals(listOf("url" to "https://paypa1.com/login", "a" to "b c"), UrlParser.queryParameters("url=https%3A%2F%2Fpaypa1.com%2Flogin&a=b+c"))
        assertEquals("%E0%A4", UrlParser.percentDecode("%E0%A4"))   // invalid UTF-8 stays as written
    }
}
