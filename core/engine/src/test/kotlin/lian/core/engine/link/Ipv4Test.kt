package lian.core.engine.link

import org.junit.jupiter.api.Test
import lian.core.engine.forAll
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Ipv4Test {
    private fun dotted(host: String) = (Ipv4.parse(host) as? Ipv4Result.Address)?.dotted

    /** Cases from the WHATWG URL Standard's IPv4 parser and web-platform-tests host parsing. */
    @Test
    fun `WHATWG IPv4 forms`() {
        assertEquals("127.0.0.1", dotted("0x7f.1"))
        assertEquals("127.0.0.1", dotted("127.1"))
        assertEquals("127.0.0.1", dotted("127.0.1"))
        assertEquals("127.0.0.1", dotted("0x7f000001"))
        assertEquals("192.168.1.1", dotted("3232235777"))
        assertEquals("192.168.0.1", dotted("0300.0250.0.01"))
        assertEquals("255.255.255.255", dotted("0xffffffff"))
        assertEquals("0.0.0.0", dotted("0x"))
        assertEquals("1.2.3.4", dotted("1.2.3.4."))
    }

    @Test
    fun `numeric-looking but invalid hosts are Invalid, not domains`() {
        listOf("256.0.0.1", "4294967296", "0x100000000", "1.2.3.4.5", "1.2.3.09", "08", ".1.2.3", "example.123").forEach {
            assertIs<Ipv4Result.Invalid>(Ipv4.parse(it), it)
        }
    }

    @Test
    fun `hosts that don't end in a number are domains`() {
        listOf("example.com", "1.2.3.-4", "+1.2.3.4x", "0x7g", "１２７.０.０.１").forEach {
            assertIs<Ipv4Result.NotIpv4>(Ipv4.parse(it), it)
        }
    }

    @Test
    fun `signed parts are rejected (Phase 4 defect 2)`() = forAll { random ->
        val sign = if (random.nextBoolean()) "+" else "-"
        val n = random.nextInt(256)
        assertIs<Ipv4Result.Invalid>(Ipv4.parse("1.2.$sign$n.4"))
    }

    @Test
    fun `canonical dotted quads round-trip and are not obfuscated`() = forAll { random ->
        val parts = List(4) { random.nextInt(256) }
        val ip = assertIs<Ipv4Result.Address>(Ipv4.parse(parts.joinToString(".")))
        assertEquals(parts.joinToString("."), ip.dotted)
        assertFalse(ip.obfuscated)
    }

    @Test
    fun `hex and short forms are marked obfuscated`() {
        assertTrue((Ipv4.parse("0x7f.0.0.1") as Ipv4Result.Address).obfuscated)
        assertTrue((Ipv4.parse("2130706433") as Ipv4Result.Address).obfuscated)
        assertEquals("loopback", (Ipv4.parse("127.0.0.1") as Ipv4Result.Address).range)
        assertEquals("private network", (Ipv4.parse("192.168.1.10") as Ipv4Result.Address).range)
    }
}
