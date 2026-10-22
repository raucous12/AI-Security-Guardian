package lian.core.engine

object Scoring {
    /** Minimum score whenever any CRITICAL finding fired. */
    const val CRITICAL_FLOOR = 80

    /**
     * Noisy-OR in fixed point: each independent signal removes its share of the remaining
     * "no risk". Signals in the same correlation group count once (highest weight). Weights are
     * combined in descending order because integer rounding is order-sensitive; the order is fixed,
     * so the result is too. Adding a finding can never lower the score.
     */
    fun riskScore(findings: List<Finding>): Int {
        val weights = findings
            .filter { it.weight > 0 }
            .groupBy { it.correlationGroup ?: it.rule.value }
            .map { (_, same) -> same.maxOf { it.weight } }
            .sortedDescending()
        var clear = 10_000
        for (w in weights) clear = clear * (100 - w.coerceIn(0, 100)) / 100
        val score = (10_000 - clear + 50) / 100
        return if (findings.any { it.severity == Severity.CRITICAL }) maxOf(score, CRITICAL_FLOOR) else score
    }
}
