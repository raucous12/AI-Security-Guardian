package lian.core.engine.link

import lian.core.engine.EvalContext
import lian.core.engine.ml.DomainFeaturizer
import lian.core.engine.ml.LinearModel
import java.lang.Character.UnicodeScript

enum class HostKind { NONE, DOMAIN, IPV4, IPV6, INVALID }

/** Everything the link rules need, computed once per candidate. Immutable; rules are pure functions of it. */
data class LinkFacts(
    val candidate: LinkCandidate,
    val url: ParsedUrl,
    val depth: Int,
    val hostKind: HostKind,
    val ipv4: Ipv4Result.Address?,
    /** Host labels as written (lowercase; ASCII or Unicode). */
    val labels: List<String>,
    /** Host labels with `xn--` decoded. */
    val unicodeLabels: List<String>,
    val invalidPunycode: List<String>,
    val psl: PublicSuffixList.Match?,
    val registrableUnicode: String?,
    /** First label of the registrable domain, Unicode (`paypa1` in `login.paypa1.com`). */
    val registrableLabel: String?,
    val subdomainLabels: List<String>,
    val worstRestriction: RestrictionLevel,
    val mixedScriptLabel: String?,
    val mixedScripts: Set<UnicodeScript>,
    val wholeScriptConfusable: String?,
    val invisible: List<Int>,
    val bidi: List<Int>,
    val officialBrand: Brand?,
    val brandHit: BrandHit?,
    val subdomainBrand: Brand?,
    val shortener: Boolean,
    val tldRisk: TldRisk?,
    val lureWords: List<String>,
    val nested: List<String>,
    val wifi: WifiPayload?,
    val phone: PhonePayload?,
    val premium: PremiumPrefix?,
    val intent: IntentPayload?,
    val dataMime: String?,
    /** Domain-name model score for the registrable label; null when not applicable (official, IP, no model). */
    val domainPrediction: LinearModel.Prediction? = null,
    /** Name of the stalkerware product whose vendor site this is. */
    val stalkerwareVendor: String? = null,
) {
    val asciiHost: String get() = labels.joinToString(".") { Punycode.toAsciiLabel(it) ?: it }
    val displayHost: String get() = DisplayText.escape(unicodeLabels.joinToString("."))
}

object DisplayText {
    /** Makes invisible and bidi-control characters visible (Phase 4, defect #8): `⟦U+202E⟧`. */
    fun escape(text: String): String {
        val hidden = (Scripts.invisibleCodePoints(text) + Scripts.bidiControls(text)).toSet()
        if (hidden.isEmpty()) return text
        return buildString {
            text.codePoints().forEach { cp -> if (cp in hidden) append("⟦").append(Scripts.hex(cp)).append("⟧") else appendCodePoint(cp) }
        }
    }
}

