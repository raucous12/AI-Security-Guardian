package lian.core.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuleSetTest {
    private class FixedRule(
        name: String,
        private val weight: Int,
        private val severity: Severity = Severity.MEDIUM,
        override val needsCorroboration: Boolean = false,
    ) : Rule<Unit> {
        override val id = RuleId(name)
        override val version = 1
        override fun evaluate(facts: Unit, ctx: EvalContext) = Finding(id, version, severity, weight, Confidence.CERTAIN, emptyList())
    }

    private val ctx = EvalContext(nowEpochMs = 0)

    @Test
    fun `weak signals only count when corroborated`() {
        val alone = RuleSet("t", listOf(FixedRule("weak", 15, needsCorroboration = true), FixedRule("small", 10)))
        assertEquals(listOf("small"), alone.evaluate(Unit, ctx).map { it.rule.value })

        val corroborated = RuleSet("t", listOf(FixedRule("weak", 15, needsCorroboration = true), FixedRule("strong", 15)))
        assertEquals(setOf("weak", "strong"), corroborated.evaluate(Unit, ctx).map { it.rule.value }.toSet())
    }

    @Test
    fun `output order is total and independent of rule order`() {
        val rules = listOf(FixedRule("b", 30), FixedRule("a", 30), FixedRule("c", 90, Severity.CRITICAL), FixedRule("d", 0, Severity.INFO))
        val expected = listOf("c", "a", "b", "d")
        assertEquals(expected, RuleSet("t", rules).evaluate(Unit, ctx).map { it.rule.value })
        assertEquals(expected, RuleSet("t", rules.reversed()).evaluate(Unit, ctx).map { it.rule.value })
    }

    @Test
    fun `duplicate rule ids are rejected`() {
        assertFailsWith<IllegalArgumentException> { RuleSet("t", listOf(FixedRule("x", 1), FixedRule("x", 2))) }
    }
}
