package lian.core.engine

/** Stable identifier of a rule, e.g. `link.userinfo`. The UI maps it to localized text. */
@JvmInline
value class RuleId(val value: String) {
    override fun toString(): String = value
}

enum class Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

/**
 * How sure the finding is about the underlying fact, not how bad it is.
 * INDICATOR: suggestive only (for example, root checks, where absence can never be proven).
 */
enum class Confidence { CERTAIN, LIKELY, INDICATOR }

/** One observed fact behind a finding, and the source that produced it. */
data class Evidence(val key: String, val value: String, val source: String)

/** Where the user can fix a finding; the app resolves it to a Settings deep link. */
enum class FixTarget { SYSTEM_UPDATE, SECURITY, DEVELOPER, NETWORK, VPN, APP_DETAILS, ACCESSIBILITY, DEVICE_ADMIN, NOTIFICATION_ACCESS }

data class Finding(
    val rule: RuleId,
    val ruleVersion: Int,
    val severity: Severity,
    /** Human-assigned 0..100, justified in the rule catalogue. 0 = informational. */
    val weight: Int,
    val confidence: Confidence,
    val evidence: List<Evidence>,
    val fix: FixTarget? = null,
    /** Findings describing the same underlying fact share a group and score once. */
    val correlationGroup: String? = null,
)

/**
 * Everything time- or environment-dependent is passed in, so evaluation is a pure function:
 * the same facts and context always give the same findings.
 */
data class EvalContext(
    val nowEpochMs: Long,
    val sdkInt: Int = 0,
    /** ISO 3166 region of the SIM/locale, used to read national phone-number formats. */
    val regionHint: String? = null,
)

interface Rule<in F> {
    val id: RuleId
    val version: Int

    /** Weak signals that only count when another finding of weight ≥ [RuleSet.CORROBORATION_WEIGHT] fired. */
    val needsCorroboration: Boolean get() = false

    fun evaluate(facts: F, ctx: EvalContext): Finding?
}

class RuleSet<F>(val version: String, private val rules: List<Rule<F>>) {
    init {
        val duplicates = rules.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate rule ids: $duplicates" }
    }

    val ids: List<RuleId> get() = rules.map { it.id }

    fun evaluate(facts: F, ctx: EvalContext): List<Finding> {
        val primary = rules.filterNot { it.needsCorroboration }.mapNotNull { it.evaluate(facts, ctx) }
        val corroborated = primary.any { it.weight >= CORROBORATION_WEIGHT }
        val secondary = if (corroborated) rules.filter { it.needsCorroboration }.mapNotNull { it.evaluate(facts, ctx) } else emptyList()
        return (primary + secondary).sortedWith(FINDING_ORDER)
    }

    companion object {
        const val CORROBORATION_WEIGHT = 15

        /** A total order, so output never depends on evaluation or hash iteration order. */
        val FINDING_ORDER: Comparator<Finding> = compareByDescending<Finding> { it.severity }
            .thenByDescending { it.weight }
            .thenBy { it.rule.value }
    }
}
