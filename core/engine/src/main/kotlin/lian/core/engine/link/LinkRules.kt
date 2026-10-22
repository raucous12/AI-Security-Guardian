package lian.core.engine.link

import lian.core.engine.Confidence
import lian.core.engine.EvalContext
import lian.core.engine.Evidence
import lian.core.engine.Finding
import lian.core.engine.Rule
import lian.core.engine.RuleId
import lian.core.engine.RuleSet
import lian.core.engine.Severity
import lian.core.engine.ml.LinearModel
import lian.core.engine.ml.MlText

/** A link rule: fires when [test] returns evidence. Weight may come from data (the TLD table). */
class LinkRule(
    id: String,
    override val version: Int,
    private val severity: Severity,
    private val weight: Int,
    private val confidence: Confidence,
    private val group: String? = null,
    override val needsCorroboration: Boolean = false,
    private val test: (LinkFacts) -> Pair<List<Evidence>, Int?>?,
) : Rule<LinkFacts> {
    override val id = RuleId(id)

    override fun evaluate(facts: LinkFacts, ctx: EvalContext): Finding? {
        val (evidence, weightOverride) = test(facts) ?: return null
        val w = weightOverride ?: weight
        val sev = if (w >= 20 && severity < Severity.MEDIUM) Severity.MEDIUM else severity
        return Finding(this.id, version, sev, w, confidence, evidence, correlationGroup = group)
    }
}

/**
 * The link rule catalogue (Phase 3.3). Weights are set by people and documented here; changing one
 * changes [VERSION], so stored results stay reproducible.
 */
object LinkRules {
    const val VERSION = "link-2026.10.1"

    private const val URL = "URL parser"
    private const val PSL = "Public Suffix List"
    private const val UTS39 = "Unicode UTS #39"
    private const val BRANDS = "brand list"
    private const val DOMAIN_MODEL = "on-device model domain.vgm"

    private fun modelEvidence(p: LinearModel.Prediction, f: LinkFacts): List<Evidence> = listOf(
        ev("name checked", f.registrableLabel.orEmpty(), PSL),
        ev("pattern score", "${p.percent}/100", DOMAIN_MODEL),
        ev("main signals", MlText.reasons(p), DOMAIN_MODEL),
    )

