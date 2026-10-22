package lian.core.engine.background

import lian.core.engine.EvalContext
import lian.core.engine.app.AppBand
import lian.core.engine.app.AppFacts
import lian.core.engine.app.Capability
import lian.core.engine.app.InstallFacts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundTest {
    private val analyzer = BackgroundAnalyzer()
    private val ctx = EvalContext(nowEpochMs = 0)

    private fun app(pkg: String, active: Set<Capability> = emptySet(), launcher: Boolean = true, system: Boolean = false) = AppFacts(
        packageName = pkg, label = pkg, versionName = "1", targetSdk = 35, signerSha256 = listOf("aa".repeat(32)), signerSha1 = emptyList(),
        debugCertificate = false, debuggable = false, testOnly = false, install = InstallFacts(system, "com.android.vending", true),
        hasLauncherIcon = launcher, requestedPermissions = emptySet(), active = active, declared = emptySet(),
    )

    private fun usage(unseenMinutes: Int, visibleMinutes: Int = 0) =
        BackgroundUsage((unseenMinutes + visibleMinutes) * 60_000L, visibleMinutes * 60_000L, List(24) { if (it == 3) unseenMinutes else 0 })

    private fun ids(r: BackgroundReport) = r.findings.map { it.rule.value }

    @Test
    fun `an app that worked unseen with access to your location and microphone is high risk`() {
        val r = analyzer.analyzeAll(listOf(BackgroundFacts(app("x", setOf(Capability.BACKGROUND_LOCATION, Capability.MICROPHONE)), usage(95), false, true)), ctx).single()
        assertTrue(listOf("bg.collects_unseen", "bg.location_always", "bg.battery_unrestricted").all { it in ids(r) }, ids(r).toString())
        assertTrue("bg.ran_unseen" in ids(r))   // same group as collects_unseen: shown, scored once
        assertTrue(r.band >= AppBand.HIGH, "score ${r.score}")
    }

    @Test
    fun `hidden apps that keep working are flagged even without personal-data access`() {
        val r = analyzer.analyzeAll(listOf(BackgroundFacts(app("y", launcher = false), usage(30), true, false)), ctx).single()
        assertTrue("bg.hidden_worker" in ids(r) && "bg.starts_at_boot" in ids(r))
    }

    @Test
    fun `short bursts and on-screen time are not background running`() {
        val brief = analyzer.analyzeAll(listOf(BackgroundFacts(app("z"), usage(3), false, false)), ctx).single()
        assertEquals(0, brief.score)
        val watched = analyzer.analyzeAll(listOf(BackgroundFacts(app("v"), BackgroundUsage(60 * 60_000L, 58 * 60_000L, List(24) { 0 }), false, false)), ctx).single()
        assertTrue("bg.ran_unseen" !in ids(watched))
    }

    @Test
    fun `without usage access only the static signals are judged`() {
        val r = analyzer.analyzeAll(listOf(BackgroundFacts(app("w", setOf(Capability.NOTIFICATION_LISTENER)), null, true, false)), ctx).single()
        assertEquals(setOf("bg.reads_notifications", "bg.starts_at_boot"), ids(r).toSet())
    }

    @Test
    fun `system components are left out and the list is riskiest first`() {
        val reports = analyzer.analyzeAll(
            listOf(
                BackgroundFacts(app("android.system", system = true), usage(500), true, true),
                BackgroundFacts(app("a.quiet"), null, false, false),
                BackgroundFacts(app("b.busy"), usage(240), false, false),
            ),
            ctx,
        )
        assertEquals(listOf("b.busy", "a.quiet"), reports.map { it.facts.app.packageName })
    }
}
