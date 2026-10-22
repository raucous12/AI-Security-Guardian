package lian.core.engine.watch

import lian.core.engine.Severity
import lian.core.engine.app.AppReport
import lian.core.engine.app.Capability
import lian.core.engine.app.Provenance
import lian.core.engine.device.CheckStatus
import lian.core.engine.device.PostureReport

/**
 * Watch snapshots (Phase 3.7). Apps are keyed by a keyed one-way fingerprint of their package name,
 * so a stored snapshot never contains the list of installed apps. The fingerprint function is
 * supplied by the platform (HMAC with a non-exportable Keystore key).
 */
data class AppState(val signerFp: String, val provenance: Provenance, val launcher: Boolean, val special: Set<Capability>, val score: Int)

data class Snapshot(
    val ruleSetVersion: String,
    val apps: Map<String, AppState>,
    /** Check group → worst status in that group. */
    val posture: Map<String, CheckStatus>,
    val userCaCount: Int,
    /** Epoch day of the last notification, for the one-per-day limit. */
    val lastNotifiedDay: Long = -1,
) {
    fun encode(): String = buildString {
        append("v1\n")
        append("rules\t").append(ruleSetVersion).append('\n')
        append("cas\t").append(userCaCount).append('\n')
        append("notified\t").append(lastNotifiedDay).append('\n')
        posture.toSortedMap().forEach { (k, v) -> append("posture\t").append(k).append('\t').append(v.name).append('\n') }
        apps.toSortedMap().forEach { (fp, s) ->
            append("app\t").append(fp).append('\t').append(s.signerFp).append('\t').append(s.provenance.name).append('\t')
                .append(if (s.launcher) 1 else 0).append('\t').append(s.special.sorted().joinToString(",") { it.name }).append('\t')
                .append(s.score).append('\n')
        }
    }

    companion object {
        /** Returns null for anything unreadable; Watch then starts a fresh baseline instead of alerting. */
        fun decode(text: String): Snapshot? {
            val lines = text.lines()
            if (lines.firstOrNull() != "v1") return null
            var rules = ""
            var cas = 0
            var notified = -1L
            val posture = HashMap<String, CheckStatus>()
            val apps = HashMap<String, AppState>()
            for (line in lines.drop(1)) {
                val f = line.split('\t')
                when (f[0]) {
                    "rules" -> rules = f.getOrElse(1) { "" }
                    "cas" -> cas = f.getOrNull(1)?.toIntOrNull() ?: 0
                    "notified" -> notified = f.getOrNull(1)?.toLongOrNull() ?: -1
                    "posture" -> if (f.size == 3) CheckStatus.entries.firstOrNull { it.name == f[2] }?.let { posture[f[1]] = it }
                    "app" -> if (f.size == 7) {
                        val provenance = Provenance.entries.firstOrNull { it.name == f[3] } ?: return null
                        val special = f[5].split(',').filter { it.isNotEmpty() }.mapNotNull { n -> Capability.entries.firstOrNull { it.name == n } }.toSet()
                        apps[f[1]] = AppState(f[2], provenance, f[4] == "1", special, f[6].toIntOrNull() ?: 0)
                    }
                }
            }
            return Snapshot(rules, apps, posture, cas, notified)
        }

        /** Builds a snapshot from today's reports. [fingerprint] must be keyed and one-way. */
        fun of(reports: List<AppReport>, posture: PostureReport, userCaCount: Int, ruleSetVersion: String, fingerprint: (String) -> String): Snapshot =
            Snapshot(
                ruleSetVersion = ruleSetVersion,
                apps = reports.associate { r ->
                    fingerprint("pkg:" + r.facts.packageName) to AppState(
                        signerFp = fingerprint("sig:" + r.facts.signerSha256.sorted().joinToString(",")),
                        provenance = r.provenance,
                        launcher = r.facts.hasLauncherIcon,
                        special = r.facts.active intersect WatchDiff.MONITORING,
                        score = r.score,
                    )
                },
                posture = posture.checks.groupBy { it.group }.mapValues { (_, checks) -> checks.minBy { STATUS_RANK.getValue(it.status) }.status },
                userCaCount = userCaCount,
            )

        private val STATUS_RANK = mapOf(CheckStatus.FAIL to 0, CheckStatus.WARN to 1, CheckStatus.UNKNOWN to 2, CheckStatus.INFO to 3, CheckStatus.PASS to 4)
    }
}

