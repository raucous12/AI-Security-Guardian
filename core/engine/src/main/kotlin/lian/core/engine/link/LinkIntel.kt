package lian.core.engine.link

import lian.core.engine.ml.LinearModel
import java.lang.Character.UnicodeScript

data class TldRisk(val tld: String, val weight: Int, val note: String)

data class PremiumPrefix(val prefix: String, val region: String, val description: String)

/** The bundled, versioned data the link rules consult. Implemented by :core:intel; tests use fixtures. */
interface LinkIntel {
    val version: String
    val publicSuffixList: PublicSuffixList
    val confusables: ConfusableMap
    val brands: BrandCatalog

    /** [registrable] is a known link-shortening service. */
    fun isShortener(registrable: String): Boolean

    /** [tld] as it appears in the host (ASCII or Unicode). */
    fun tldRisk(tld: String): TldRisk?

    fun premiumPrefix(e164: String): PremiumPrefix?

    /** TLDs where labels written entirely in [script] are expected (e.g. Cyrillic under .ru, .рф). */
    fun tldsForScript(script: UnicodeScript): Set<String>

    /** On-device domain-name model (DomainFeaturizer input), or null when not bundled. */
    val domainModel: LinearModel? get() = null

    /** Known stalkerware vendor sites (registrable domains), from the stalkerware indicator data. */
    fun stalkerwareVendor(registrable: String): String? = null
}
