package lian.core.engine.app

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
import lian.core.engine.link.skeleton

/**
 * Special powers an app can hold; most stalkerware and banking trojans need one or more of these.
 * CAMERA, MICROPHONE, CONTACTS and CALL_LOG are granted personal-data permissions, used by the
 * background-activity check to say what an app could collect while it runs unseen.
 */
enum class Capability {
    ACCESSIBILITY, DEVICE_ADMIN, NOTIFICATION_LISTENER, KEYBOARD, SMS, OVERLAY, BACKGROUND_LOCATION, USAGE_ACCESS,
    INSTALLS_APPS, VPN, CAMERA, MICROPHONE, CONTACTS, CALL_LOG,
}

/** Where an app came from, decided from Android's tamper-resistant initiating-installer record (Phase 3.4). */
enum class Provenance { SYSTEM, STORE_REVIEWED, STORE_INDEPENDENT, FILE_INSTALL, INSTALLED_BY_APP, SPOOFED_STORE, ADB_OR_UNKNOWN }

data class InstallFacts(
    val isSystemApp: Boolean,
    /** `InstallSourceInfo.getInitiatingPackageName()`: the app that really performed the install. */
    val initiatingPackage: String?,
    val initiatorIsSystemApp: Boolean,
)

/** Everything the app rules need about one installed app, read by :core:platform. Plain values only. */
data class AppFacts(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val targetSdk: Int,
    val signerSha256: List<String>,
    val signerSha1: List<String>,
    /** Signed with the Android SDK debug certificate (`CN=Android Debug`). */
    val debugCertificate: Boolean,
    val debuggable: Boolean,
    val testOnly: Boolean,
    val install: InstallFacts,
    val hasLauncherIcon: Boolean,
    val requestedPermissions: Set<String>,
    /** Capabilities the user has switched on (enabled Accessibility service, active admin, granted permission…). */
    val active: Set<Capability>,
    /** Capabilities the app declares (a service it could ask to have enabled). */
    val declared: Set<Capability>,
    /** Its enabled Accessibility service says it is an accessibility tool (API 31+); null when not applicable. */
    val accessibilityTool: Boolean? = null,
    /** True for the user's default SMS app, which legitimately holds SMS permissions. */
    val defaultSmsApp: Boolean = false,
)

enum class AppBand {
    LOW, MEDIUM, HIGH, CRITICAL;

    companion object {
        fun of(score: Int) = when {
            score >= 80 -> CRITICAL
            score >= 50 -> HIGH
            score >= 20 -> MEDIUM
            else -> LOW
        }
    }
}

data class AppReport(val facts: AppFacts, val provenance: Provenance, val storeName: String?, val findings: List<Finding>, val score: Int, val band: AppBand)

/** Facts plus derived context, so each rule stays a small pure function. */
data class AppContext(val facts: AppFacts, val provenance: Provenance, val intel: AppIntel) {
    val reviewed: Boolean get() = provenance == Provenance.STORE_REVIEWED
    val system: Boolean get() = provenance == Provenance.SYSTEM
}

object ProvenanceClassifier {
    fun classify(install: InstallFacts, stores: StoreCatalog): Pair<Provenance, String?> {
        if (install.isSystemApp) return Provenance.SYSTEM to null
        val initiator = install.initiatingPackage ?: return Provenance.ADB_OR_UNKNOWN to null
        val store = stores[initiator] ?: return Provenance.INSTALLED_BY_APP to initiator   // the dropper pattern
        return when (store.kind) {
            Store.Kind.SHELL -> Provenance.ADB_OR_UNKNOWN to store.name
            Store.Kind.INSTALLER -> Provenance.FILE_INSTALL to store.name
            Store.Kind.STORE -> when {
                store.mustBeSystem && !install.initiatorIsSystemApp -> Provenance.SPOOFED_STORE to store.name
                store.reviewed -> Provenance.STORE_REVIEWED to store.name
                else -> Provenance.STORE_INDEPENDENT to store.name
            }
        }
    }
}

/**
 * One app rule. The weight depends on where the app came from (Phase 3.4): a reviewed store has
 * already checked declared capabilities, so the same power weighs less there.
 */
