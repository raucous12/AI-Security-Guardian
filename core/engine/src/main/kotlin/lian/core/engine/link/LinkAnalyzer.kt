package lian.core.engine.link

import lian.core.engine.EvalContext
import lian.core.engine.Finding
import lian.core.engine.Scoring

/** Never "safe": the best a rule-based check can honestly say is that it found no warning signs. */
enum class LinkBand {
    NO_WARNING_SIGNS, CAUTION, LIKELY_SCAM, DANGEROUS;

    companion object {
        fun of(score: Int): LinkBand = when {
            score >= 70 -> DANGEROUS
            score >= 40 -> LIKELY_SCAM
            score >= 15 -> CAUTION
            else -> NO_WARNING_SIGNS
        }
    }
}

data class LinkSummary(
    val scheme: String,
    val displayHost: String?,
    val asciiHost: String?,
    val registrable: String?,
    /** http(s) link that a browser could open. */
    val isWebLink: Boolean,
)

data class LinkReport(
    val input: String,
    val summary: LinkSummary?,
    val findings: List<Finding>,
    val score: Int,
    val band: LinkBand,
    /** Links this one forwards to (redirect parameters, intent fallbacks), checked the same way. */
    val nested: List<LinkReport>,
)

data class LinkCheckResult(
    val reports: List<LinkReport>,
    val score: Int,
    val band: LinkBand,
    val ruleSetVersion: String,
    val intelVersion: String,
)

/**
 * Checks every link in a piece of text. Entirely offline: nothing is resolved, fetched or followed,
 * so the sender can never learn that the link was checked.
 */
class LinkAnalyzer(private val intel: LinkIntel) {
    val rules = LinkRules.create()
    private val factsBuilder = LinkFactsBuilder(intel)

    fun analyze(text: String, ctx: EvalContext): LinkCheckResult {
        val candidates = LinkScanner.extract(text).filter { it.kind != LinkCandidate.Kind.BARE_DOMAIN || hasKnownTld(it.text) }
        val reports = candidates.map { analyze(it, ctx, depth = 0) }
        val score = reports.maxOfOrNull { it.score } ?: 0
        return LinkCheckResult(reports, score, LinkBand.of(score), rules.version, intel.version)
    }

    private fun analyze(candidate: LinkCandidate, ctx: EvalContext, depth: Int): LinkReport {
        val facts = factsBuilder.build(candidate, depth, ctx)
            ?: return LinkReport(candidate.text, null, emptyList(), 0, LinkBand.NO_WARNING_SIGNS, emptyList())
        val findings = rules.evaluate(facts, ctx)
        val nested = if (depth < MAX_DEPTH) {
            facts.nested.map { analyze(LinkCandidate(it, LinkCandidate.Kind.EXPLICIT), ctx, depth + 1) }
        } else emptyList()
        val score = maxOf(Scoring.riskScore(findings), nested.maxOfOrNull { it.score } ?: 0)
        val summary = LinkSummary(
            scheme = facts.url.scheme,
            displayHost = facts.displayHost.takeIf { it.isNotEmpty() } ?: facts.ipv4?.dotted,
            asciiHost = facts.asciiHost.takeIf { it.isNotEmpty() },
            registrable = facts.registrableUnicode?.let(DisplayText::escape),
            isWebLink = facts.url.scheme == "http" || facts.url.scheme == "https",
        )
        return LinkReport(candidate.text, summary, findings, score, LinkBand.of(score), nested)
    }

    private fun hasKnownTld(bareDomain: String): Boolean {
        val host = bareDomain.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return intel.publicSuffixList.isKnownTld(host.substringAfterLast('.').lowercase())
    }

    companion object {
        const val MAX_DEPTH = 2
    }
}