    private val ACTIVE_DATA_TYPES = setOf("text/html", "application/xhtml+xml", "image/svg+xml", "text/javascript", "application/javascript")
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443, "ftp" to 21, "ws" to 80, "wss" to 443)

    private fun ev(key: String, value: String, source: String) = Evidence(key, value.take(120), source)
    private fun fire(vararg evidence: Evidence) = evidence.toList() to null

    private fun brandEvidence(f: LinkFacts, hit: BrandHit): List<Evidence> = listOfNotNull(
        ev("domain", f.registrableUnicode.orEmpty(), PSL),
        ev("resembles", hit.brand.officialDomains.minOrNull() ?: hit.brand.label, BRANDS),
        if (hit.kind == BrandHitKind.TYPOSQUAT) ev("letters changed", hit.distance.toString(), "OSA distance") else null,
        if (hit.kind == BrandHitKind.HOMOGRAPH) ev("reads as", hit.brand.label, UTS39) else null,
    )

    fun create(): RuleSet<LinkFacts> = RuleSet(
        VERSION,
        listOf(
            LinkRule("link.scheme.script", 1, Severity.CRITICAL, 95, Confidence.CERTAIN) { f ->
                when {
                    f.url.scheme == "javascript" || f.url.scheme == "vbscript" -> fire(ev("scheme", "${f.url.scheme}:", URL))
                    f.url.scheme == "data" && f.dataMime in ACTIVE_DATA_TYPES -> fire(ev("scheme", "data:", URL), ev("content type", f.dataMime.orEmpty(), URL))
                    else -> null
                }
            },
            LinkRule("link.brand.homograph", 1, Severity.CRITICAL, 90, Confidence.LIKELY, group = "brand") { f ->
                f.brandHit?.takeIf { it.kind == BrandHitKind.HOMOGRAPH }?.let { brandEvidence(f, it) to null }
            },
            LinkRule("link.intent.install", 1, Severity.HIGH, 70, Confidence.CERTAIN) { f ->
                f.intent?.takeIf { it.installsApp }?.let {
                    fire(ev("intent", listOfNotNull(it.action, it.mimeType, it.component, it.fallbackUrl).joinToString(" · "), URL))
                }
            },
            LinkRule("link.userinfo", 1, Severity.HIGH, 60, Confidence.CERTAIN) { f ->
                f.url.userinfo?.let { fire(ev("text before @", it, URL), ev("real destination", f.displayHost, URL)) }
            },
            LinkRule("link.brand.typo", 1, Severity.HIGH, 60, Confidence.LIKELY, group = "brand") { f ->
                f.brandHit?.takeIf { it.kind == BrandHitKind.TYPOSQUAT }?.let { brandEvidence(f, it) to null }
            },
            LinkRule("link.brand.combo", 1, Severity.HIGH, 55, Confidence.LIKELY, group = "brand") { f ->
                f.brandHit?.takeIf { it.kind == BrandHitKind.COMBOSQUAT }?.let { brandEvidence(f, it) to null }
            },
            LinkRule("link.brand.subdomain", 1, Severity.HIGH, 55, Confidence.LIKELY, group = "brand") { f ->
                f.subdomainBrand?.let {
                    fire(ev("brand name in", f.subdomainLabels.joinToString("."), URL), ev("real domain", f.registrableUnicode.orEmpty(), PSL))
                }
            },
            LinkRule("link.brand.unofficial", 1, Severity.HIGH, 50, Confidence.LIKELY, group = "brand") { f ->
                f.brandHit?.takeIf { it.kind == BrandHitKind.UNOFFICIAL_DOMAIN }?.let { brandEvidence(f, it) to null }
            },
            LinkRule("link.idn.mixed_script", 1, Severity.HIGH, 50, Confidence.LIKELY, group = "idn") { f ->
                f.mixedScriptLabel?.let {
                    fire(ev("label", DisplayText.escape(it), URL), ev("scripts", f.mixedScripts.joinToString(" + ") { s -> s.name.lowercase() }, UTS39))
                }
            },
            LinkRule("link.idn.invisible", 1, Severity.HIGH, 50, Confidence.CERTAIN, group = "idn") { f ->
                f.invisible.takeIf { it.isNotEmpty() }?.let { fire(ev("hidden characters", it.distinct().joinToString(" ") { cp -> Scripts.hex(cp) }, UTS39)) }
            },
            LinkRule("link.bidi", 1, Severity.HIGH, 50, Confidence.CERTAIN) { f ->
                f.bidi.takeIf { it.isNotEmpty() }?.let { fire(ev("direction controls", it.distinct().joinToString(" ") { cp -> Scripts.hex(cp) }, "Unicode")) }
            },
            LinkRule("link.ip.obfuscated", 1, Severity.HIGH, 50, Confidence.CERTAIN, group = "ip") { f ->
                f.ipv4?.takeIf { it.obfuscated }?.let { fire(ev("written as", f.url.host, URL), ev("actual address", it.dotted, "WHATWG IPv4 parser")) }
            },
            LinkRule("link.idn.whole_script", 1, Severity.HIGH, 45, Confidence.LIKELY, group = "idn") { f ->
                f.wholeScriptConfusable?.let { fire(ev("label", f.registrableLabel.orEmpty(), URL), ev("reads like", it, UTS39)) }
            },
            LinkRule("link.stalkerware.site", 1, Severity.HIGH, 45, Confidence.CERTAIN) { f ->
                f.stalkerwareVendor?.let { fire(ev("site of", it, "stalkerware indicators (Echap)"), ev("domain", f.registrableUnicode.orEmpty(), PSL)) }
            },
            LinkRule("link.phone.premium", 1, Severity.HIGH, 45, Confidence.LIKELY) { f ->
                f.premium?.let { fire(ev("number", f.phone?.number.orEmpty(), URL), ev("type", "${it.description} (${it.region})", "premium-rate table")) }
            },
            LinkRule("link.idn.invalid", 1, Severity.HIGH, 40, Confidence.CERTAIN, group = "idn") { f ->
                f.invalidPunycode.takeIf { it.isNotEmpty() }?.let { fire(ev("malformed label", it.first(), "RFC 3492")) }
            },
            LinkRule("link.scheme.local", 1, Severity.MEDIUM, 35, Confidence.CERTAIN) { f ->
                if (f.url.scheme == "file" || f.url.scheme == "content") fire(ev("scheme", "${f.url.scheme}:", URL)) else null
            },
            LinkRule("link.host.encoded", 1, Severity.MEDIUM, 35, Confidence.CERTAIN, group = "encoding") { f ->
                val tricks = listOfNotNull(
                    "percent-encoded host".takeIf { f.url.hostPercentEncoded },
                    "full-width characters".takeIf { f.url.compatibilityForms },
                    "non-ASCII full stops".takeIf { f.url.unicodeDots },
                    "backslashes".takeIf { f.url.hasBackslash },
                    "unusual slashes after the scheme".takeIf { f.url.slashAnomaly },
                )
                tricks.takeIf { it.isNotEmpty() }?.let { fire(ev("tricks", it.joinToString(", "), URL)) }
            },
            LinkRule("link.host.invalid", 1, Severity.MEDIUM, 30, Confidence.CERTAIN) { f ->
                when {
                    f.hostKind == HostKind.INVALID -> fire(ev("host", f.url.host, URL))
                    f.url.portInvalid -> fire(ev("port", f.url.raw.substringAfterLast(':').take(12), URL))
                    f.url.hierarchical && f.hostKind == HostKind.NONE && f.url.scheme != "file" -> fire(ev("host", "(empty)", URL))
                    else -> null
                }
            },
            LinkRule("link.redirect.nested", 1, Severity.MEDIUM, 30, Confidence.LIKELY) { f ->
                f.nested.firstOrNull()?.let { fire(ev("forwards to", it, URL)) }
            },
            qrWifiOpen(),
            LinkRule("link.ip.literal", 1, Severity.MEDIUM, 25, Confidence.CERTAIN, group = "ip") { f ->
                when (f.hostKind) {
                    HostKind.IPV4 -> f.ipv4?.takeUnless { it.obfuscated }?.let { fire(ev("address", it.dotted, URL), ev("range", it.range, "IANA")) }
                    HostKind.IPV6 -> fire(ev("address", f.url.host, URL))
                    else -> null
                }
            },
            // Learned signals (Phase 2 R1 amended): a strong match can raise a link to "Be careful" on its own;
            // a moderate one only counts alongside another warning sign. Both describe one fact, so they share a group.
            LinkRule("link.ml.domain", 1, Severity.MEDIUM, 30, Confidence.INDICATOR, group = "ml") { f ->
                f.domainPrediction?.takeIf { it.level == LinearModel.Level.HIGH }?.let { modelEvidence(it, f) to null }
            },
            LinkRule("link.ml.domain_weak", 1, Severity.LOW, 15, Confidence.INDICATOR, group = "ml", needsCorroboration = true) { f ->
                f.domainPrediction?.takeIf { it.level == LinearModel.Level.MEDIUM }?.let { modelEvidence(it, f) to null }
            },
            LinkRule("link.shortener", 1, Severity.MEDIUM, 25, Confidence.CERTAIN) { f ->
                if (f.shortener) fire(ev("service", f.registrableUnicode.orEmpty(), "shortener list")) else null
            },
            LinkRule("link.tld.risk", 1, Severity.LOW, 10, Confidence.INDICATOR) { f ->
                f.tldRisk?.let { listOf(ev("ending", ".${it.tld}", "TLD table"), ev("why", it.note, "TLD table")) to it.weight }
            },
            LinkRule("link.hosting.shared", 1, Severity.LOW, 15, Confidence.INDICATOR) { f ->
                if (f.psl?.sharedHosting == true && f.officialBrand == null) fire(ev("hosted under", f.psl.publicSuffix, PSL)) else null
            },
            LinkRule("link.lure", 1, Severity.LOW, 15, Confidence.INDICATOR, needsCorroboration = true) { f ->
                f.lureWords.takeIf { it.isNotEmpty() }?.let { fire(ev("words", it.joinToString(", "), URL)) }
            },
            LinkRule("link.cleartext", 1, Severity.LOW, 10, Confidence.CERTAIN) { f ->
                if (f.url.scheme == "http" && !f.url.schemeMissing) fire(ev("scheme", "http:", URL)) else null
            },
            LinkRule("link.port", 1, Severity.LOW, 10, Confidence.CERTAIN) { f ->
                val port = f.url.port
                if (port != null && f.url.hierarchical && port != DEFAULT_PORTS[f.url.scheme]) fire(ev("port", port.toString(), URL)) else null
            },
            LinkRule("link.depth", 1, Severity.LOW, 10, Confidence.INDICATOR) { f ->
                if (f.subdomainLabels.size >= 4 || f.url.host.length > 50) fire(ev("host", f.displayHost, URL)) else null
            },
            LinkRule("link.official", 1, Severity.INFO, 0, Confidence.CERTAIN) { f ->
                f.officialBrand?.let { fire(ev("official site of", it.label, BRANDS), ev("domain", f.registrableUnicode.orEmpty(), PSL)) }
            },
        ),
    )

    private fun qrWifiOpen() = LinkRule("qr.wifi.open", 1, Severity.MEDIUM, 30, Confidence.CERTAIN) { f ->
        f.wifi?.takeIf { it.insecure }?.let {
            fire(ev("network", it.ssid ?: "(unnamed)", "Wi-Fi QR code"), ev("security", it.security ?: "none", "Wi-Fi QR code"))
        }
    }
}