internal class LinkFactsBuilder(private val intel: LinkIntel) {
    fun build(candidate: LinkCandidate, depth: Int, ctx: EvalContext): LinkFacts? {
        val url = UrlParser.parse(candidate.text) ?: return null
        val intent = if (url.scheme == "intent") Payloads.intent(url.opaque.orEmpty()) else null
        val hostText = when {
            url.hierarchical -> url.host
            url.scheme == "mailto" -> UrlParser.percentDecode(url.opaque.orEmpty().substringBefore('?').substringAfterLast('@')).lowercase()
            intent != null -> intent.target.orEmpty().substringBefore('/').substringBefore('?').lowercase()
            else -> ""
        }

        var hostKind = HostKind.NONE
        var ipv4: Ipv4Result.Address? = null
        var labels = emptyList<String>()
        var unicodeLabels = emptyList<String>()
        val invalidPunycode = mutableListOf<String>()
        when {
            hostText.isEmpty() -> Unit
            hostText.startsWith("[") -> hostKind = HostKind.IPV6
            else -> when (val ip = Ipv4.parse(hostText)) {
                is Ipv4Result.Address -> { hostKind = HostKind.IPV4; ipv4 = ip }
                Ipv4Result.Invalid -> hostKind = HostKind.INVALID
                Ipv4Result.NotIpv4 -> {
                    labels = hostText.split('.')
                    hostKind = if (labels.any { it.isEmpty() }) HostKind.INVALID else HostKind.DOMAIN
                    unicodeLabels = labels.map { label -> Punycode.toUnicodeLabel(label) ?: label.also { invalidPunycode += it } }
                }
            }
        }

        val psl = if (hostKind == HostKind.DOMAIN) intel.publicSuffixList.match(hostText) else null
        val registrableCount = psl?.registrable?.count { it == '.' }?.plus(1) ?: 0
        val registrableUnicode = if (registrableCount > 0) unicodeLabels.takeLast(registrableCount).joinToString(".") else null
        val registrableLabel = if (registrableCount > 0) unicodeLabels[unicodeLabels.size - registrableCount] else null
        val subdomainLabels = if (registrableCount > 0) unicodeLabels.dropLast(registrableCount) else emptyList()
        val suffix = psl?.publicSuffix.orEmpty()

        val levels = unicodeLabels.map { it to Scripts.restrictionLevel(it) }
        val worst = levels.maxOfOrNull { it.second } ?: RestrictionLevel.ASCII
        val mixedLabel = levels.firstOrNull { it.second > RestrictionLevel.HIGHLY_RESTRICTIVE }?.first
        val wholeScript = registrableLabel?.let { wholeScriptConfusable(it, unicodeLabels.lastOrNull().orEmpty()) }

        val official = if (registrableLabel != null && psl?.registrable != null) {
            intel.brands.bySkeleton(skeleton(registrableLabel, intel.confusables)).firstOrNull { it.isOfficial(psl.registrable, suffix) }
        } else null
        val brandHit = if (official == null && registrableLabel != null && psl?.registrable != null) {
            BrandMatcher.match(registrableLabel, psl.registrable, suffix, intel.brands, intel.confusables)
        } else null
        val subdomainBrand = if (official == null && psl?.registrable != null) {
            BrandMatcher.inSubdomains(subdomainLabels, intel.brands, intel.confusables)?.takeUnless { it.isOfficial(psl.registrable, suffix) }
        } else null

        val phone = if (url.scheme in setOf("tel", "sms", "smsto")) Payloads.phone(url.scheme, url.opaque.orEmpty()) else null
        val shortener = psl?.registrable?.let(intel::isShortener) == true

        // The model judges names a person chose; official brand domains and shorteners are never scored.
        val domainPrediction = if (official == null && !shortener && registrableLabel != null) {
            intel.domainModel?.predict(DomainFeaturizer, Punycode.toAsciiLabel(registrableLabel) ?: registrableLabel)
        } else null

        return LinkFacts(
            candidate = candidate,
            url = url,
            depth = depth,
            hostKind = hostKind,
            ipv4 = ipv4,
            labels = labels,
            unicodeLabels = unicodeLabels,
            invalidPunycode = invalidPunycode,
            psl = psl,
            registrableUnicode = registrableUnicode,
            registrableLabel = registrableLabel,
            subdomainLabels = subdomainLabels,
            worstRestriction = worst,
            mixedScriptLabel = mixedLabel,
            mixedScripts = mixedLabel?.let(Scripts::scriptsOf).orEmpty(),
            wholeScriptConfusable = wholeScript,
            invisible = Scripts.invisibleCodePoints(unicodeLabels.joinToString(".")),
            bidi = Scripts.bidiControls(candidate.text),
            officialBrand = official,
            brandHit = brandHit,
            subdomainBrand = subdomainBrand,
            shortener = shortener,
            tldRisk = if (hostKind == HostKind.DOMAIN && psl?.sharedHosting != true) intel.tldRisk(unicodeLabels.last()) else null,
            lureWords = lureWords(url, subdomainLabels),
            nested = nestedLinks(url, intent),
            wifi = if (url.scheme == "wifi") Payloads.wifi(candidate.text) else null,
            phone = phone,
            premium = phone?.toE164(ctx.regionHint)?.let(intel::premiumPrefix),
            intent = intent,
            dataMime = if (url.scheme == "data") url.opaque.orEmpty().substringBefore(',').substringBefore(';').trim().lowercase() else null,
            domainPrediction = domainPrediction,
            stalkerwareVendor = psl?.registrable?.let(intel::stalkerwareVendor),
        )
    }

    /** A label written entirely in one non-Latin script that looks like plain Latin (`аррӏе`), outside that script's TLDs. */
    private fun wholeScriptConfusable(label: String, tld: String): String? {
        val scripts = Scripts.scriptsOf(label)
        if (scripts.size != 1 || scripts.single() == UnicodeScript.LATIN || label.all { it.code < 0x80 }) return null
        val sk = skeleton(label, intel.confusables)
        if (sk.isEmpty() || !sk.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return null
        val tldUnicode = Punycode.toUnicodeLabel(tld) ?: tld
        return if (tldUnicode in intel.tldsForScript(scripts.single()) || tld in intel.tldsForScript(scripts.single())) null else sk
    }

    private fun lureWords(url: ParsedUrl, subdomains: List<String>): List<String> {
        val text = buildString {
            append(UrlParser.percentDecode(url.path)).append(' ')
            append(UrlParser.percentDecode(url.query.orEmpty())).append(' ')
            subdomains.forEach { append(it).append(' ') }
        }.lowercase()
        val tokens = text.split(NON_WORD).filter { it.length >= 3 }
        return tokens.filter { it in LURE_WORDS }.distinct().take(5)
    }

    private fun nestedLinks(url: ParsedUrl, intent: IntentPayload?): List<String> {
        val values = UrlParser.queryParameters(url.query).map { it.second } +
            UrlParser.queryParameters(url.fragment).map { it.second } +
            listOfNotNull(intent?.fallbackUrl)
        return values.map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("www.", true) }
            .distinct()
            .take(3)
    }

    companion object {
        /** A single negated character class: linear time on any input (no backtracking). */
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        val LURE_WORDS = setOf(
            "login", "logon", "signin", "verify", "verification", "account", "suspend", "suspended", "locked", "unlock",
            "secure", "security", "update", "confirm", "wallet", "recover", "recovery", "password", "billing", "invoice",
            "payment", "refund", "parcel", "delivery", "redelivery", "customs", "prize", "winner", "gift", "bonus",
            "claim", "kyc", "reward", "support", "helpdesk",
        )
    }
}
