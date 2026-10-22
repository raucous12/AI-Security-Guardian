package lian.core.engine.link

import org.junit.jupiter.api.Test
import lian.core.engine.link.LinkCandidate.Kind
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkScannerTest {
    private fun texts(input: String) = LinkScanner.extract(input).map { it.text }

    @Test
    fun `finds links and strips surrounding punctuation`() {
        assertEquals(listOf("https://example.com/path"), texts("Visit https://example.com/path)."))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), texts("(see https://en.wikipedia.org/wiki/Foo_(bar))"))
        assertEquals(listOf("www.example.com"), texts("go to \"www.example.com\"!"))
    }

    @Test
    fun `classifies bare domains, emails and payloads`() {
        assertEquals(listOf(LinkCandidate("paypa1.com", Kind.BARE_DOMAIN)), LinkScanner.extract("pay at paypa1.com today"))
        assertEquals(listOf(LinkCandidate("mailto:help@paypa1.com", Kind.EMAIL)), LinkScanner.extract("write to help@paypa1.com"))
        assertEquals(Kind.PAYLOAD, LinkScanner.extract("WIFI:S:My Net;T:WPA;P:x;;").single().kind)
        assertEquals(listOf("tel:+449001234567"), texts("Call tel:+449001234567 now"))
    }

    @Test
    fun `ignores ordinary words and caps the number of candidates`() {
        assertEquals(emptyList(), texts("hello there, e.g. nothing here"))
        val many = (1..8).joinToString(" ") { "https://site$it.com" }
        assertEquals(LinkScanner.MAX_CANDIDATES, texts(many).size)
        assertEquals(1, texts("https://a.com https://a.com").size)
    }

    @Test
    fun `adversarial input stays linear`() {
        val hostile = "a.".repeat(LinkScanner.MAX_INPUT / 2) + " " + ")".repeat(5_000) + " " + "(".repeat(5_000)
        val start = System.nanoTime()
        repeat(20) { LinkScanner.extract(hostile) }
        val perRunMs = (System.nanoTime() - start) / 20 / 1_000_000
        assertTrue(perRunMs < 50, "took $perRunMs ms per run")
    }
}
