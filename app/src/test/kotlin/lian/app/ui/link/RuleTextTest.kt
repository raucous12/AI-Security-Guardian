package lian.app.ui.link

import org.junit.Assert.assertTrue
import org.junit.Test
import lian.app.ui.RuleText
import lian.core.engine.EvalContext
import lian.core.engine.apk.ApkRules
import lian.core.engine.app.AppRules
import lian.core.engine.app.OsSupport
import lian.core.engine.background.BackgroundRules
import lian.core.engine.device.AttestationFacts
import lian.core.engine.device.DeviceChecks
import lian.core.engine.device.DeviceFacts
import lian.core.engine.device.Encryption
import lian.core.engine.device.PrivateDns
import lian.core.engine.link.LinkRules
import lian.core.engine.message.MessageRules

/** Every rule the engine can emit has its own title and explanation, so users never see "Other warning sign". */
class RuleTextTest {
    @Test
    fun `every link, message, app and apk rule has copy`() {
        val ids = (LinkRules.create().ids + MessageRules.create().ids + AppRules.create().ids + ApkRules.create().ids + BackgroundRules.create().ids).map { it.value }
        val missing = ids.filterNot { it in RuleText.coveredRuleIds }
        assertTrue("missing copy for $missing", missing.isEmpty())
    }

    @Test
    fun `every device check outcome has copy`() {
        val attestations = listOf(
            AttestationFacts.unavailable("x"),
            AttestationFacts(AttestationFacts.Verdict.REVOKED, 1, true, 0, null, "x"),
            AttestationFacts(AttestationFacts.Verdict.VERIFIED_HARDWARE, 1, true, 0, null, "x"),
            AttestationFacts(AttestationFacts.Verdict.VERIFIED_HARDWARE, 1, false, 2, null, "x"),
            AttestationFacts(AttestationFacts.Verdict.VERIFIED_HARDWARE, 1, true, 3, null, "x"),
        )
        val seen = HashSet<String>()
        for (flag in listOf(true, false, null)) for (a in attestations) for (enc in Encryption.entries) for (dns in PrivateDns.entries) {
            for (sdk in listOf(33, 34, 36)) for (patch in listOf(null, "2026-09-01", "2026-05-01", "2025-01-01")) {
                val facts = DeviceFacts(
                    sdk, "x", "x", "x", patch, flag, enc, flag, flag, flag, if (flag == true) 1 else if (flag == false) 0 else null,
                    if (flag == true) listOf("su") else emptyList(), a, dns, flag, flag,
                )
                val support = { s: Int -> (if (s <= 33) OsSupport.ENDED else if (s == 34) OsSupport.ENDING else OsSupport.SUPPORTED) to "x" }
                DeviceChecks.evaluate(facts, EvalContext(nowEpochMs = 1_790_000_000_000), support).checks.forEach { seen += it.id.value }
            }
        }
        val missing = seen.filterNot { it in RuleText.coveredRuleIds }
        assertTrue("missing copy for $missing", missing.isEmpty())
        assertTrue("only saw ${seen.size} outcomes", seen.size > 30)
    }
}