class AppRule(
    id: String,
    override val version: Int,
    private val severity: Severity,
    private val reviewedWeight: Int,
    private val otherWeight: Int,
    private val confidence: Confidence,
    private val group: String? = null,
    private val fix: FixTarget? = FixTarget.APP_DETAILS,
    /** Rules about capabilities don't score for system apps (pre-installed components hold them legitimately). */
    private val appliesToSystem: Boolean = false,
    private val test: (AppContext) -> List<Evidence>?,
) : Rule<AppContext> {
    override val id = RuleId(id)

    override fun evaluate(facts: AppContext, ctx: EvalContext): Finding? {
        if (facts.system && !appliesToSystem) return null
        val evidence = test(facts) ?: return null
        val weight = if (facts.reviewed) reviewedWeight else otherWeight
        val sev = when {
            severity == Severity.CRITICAL -> severity
            weight >= 50 -> maxOf(severity, Severity.HIGH)
            weight >= 20 -> maxOf(severity, Severity.MEDIUM)
            else -> severity
        }
        return Finding(this.id, version, sev, weight, confidence, evidence, fix, group)
    }
}

object AppRules {
    const val VERSION = "app-2026.10.1"
    private const val PM = "PackageManager"
    private const val IOC = "stalkerware indicators (Echap)"

    private val MONITORING = setOf(
        Capability.ACCESSIBILITY, Capability.DEVICE_ADMIN, Capability.NOTIFICATION_LISTENER, Capability.SMS,
        Capability.USAGE_ACCESS, Capability.BACKGROUND_LOCATION, Capability.OVERLAY, Capability.KEYBOARD,
    )

    private fun ev(key: String, value: String, source: String = PM) = Evidence(key, value.take(120), source)

