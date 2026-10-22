package lian.core.engine.apk

import lian.core.engine.Confidence
import lian.core.engine.EvalContext
import lian.core.engine.Evidence
import lian.core.engine.Finding
import lian.core.engine.Rule
import lian.core.engine.RuleId
import lian.core.engine.RuleSet
import lian.core.engine.Scoring
import lian.core.engine.Severity
import lian.core.engine.app.AppBand
import lian.core.engine.app.AppIntel
import lian.core.engine.app.Capability
import lian.core.engine.app.Indicator
import lian.core.engine.app.label
import lian.core.engine.link.skeleton

/** What's already on the phone that an APK could collide with, read by the platform before the rules run. */
data class InstalledContext(
    /** Signing-certificate SHA-256 history of an installed app with the same package name; null if not installed. */
    val samePackageSigners: List<String>?,
    /** Package of an installed app with exactly the same label (case-insensitive), if any. */
    val sameLabelPackage: String?,
)

data class ApkInput(val facts: ApkFacts, val installed: InstalledContext, val intel: AppIntel)

data class ApkReport(val facts: ApkFacts, val findings: List<Finding>, val score: Int, val band: AppBand, val ruleSetVersion: String)

class ApkRule(
    id: String,
    override val version: Int,
    private val severity: Severity,
    private val weight: Int,
    private val confidence: Confidence,
    private val group: String? = null,
    private val test: (ApkInput) -> List<Evidence>?,
) : Rule<ApkInput> {
    override val id = RuleId(id)
    override fun evaluate(facts: ApkInput, ctx: EvalContext): Finding? =
        test(facts)?.let { Finding(this.id, version, severity, weight, confidence, it, correlationGroup = group) }
}

/** Phase 3.5: APK-only rules plus the app rules applied to what the file *declares* (half weight: nothing is active yet). */
object ApkRules {
    const val VERSION = "apk-2026.10.1"
    private const val FILE = "APK file"
    private const val SIG = "APK signature"
    private const val IOC = "stalkerware indicators (Echap)"

    private val SMS = setOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS")
    private val CALLS = setOf("android.permission.READ_CALL_LOG", "android.permission.PROCESS_OUTGOING_CALLS", "android.permission.ANSWER_PHONE_CALLS")

    private fun ev(key: String, value: String, source: String = FILE) = Evidence(key, value.take(160), source)
    private fun ApkInput.has(vararg p: String) = p.any { it in facts.permissions }
    private fun ApkInput.hasAny(set: Set<String>) = facts.permissions.any { it in set }

