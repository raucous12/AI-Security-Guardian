package lian.core.engine

import lian.core.engine.link.Brand
import lian.core.engine.link.BrandCatalog
import lian.core.engine.link.ConfusableMap
import lian.core.engine.link.IndexedBrandCatalog
import lian.core.engine.link.LinkIntel
import lian.core.engine.link.PremiumPrefix
import lian.core.engine.link.PublicSuffixList
import lian.core.engine.link.PublicSuffixList.Section
import lian.core.engine.link.TldRisk
import lian.core.engine.link.skeleton
import java.lang.Character.UnicodeScript

/** Small, hand-written intel so engine tests don't depend on the real data packs (those have their own tests). */
object TestIntel : LinkIntel {
    override val version = "test-fixture"

    override val publicSuffixList = PublicSuffixList(
        listOf("com", "net", "org", "uk", "co.uk", "top", "xyz", "ru", "рф", "de", "io", "ly", "me", "jp", "ck", "*.ck", "!www.ck")
            .map { it to Section.ICANN } + listOf("github.io" to Section.PRIVATE),
    )

    override val confusables = ConfusableMap { cp ->
        when (cp) {
            0x0430 -> "a"   // CYRILLIC SMALL LETTER A
            0x0440 -> "p"   // CYRILLIC SMALL LETTER ER
            0x0443 -> "y"   // CYRILLIC SMALL LETTER U
            0x0435 -> "e"   // CYRILLIC SMALL LETTER IE
            0x043E -> "o"   // CYRILLIC SMALL LETTER O
            0x04CF -> "l"   // CYRILLIC SMALL LETTER PALOCHKA
            0x0031 -> "l"   // DIGIT ONE
            0x0030 -> "O"   // DIGIT ZERO (the real table maps to a capital)
            0x006D -> "rn"  // LATIN SMALL LETTER M
            else -> null
        }
    }

    private fun brand(label: String, vararg domains: String, cc: Boolean = false, not: Set<String> = emptySet()) =
        Brand(label, skeleton(label, confusables), domains.toSet(), cc, emptySet(), not, "test")

    override val brands: BrandCatalog = IndexedBrandCatalog(
        listOf(
            brand("paypal", "paypal.com", "paypal.me", not = setOf("paypay")),
            brand("apple", "apple.com", "icloud.com"),
            brand("amazon", "amazon.com", cc = true),
            brand("microsoft", "microsoft.com", "live.com"),
            brand("google", "google.com", cc = true),
        ),
    )

    override fun isShortener(registrable: String) = registrable == "bit.ly"

    override fun tldRisk(tld: String) = when (tld) {
        "top" -> TldRisk("top", 15, "fixture")
        "xyz" -> TldRisk("xyz", 10, "fixture")
        else -> null
    }

    override fun premiumPrefix(e164: String) = listOf(
        PremiumPrefix("+449", "GB", "premium rate"),
        PremiumPrefix("+1900", "US", "premium rate"),
    ).firstOrNull { e164.startsWith(it.prefix) }

    override fun tldsForScript(script: UnicodeScript): Set<String> =
        if (script == UnicodeScript.CYRILLIC) setOf("ru", "рф") else emptySet()
}

/** Deterministic property testing: a fixed seed, so a failure always reproduces. */
fun forAll(seed: Long = 20260930, iterations: Int = 1_000, body: (java.util.Random) -> Unit) {
    val random = java.util.Random(seed)
    repeat(iterations) { body(random) }
}