    fun create(): RuleSet<AppContext> = RuleSet(
        VERSION,
        listOf(
            AppRule("app.ioc.stalkerware", 1, Severity.CRITICAL, 95, 95, Confidence.CERTAIN, appliesToSystem = true) { c ->
                indicator(c, Indicator.Type.STALKERWARE)
            },
            AppRule("app.impostor", 1, Severity.CRITICAL, 85, 85, Confidence.LIKELY) { c -> impostor(c) },
            AppRule("app.combo.trojan", 1, Severity.HIGH, 25, 70, Confidence.LIKELY, group = "combo") { c ->
                val a = c.facts.active
                val partner = listOf(Capability.SMS, Capability.OVERLAY, Capability.NOTIFICATION_LISTENER).filter { it in a }
                if (Capability.ACCESSIBILITY in a && partner.isNotEmpty()) {
                    listOf(ev("combination", (listOf(Capability.ACCESSIBILITY) + partner).joinToString(" + ") { it.label }))
                } else null
            },
            AppRule("app.stealth.surveil", 1, Severity.HIGH, 40, 70, Confidence.LIKELY, group = "stealth") { c ->
                val powers = c.facts.active intersect MONITORING
                if (!c.facts.hasLauncherIcon && powers.isNotEmpty()) {
                    listOf(ev("launcher icon", "none (hidden from the app drawer)"), ev("special access", powers.sorted().joinToString(", ") { it.label }))
                } else null
            },
            AppRule("app.src.spoofed_store", 1, Severity.HIGH, 60, 60, Confidence.LIKELY, group = "source") { c ->
                if (c.provenance == Provenance.SPOOFED_STORE) listOf(ev("installed by", c.facts.install.initiatingPackage.orEmpty(), "InstallSourceInfo"), ev("problem", "uses a store's name but isn't the pre-installed store")) else null
            },
            AppRule("app.a11y.non_tool", 1, Severity.MEDIUM, 25, 55, Confidence.CERTAIN, group = "a11y", fix = FixTarget.ACCESSIBILITY) { c ->
                if (Capability.ACCESSIBILITY in c.facts.active && c.facts.accessibilityTool != true) listOf(ev("Accessibility service", "enabled", "AccessibilityManager")) else null
            },
            AppRule("app.a11y.tool", 1, Severity.LOW, 10, 40, Confidence.CERTAIN, group = "a11y", fix = FixTarget.ACCESSIBILITY) { c ->
                if (Capability.ACCESSIBILITY in c.facts.active && c.facts.accessibilityTool == true) listOf(ev("Accessibility service", "enabled; declares itself an accessibility tool", "AccessibilityManager")) else null
            },
            AppRule("app.ioc.watchware", 1, Severity.MEDIUM, 30, 45, Confidence.CERTAIN, appliesToSystem = true) { c ->
                indicator(c, Indicator.Type.WATCHWARE)
            },
            AppRule("app.ime", 1, Severity.MEDIUM, 8, 50, Confidence.CERTAIN) { c ->
                if (Capability.KEYBOARD in c.facts.active) listOf(ev("default keyboard", "yes: it sees everything you type", "Settings.Secure")) else null
            },
            AppRule("app.admin", 1, Severity.MEDIUM, 10, 45, Confidence.CERTAIN, fix = FixTarget.DEVICE_ADMIN) { c ->
                if (Capability.DEVICE_ADMIN in c.facts.active) listOf(ev("device admin", "active", "DevicePolicyManager")) else null
            },
            AppRule("app.sms", 1, Severity.MEDIUM, 15, 45, Confidence.CERTAIN) { c ->
                if (Capability.SMS in c.facts.active && !c.facts.defaultSmsApp) listOf(ev("SMS permission", "granted; not your SMS app")) else null
            },
            AppRule("app.notif", 1, Severity.MEDIUM, 10, 35, Confidence.CERTAIN, fix = FixTarget.NOTIFICATION_ACCESS) { c ->
                if (Capability.NOTIFICATION_LISTENER in c.facts.active) listOf(ev("notification access", "enabled: it can read all notifications", "NotificationManager")) else null
            },
            AppRule("app.src.by_app", 1, Severity.MEDIUM, 30, 30, Confidence.CERTAIN, group = "source") { c ->
                if (c.provenance == Provenance.INSTALLED_BY_APP) listOf(ev("installed by", c.facts.install.initiatingPackage.orEmpty(), "InstallSourceInfo")) else null
            },
            AppRule("app.sdk.legacy", 1, Severity.MEDIUM, 30, 30, Confidence.CERTAIN, group = "sdk") { c ->
                if (c.facts.targetSdk in 1..22) listOf(ev("target SDK", "${c.facts.targetSdk}: gets permissions without asking")) else null
            },
            AppRule("app.overlay", 1, Severity.MEDIUM, 5, 30, Confidence.CERTAIN) { c ->
                if (Capability.OVERLAY in c.facts.active) listOf(ev("display over other apps", "allowed", "AppOpsManager")) else null
            },
            AppRule("app.build.debug", 1, Severity.MEDIUM, 20, 25, Confidence.CERTAIN, group = "build", appliesToSystem = true) { c ->
                when {
                    c.facts.debugCertificate -> listOf(ev("signed with", "the Android debug certificate"))
                    c.facts.debuggable -> listOf(ev("flag", "debuggable"))
                    else -> null
                }
            },
            AppRule("app.usage_access", 1, Severity.MEDIUM, 5, 25, Confidence.CERTAIN) { c ->
                if (Capability.USAGE_ACCESS in c.facts.active) listOf(ev("usage access", "granted: sees which apps you use and when", "AppOpsManager")) else null
            },
            AppRule("app.src.file", 1, Severity.MEDIUM, 20, 20, Confidence.CERTAIN, group = "source") { c ->
                if (c.provenance == Provenance.FILE_INSTALL) listOf(ev("installed from", "an APK file", "InstallSourceInfo")) else null
            },
            AppRule("app.bg_location", 1, Severity.LOW, 8, 20, Confidence.CERTAIN) { c ->
                if (Capability.BACKGROUND_LOCATION in c.facts.active) listOf(ev("location", "allowed all the time")) else null
            },
            AppRule("app.src.adb", 1, Severity.LOW, 15, 15, Confidence.CERTAIN, group = "source") { c ->
                if (c.provenance == Provenance.ADB_OR_UNKNOWN) listOf(ev("installed by", c.facts.install.initiatingPackage ?: "unknown (adb or a computer)", "InstallSourceInfo")) else null
            },
            AppRule("app.build.test_only", 1, Severity.LOW, 15, 15, Confidence.CERTAIN, group = "build", appliesToSystem = true) { c ->
                if (c.facts.testOnly) listOf(ev("flag", "testOnly")) else null
            },
            AppRule("app.installs_apps", 1, Severity.LOW, 0, 15, Confidence.CERTAIN) { c ->
                if ("android.permission.REQUEST_INSTALL_PACKAGES" in c.facts.requestedPermissions) listOf(ev("can request", "installing other apps")) else null
            },
            AppRule("app.sdk.old", 1, Severity.LOW, 8, 12, Confidence.CERTAIN, group = "sdk") { c ->
                if (c.facts.targetSdk in 23..28) listOf(ev("target SDK", "${c.facts.targetSdk} (built for Android 9 or older)")) else null
            },
        ),
    )

