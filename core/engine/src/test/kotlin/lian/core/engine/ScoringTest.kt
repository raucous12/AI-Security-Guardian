package lian.core.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringTest {
    private fun finding(id: String, weight: Int, severity: Severity = Severity.MEDIUM, group: String? = null) =
        Finding(RuleId(id), 1, severity, weight, Confidence.CERTAIN, emptyList(), correlationGroup = group)

    @Test
    fun `noisy-or matches the Phase 3 worked example`() {
        // Sideloaded banking-trojan pattern: 55 (non-tool accessibility), 70 (combo), 20 (sideloaded) → 89.
        val score = Scoring.riskScore(listOf(finding("a", 55), finding("b", 70), finding("c", 20)))
        assertEquals(89, score)
    }

    @Test
    fun `empty and zero-weight findings score zero`() {
        assertEquals(0, Scoring.riskScore(emptyList()))
        assertEquals(0, Scoring.riskScore(listOf(finding("info", 0, Severity.INFO))))
    }

    @Test
    fun `same correlation group counts once with its highest weight`() {
        val grouped = Scoring.riskScore(listOf(finding("a", 50, group = "g"), finding("b", 40, group = "g")))
        assertEquals(50, grouped)
    }

    @Test
    fun `any critical finding lifts the score to the floor`() {
        assertEquals(Scoring.CRITICAL_FLOOR, Scoring.riskScore(listOf(finding("x", 10, Severity.CRITICAL))))
    }

    @Test
    fun `bounded, order-independent and monotonic`() = forAll { random ->
        val findings = List(random.nextInt(12)) { i ->
            finding("r$i", random.nextInt(101), Severity.entries[random.nextInt(4)], if (random.nextBoolean()) "g${random.nextInt(3)}" else null)
        }
        val score = Scoring.riskScore(findings)
        assertTrue(score in 0..100)
        assertEquals(score, Scoring.riskScore(findings.shuffled(random)))
        findings.forEach { extra ->
            assertTrue(Scoring.riskScore(findings + extra.copy(rule = RuleId("new." + extra.rule.value), correlationGroup = null)) >= score)
        }
    }
}