    fun create(): RuleSet<ApkInput> = RuleSet(
        VERSION,
        listOf(
            ApkRule("apk.ioc.stalkerware", 1, Severity.CRITICAL, 95, Confidence.CERTAIN) { i -> indicator(i, Indicator.Type.STALKERWARE) },
            ApkRule("apk.sig.invalid", 1, Severity.CRITICAL, 95, Confidence.CERTAIN, group = "sig") { i ->
                val s = i.facts.signing
                if (s.verified == false && s.schemes.isNotEmpty()) listOf(ev("problem", s.problem ?: "signature doesn't verify", SIG)) else null
            },
            ApkRule("apk.update.signer_mismatch", 1, Severity.CRITICAL, 90, Confidence.CERTAIN) { i ->
                val installed = i.installed.samePackageSigners ?: return@ApkRule null
                val certs = i.facts.signing.certificates.map { it.sha256 }
                if (certs.isNotEmpty() && installed.isNotEmpty() && certs.none { it in installed }) {
                    listOf(ev("installed app", i.facts.packageName, "PackageManager"), ev("problem", "signed by someone else: Android would refuse it as an update", SIG))
                } else null
            },
            ApkRule("apk.impostor.label", 1, Severity.CRITICAL, 85, Confidence.LIKELY) { i -> impostor(i) },
            ApkRule("apk.combo.trojan", 1, Severity.HIGH, 60, Confidence.LIKELY, group = "combo") { i ->
                val partners = listOfNotNull(
                    "SMS".takeIf { i.hasAny(SMS) },
                    "display over other apps".takeIf { i.has("android.permission.SYSTEM_ALERT_WINDOW") },
                    "notification access".takeIf { Capability.NOTIFICATION_LISTENER in i.facts.declared },
                )
                if (Capability.ACCESSIBILITY in i.facts.declared && partners.isNotEmpty()) listOf(ev("asks for", (listOf("Accessibility") + partners).joinToString(" + "))) else null
            },
            ApkRule("apk.sig.none", 1, Severity.HIGH, 60, Confidence.CERTAIN, group = "sig") { i ->
                if (i.facts.signing.schemes.isEmpty()) listOf(ev("signature", "none: Android won't install it as is", SIG)) else null
            },
            ApkRule("apk.stealth", 1, Severity.HIGH, 50, Confidence.LIKELY, group = "stealth") { i ->
                val monitoring = i.facts.declared - Capability.VPN
                if (!i.facts.hasLauncher && monitoring.isNotEmpty()) listOf(ev("launcher icon", "none"), ev("declares", monitoring.sorted().joinToString(", ") { it.label })) else null
            },
            ApkRule("apk.ioc.watchware", 1, Severity.MEDIUM, 45, Confidence.CERTAIN) { i -> indicator(i, Indicator.Type.WATCHWARE) },
            ApkRule("apk.sdk.legacy", 1, Severity.MEDIUM, 30, Confidence.CERTAIN, group = "sdk") { i ->
                i.facts.targetSdk?.takeIf { it in 1..22 }?.let { listOf(ev("target SDK", "$it: gets permissions without asking")) }
            },
            ApkRule("apk.declares.a11y", 1, Severity.MEDIUM, 28, Confidence.CERTAIN) { i -> declares(i, Capability.ACCESSIBILITY) },
            ApkRule("apk.sms", 1, Severity.MEDIUM, 25, Confidence.CERTAIN) { i ->
                if (i.hasAny(SMS)) listOf(ev("requests", i.facts.permissions.filter { it in SMS }.joinToString(", ") { it.substringAfterLast('.') })) else null
            },
            ApkRule("apk.declares.ime", 1, Severity.MEDIUM, 25, Confidence.CERTAIN) { i -> declares(i, Capability.KEYBOARD) },
            ApkRule("apk.build.debug", 1, Severity.MEDIUM, 25, Confidence.CERTAIN, group = "build") { i ->
                when {
                    i.facts.signing.certificates.any { it.isDebug } -> listOf(ev("signed with", "the Android debug certificate", SIG))
                    i.facts.debuggable -> listOf(ev("flag", "debuggable"))
                    else -> null
                }
            },
            ApkRule("apk.sig.v1_only", 1, Severity.MEDIUM, 25, Confidence.CERTAIN, group = "sig") { i ->
                val s = i.facts.signing
                if (s.schemes == setOf(1) && (i.facts.minSdk ?: 1) >= 24) listOf(ev("signature", "old JAR (v1) scheme only", SIG)) else null
            },
            ApkRule("apk.declares.admin", 1, Severity.MEDIUM, 23, Confidence.CERTAIN) { i -> declares(i, Capability.DEVICE_ADMIN) },
            ApkRule("apk.declares.listener", 1, Severity.MEDIUM, 18, Confidence.CERTAIN) { i -> declares(i, Capability.NOTIFICATION_LISTENER) },
            ApkRule("apk.calls", 1, Severity.LOW, 15, Confidence.CERTAIN) { i ->
                if (i.hasAny(CALLS)) listOf(ev("requests", i.facts.permissions.filter { it in CALLS }.joinToString(", ") { it.substringAfterLast('.') })) else null
            },
            ApkRule("apk.overlay", 1, Severity.LOW, 15, Confidence.CERTAIN) { i ->
                if (i.has("android.permission.SYSTEM_ALERT_WINDOW")) listOf(ev("requests", "display over other apps")) else null
            },
            ApkRule("apk.installs_apps", 1, Severity.LOW, 15, Confidence.CERTAIN) { i ->
                if (i.has("android.permission.REQUEST_INSTALL_PACKAGES")) listOf(ev("requests", "installing other apps")) else null
            },
            ApkRule("apk.build.test_only", 1, Severity.LOW, 15, Confidence.CERTAIN, group = "build") { i ->
                if (i.facts.testOnly) listOf(ev("flag", "testOnly")) else null
            },
            ApkRule("apk.sdk.old", 1, Severity.LOW, 12, Confidence.CERTAIN, group = "sdk") { i ->
                i.facts.targetSdk?.takeIf { it in 23..28 }?.let { listOf(ev("target SDK", "$it (built for Android 9 or older)")) }
            },
            ApkRule("apk.declares.vpn", 1, Severity.INFO, 0, Confidence.CERTAIN) { i -> declares(i, Capability.VPN) },
            ApkRule("apk.sig.verified", 1, Severity.INFO, 0, Confidence.CERTAIN) { i ->
                val s = i.facts.signing
                if (s.verified == true) listOf(ev("schemes", s.schemes.sorted().joinToString(", ") { if (it == 31) "v3.1" else "v$it" }, SIG)) else null
            },
        ),
    )

    private fun declares(i: ApkInput, c: Capability) =
        if (c in i.facts.declared) listOf(ev("declares a service for", c.label)) else null

    private fun indicator(i: ApkInput, type: Indicator.Type): List<Evidence>? {
        val set = i.intel.indicators
        val byPackage = set.byPackage(i.facts.packageName)?.takeIf { it.type == type }
        val byCert = i.facts.signing.certificates.firstNotNullOfOrNull { c -> set.byCertSha1(c.sha1)?.takeIf { it.type == type } }
        val hit = byPackage ?: byCert ?: return null
        return listOfNotNull(
            ev("product", hit.name, IOC),
            if (byPackage != null) ev("matched", "package name", IOC) else null,
            if (byCert != null) ev("matched", "signing certificate", IOC) else null,
        )
    }

    private fun impostor(i: ApkInput): List<Evidence>? {
        val label = i.facts.label ?: return null
        i.installed.sameLabelPackage?.takeIf { it != i.facts.packageName }?.let {
            return listOf(ev("app name", label), ev("same name as installed app", it, "PackageManager"), ev("this package", i.facts.packageName))
        }
        val normalized = label.lowercase().filter { it.isLetterOrDigit() }
        if (normalized.length < 3) return null
        val brand = i.intel.brands.bySkeleton(skeleton(normalized, i.intel.confusables)).firstOrNull { it.packages.isNotEmpty() } ?: return null
        val pkg = i.facts.packageName
        if (brand.packages.any { pkg == it || pkg.startsWith(it.split('.').take(2).joinToString(".") + ".") }) return null
        return listOf(ev("app name", label), ev("real app", brand.packages.sorted().joinToString(", "), "brand list"), ev("this package", pkg))
    }
}

class ApkAnalyzer(private val intel: AppIntel) {
    val rules = ApkRules.create()

    fun analyze(facts: ApkFacts, installed: InstalledContext, ctx: EvalContext): ApkReport {
        val findings = rules.evaluate(ApkInput(facts, installed, intel), ctx)
        val score = Scoring.riskScore(findings)
        return ApkReport(facts, findings, score, AppBand.of(score), rules.version)
    }
}
