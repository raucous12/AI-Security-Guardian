package lian.core.engine.background

import lian.core.engine.Confidence
import lian.core.engine.EvalContext
import lian.core.engine.Evidence
import lian.core.engine.Finding
import lian.core.engine.FixTarget
import lian.core.engine.Rule
import lian.core.engine.RuleId
import lian.core.engine.RuleSet
import lian.core.engine.Scoring
import lian.core.engine.Severity
import lian.core.engine.app.AppBand
import lian.core.engine.app.AppFacts
import lian.core.engine.app.Capability
import lian.core.engine.app.label

/**
 * How much an app worked while you weren't using it, from Android's own usage records
 * (UsageStatsManager; needs the user-granted "usage access"). Durations are over the observed window.
 */
data class BackgroundUsage(
    /** Time a foreground service was running (the way Android lets apps keep working unseen). */
    val foregroundServiceMs: Long,
    /** Time the app was actually on screen. */
    val visibleMs: Long,
    /** Background minutes per hour, oldest first (24 entries for a day). */
    val hourlyMinutes: List<Int>,
) {
    /** Background time that wasn't covered by the app being on screen. */
    val unseenMs: Long get() = (foregroundServiceMs - visibleMs).coerceAtLeast(0)
}

/** Everything the background rules need about one app. */
data class BackgroundFacts(
    val app: AppFacts,
    /** Null when usage access isn't granted: only the static signals below are judged. */
    val usage: BackgroundUsage?,
    /** Declares a receiver that starts it when the phone boots. */
    val startsAtBoot: Boolean,
    /** On the battery-optimisation allowlist: Android won't hold it back in the background. */
    val batteryUnrestricted: Boolean,
) {
    val ranUnseen: Boolean get() = usage != null && usage.unseenMs >= MIN_UNSEEN_MS
    val personalData: Set<Capability> get() = app.active intersect PERSONAL_DATA

    companion object {
        /** Five minutes: shorter bursts (a sync, a download) are normal for almost every app. */
        const val MIN_UNSEEN_MS = 5 * 60_000L

        /** What an app could collect while it runs unseen. */
        val PERSONAL_DATA = setOf(
            Capability.BACKGROUND_LOCATION, Capability.MICROPHONE, Capability.CAMERA, Capability.CONTACTS,
            Capability.SMS, Capability.CALL_LOG, Capability.NOTIFICATION_LISTENER, Capability.ACCESSIBILITY,
        )
    }
}

data class BackgroundReport(val facts: BackgroundFacts, val findings: List<Finding>, val score: Int, val band: AppBand)

class BackgroundRule(
    id: String,
    override val version: Int,
    private val severity: Severity,
    private val weight: Int,
    private val confidence: Confidence,
    private val group: String? = null,
    private val test: (BackgroundFacts) -> List<Evidence>?,
) : Rule<BackgroundFacts> {
    override val id = RuleId(id)
    override fun evaluate(facts: BackgroundFacts, ctx: EvalContext): Finding? =
        test(facts)?.let { Finding(this.id, version, severity, weight, confidence, it, FixTarget.APP_DETAILS, group) }
}

/**
 * LIAN's core promise turned into checks: which third-party apps keep running when you aren't using
 * them, and which of those could be collecting personal data while they do. Pre-installed system
 * components are left out; they run in the background by design.
 */
object BackgroundRules {
    const val VERSION = "bg-2026.10.1"
    private const val USAGE = "UsageStatsManager"
    private const val PM = "PackageManager"

    private fun minutes(ms: Long) = (ms / 60_000).let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" }
    private fun ev(key: String, value: String, source: String) = Evidence(key, value, source)

    fun create(): RuleSet<BackgroundFacts> = RuleSet(
        VERSION,
        listOf(
            BackgroundRule("bg.hidden_worker", 1, Severity.HIGH, 50, Confidence.CERTAIN, group = "unseen") { f ->
                if (f.ranUnseen && !f.app.hasLauncherIcon) {
                    listOf(ev("ran unseen", minutes(f.usage!!.unseenMs), USAGE), ev("launcher icon", "none: you can't open it yourself", PM))
                } else null
            },
            BackgroundRule("bg.collects_unseen", 1, Severity.HIGH, 45, Confidence.LIKELY, group = "unseen") { f ->
                val data = f.personalData
                if (f.ranUnseen && data.isNotEmpty()) {
                    listOf(ev("ran unseen", minutes(f.usage!!.unseenMs), USAGE), ev("has access to", data.sorted().joinToString(", ") { it.label }, PM))
                } else null
            },
            BackgroundRule("bg.reads_screen", 1, Severity.HIGH, 40, Confidence.CERTAIN) { f ->
                if (Capability.ACCESSIBILITY in f.app.active && f.app.accessibilityTool != true) listOf(ev("Accessibility", "on: it can read your screen at any time", "AccessibilityManager")) else null
            },
            BackgroundRule("bg.reads_notifications", 1, Severity.MEDIUM, 30, Confidence.CERTAIN) { f ->
                if (Capability.NOTIFICATION_LISTENER in f.app.active) listOf(ev("notification access", "on: it sees every message preview", "NotificationManager")) else null
            },
            BackgroundRule("bg.location_always", 1, Severity.MEDIUM, 25, Confidence.CERTAIN) { f ->
                if (Capability.BACKGROUND_LOCATION in f.app.active) listOf(ev("location", "allowed all the time, even when closed", PM)) else null
            },
            BackgroundRule("bg.ran_unseen", 1, Severity.MEDIUM, 20, Confidence.CERTAIN, group = "unseen") { f ->
                if (f.ranUnseen) listOf(ev("ran unseen", minutes(f.usage!!.unseenMs), USAGE), ev("on screen", minutes(f.usage.visibleMs), USAGE)) else null
            },
            BackgroundRule("bg.battery_unrestricted", 1, Severity.LOW, 15, Confidence.CERTAIN) { f ->
                if (f.batteryUnrestricted) listOf(ev("battery", "unrestricted: Android won't pause it in the background", "PowerManager")) else null
            },
            BackgroundRule("bg.starts_at_boot", 1, Severity.LOW, 10, Confidence.CERTAIN) { f ->
                if (f.startsAtBoot) listOf(ev("starts", "by itself when the phone turns on", PM)) else null
            },
        ),
    )
}

class BackgroundAnalyzer {
    val rules = BackgroundRules.create()

    /** System components are excluded; the rest are scored and ordered by risk, then by unseen time. */
    fun analyzeAll(apps: List<BackgroundFacts>, ctx: EvalContext): List<BackgroundReport> = apps
        .filterNot { it.app.install.isSystemApp }
        .map { f ->
            val findings = rules.evaluate(f, ctx)
            val score = Scoring.riskScore(findings)
            BackgroundReport(f, findings, score, AppBand.of(score))
        }
        .sortedWith(
            compareByDescending<BackgroundReport> { it.score }
                .thenByDescending { it.facts.usage?.unseenMs ?: 0 }
                .thenBy { it.facts.app.label.lowercase() }
                .thenBy { it.facts.app.packageName },
        )
}