    private fun indicator(c: AppContext, type: Indicator.Type): List<Evidence>? {
        val set = c.intel.indicators
        val byPackage = set.byPackage(c.facts.packageName)?.takeIf { it.type == type }
        val byCert = c.facts.signerSha1.firstNotNullOfOrNull { sha1 -> set.byCertSha1(sha1)?.takeIf { it.type == type } }
        val hit = byPackage ?: byCert ?: return null
        return listOfNotNull(
            ev("product", hit.name, IOC),
            if (byPackage != null) ev("matched", "package name ${c.facts.packageName}", IOC) else null,
            if (byCert != null) ev("matched", "signing certificate", IOC) else null,
        )
    }

    /**
     * The app's name is a brand's name, but the package isn't one of that brand's apps (and isn't from
     * the same publisher). Only for apps outside a reviewed store: store review already checks this,
     * and a TV or Lite edition of a real app would otherwise be flagged.
     */
    private fun impostor(c: AppContext): List<Evidence>? {
        if (c.reviewed) return null
        val normalized = c.facts.label.lowercase().filter { it.isLetterOrDigit() }
        if (normalized.length < 3) return null
        val brand = c.intel.brands.bySkeleton(skeleton(normalized, c.intel.confusables)).firstOrNull { it.packages.isNotEmpty() } ?: return null
        val pkg = c.facts.packageName
        val samePublisher = brand.packages.any { official -> pkg == official || pkg.startsWith(publisher(official) + ".") }
        if (samePublisher) return null
        return listOf(ev("app name", c.facts.label), ev("real app", brand.packages.sorted().joinToString(", "), "brand list"), ev("this package", pkg))
    }

    private fun publisher(pkg: String) = pkg.split('.').take(2).joinToString(".")
}

val Capability.label: String
    get() = when (this) {
        Capability.ACCESSIBILITY -> "Accessibility"
        Capability.DEVICE_ADMIN -> "device admin"
        Capability.NOTIFICATION_LISTENER -> "notification access"
        Capability.KEYBOARD -> "keyboard"
        Capability.SMS -> "SMS"
        Capability.OVERLAY -> "display over other apps"
        Capability.BACKGROUND_LOCATION -> "background location"
        Capability.USAGE_ACCESS -> "usage access"
        Capability.INSTALLS_APPS -> "install apps"
        Capability.VPN -> "VPN"
        Capability.CAMERA -> "camera"
        Capability.MICROPHONE -> "microphone"
        Capability.CONTACTS -> "contacts"
        Capability.CALL_LOG -> "call log"
    }

class AppAnalyzer(private val intel: AppIntel) {
    val rules = AppRules.create()

    fun analyze(facts: AppFacts, ctx: EvalContext): AppReport {
        val (provenance, storeName) = ProvenanceClassifier.classify(facts.install, intel.stores)
        val findings = rules.evaluate(AppContext(facts, provenance, intel), ctx)
        val score = Scoring.riskScore(findings)
        return AppReport(facts, provenance, storeName, findings, score, AppBand.of(score))
    }

    /** Riskiest first; ties by label then package, so the list order never depends on PackageManager order. */
    fun analyzeAll(apps: List<AppFacts>, ctx: EvalContext): List<AppReport> = apps.map { analyze(it, ctx) }
        .sortedWith(compareByDescending<AppReport> { it.score }.thenBy { it.facts.label.lowercase() }.thenBy { it.facts.packageName })
}