/** A change worth telling the user about. [appFp] refers to the current snapshot's fingerprint for that app. */
data class WatchEvent(val type: Type, val severity: Severity, val appFp: String?, val detail: String) {
    enum class Type { SIGNER_CHANGED, ICON_HIDDEN, SPECIAL_ACCESS_GAINED, NEW_APP, RISK_ROSE, USER_CA_ADDED, POSTURE_REGRESSED }
}

object WatchDiff {
    /** Takeover powers worth an alert; ordinary permissions such as camera are not tracked. */
    val MONITORING = setOf(
        Capability.ACCESSIBILITY, Capability.DEVICE_ADMIN, Capability.NOTIFICATION_LISTENER, Capability.SMS,
        Capability.USAGE_ACCESS, Capability.OVERLAY, Capability.KEYBOARD, Capability.BACKGROUND_LOCATION,
    )

    /** The first run (no previous snapshot) only records a baseline: no alerts. */
    fun diff(old: Snapshot?, new: Snapshot): List<WatchEvent> {
        if (old == null) return emptyList()
        val events = ArrayList<WatchEvent>()
        for ((fp, now) in new.apps.toSortedMap()) {
            val before = old.apps[fp]
            if (before == null) {
                val outsideStore = now.provenance !in setOf(Provenance.STORE_REVIEWED, Provenance.SYSTEM)
                if (now.score >= 20 || outsideStore) {
                    events += WatchEvent(WatchEvent.Type.NEW_APP, if (now.score >= 50) Severity.HIGH else Severity.MEDIUM, fp, "score ${now.score}")
                }
                continue
            }
            if (before.signerFp != now.signerFp) events += WatchEvent(WatchEvent.Type.SIGNER_CHANGED, Severity.CRITICAL, fp, "signing certificate changed")
            if (before.launcher && !now.launcher && (now.special intersect MONITORING).isNotEmpty()) {
                events += WatchEvent(WatchEvent.Type.ICON_HIDDEN, Severity.CRITICAL, fp, "icon hidden while holding special access")
            }
            val gained = now.special - before.special
            if (gained.isNotEmpty()) {
                val sev = if (now.provenance == Provenance.STORE_REVIEWED || now.provenance == Provenance.SYSTEM) Severity.MEDIUM else Severity.HIGH
                events += WatchEvent(WatchEvent.Type.SPECIAL_ACCESS_GAINED, sev, fp, gained.sorted().joinToString(",") { it.name })
            }
            if (old.ruleSetVersion == new.ruleSetVersion && now.score - before.score >= 20) {
                events += WatchEvent(WatchEvent.Type.RISK_ROSE, Severity.MEDIUM, fp, "${before.score} → ${now.score}")
            }
        }
        if (new.userCaCount > old.userCaCount) events += WatchEvent(WatchEvent.Type.USER_CA_ADDED, Severity.HIGH, null, "${old.userCaCount} → ${new.userCaCount}")
        for ((group, status) in new.posture.toSortedMap()) {
            val was = old.posture[group] ?: continue
            if (was == CheckStatus.PASS && (status == CheckStatus.WARN || status == CheckStatus.FAIL)) {
                events += WatchEvent(WatchEvent.Type.POSTURE_REGRESSED, if (status == CheckStatus.FAIL) Severity.HIGH else Severity.MEDIUM, null, group)
            }
        }
        return events.sortedWith(compareByDescending<WatchEvent> { it.severity }.thenBy { it.type }.thenBy { it.appFp.orEmpty() })
    }

    /** Notify only for MEDIUM or worse, and at most once per day. */
    fun shouldNotify(events: List<WatchEvent>, lastNotifiedDay: Long, today: Long): Boolean =
        events.any { it.severity >= Severity.MEDIUM } && today > lastNotifiedDay
}
