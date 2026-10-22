package lian.core.engine.device

import lian.core.engine.EvalContext
import lian.core.engine.Evidence
import lian.core.engine.FixTarget
import lian.core.engine.RuleId
import lian.core.engine.app.OsSupport

enum class Encryption { PER_USER, DEFAULT_KEY, NONE, UNKNOWN }

enum class PrivateDns { STRICT, OPPORTUNISTIC, OFF, UNKNOWN }

/** Device security facts read by :core:platform. Null means "couldn't be read", never "fine". */
data class DeviceFacts(
    val sdkInt: Int,
    val release: String,
    val manufacturer: String,
    val model: String,
    /** `Build.VERSION.SECURITY_PATCH`, "YYYY-MM-DD". */
    val securityPatch: String?,
    val screenLockSecure: Boolean?,
    val encryption: Encryption,
    val adbEnabled: Boolean?,
    val wirelessAdbEnabled: Boolean?,
    val developerOptions: Boolean?,
    val userCaCount: Int?,
    val rootIndicators: List<String>,
    val attestation: AttestationFacts,
    val privateDns: PrivateDns,
    val vpnActive: Boolean?,
    val advancedProtection: Boolean?,
)

enum class CheckStatus { PASS, WARN, FAIL, UNKNOWN, INFO }

data class CheckResult(
    val id: RuleId,
    val status: CheckStatus,
    /** Penalty if FAIL; WARN costs half (rounded up). */
    val weight: Int,
    val group: String,
    val evidence: List<Evidence>,
    val fix: FixTarget?,
)

enum class PostureBand {
    STRONG, GOOD, NEEDS_WORK, AT_RISK;

    companion object {
        fun of(score: Int) = when {
            score >= 90 -> STRONG
            score >= 70 -> GOOD
            score >= 50 -> NEEDS_WORK
            else -> AT_RISK
        }
    }
}

data class PostureReport(val checks: List<CheckResult>, val score: Int, val band: PostureBand, val ruleSetVersion: String)

/**
 * Device posture (Phase 3.6): each check is PASS / WARN / FAIL / UNKNOWN, with a human-set weight.
 * Score = 100 − Σ penalties, one penalty per group (FAIL = weight, WARN = ⌈weight/2⌉). UNKNOWN never
 * scores in either direction: LIAN doesn't claim what it couldn't read.
 */
object DeviceChecks {
    const val VERSION = "device-2026.10.1"
    private const val DAY_MS = 86_400_000L
    private const val BUILD = "Build"
    private const val SETTINGS = "Settings.Global"
    private const val ATTEST = "key attestation"

    fun evaluate(f: DeviceFacts, ctx: EvalContext, osSupport: (Int) -> Pair<OsSupport, String>): PostureReport {
        val checks = listOf(
            osVersion(f, osSupport),
            patchAge(f, ctx),
            screenLock(f),
            bootState(f),
            root(f),
            encryption(f),
            userCas(f),
            usbDebugging(f),
            wirelessDebugging(f),
            developerOptions(f),
            privateDns(f),
            vpn(f),
            advancedProtection(f),
        )
        val ordered = checks.sortedWith(compareBy<CheckResult> { ORDER.getValue(it.status) }.thenByDescending { it.weight }.thenBy { it.id.value })
        val score = score(checks)
        return PostureReport(ordered, score, PostureBand.of(score), VERSION)
    }

    fun score(checks: List<CheckResult>): Int = (100 - checks.groupBy { it.group }.values.sumOf { group ->
        group.maxOf { c -> when (c.status) { CheckStatus.FAIL -> c.weight; CheckStatus.WARN -> (c.weight + 1) / 2; else -> 0 } }
    }).coerceIn(0, 100)

    private val ORDER = mapOf(CheckStatus.FAIL to 0, CheckStatus.WARN to 1, CheckStatus.UNKNOWN to 2, CheckStatus.INFO to 3, CheckStatus.PASS to 4)

    private fun check(id: String, status: CheckStatus, weight: Int, group: String, fix: FixTarget?, vararg evidence: Evidence) =
        CheckResult(RuleId(id), status, weight, group, evidence.toList(), fix)

    private fun ev(key: String, value: String, source: String) = Evidence(key, value, source)

    private fun osVersion(f: DeviceFacts, osSupport: (Int) -> Pair<OsSupport, String>): CheckResult {
        val (status, name) = osSupport(f.sdkInt)
        val e = ev("version", "$name (API ${f.sdkInt})", "AOSP security bulletins")
        return when (status) {
            OsSupport.ENDED -> check("device.os.unsupported", CheckStatus.FAIL, 15, "os", FixTarget.SYSTEM_UPDATE, e)
            OsSupport.ENDING -> check("device.os.ending", CheckStatus.WARN, 10, "os", FixTarget.SYSTEM_UPDATE, e)
            OsSupport.SUPPORTED -> check("device.os.supported", CheckStatus.PASS, 15, "os", null, e)
        }
    }

    private fun patchAge(f: DeviceFacts, ctx: EvalContext): CheckResult {
        val unknown = check("device.patch.unknown", CheckStatus.UNKNOWN, 20, "patch", FixTarget.SYSTEM_UPDATE)
        val patch = f.securityPatch ?: return unknown
        val patchDay = epochDay(patch) ?: return unknown
        if (ctx.nowEpochMs <= 0) return unknown
        val attested = f.attestation.takeIf { it.verdict == AttestationFacts.Verdict.VERIFIED_HARDWARE }?.osPatchLevel
        val age = (ctx.nowEpochMs / DAY_MS - patchDay).toInt()
        val e = mutableListOf(ev("security patch", patch, BUILD), ev("age", "$age days", BUILD))
        // The reported patch level is just a string the OS sets; secure hardware's own record can contradict it.
        val reportedYm = patch.take(7).replace("-", "").toIntOrNull()
        if (attested != null && reportedYm != null && reportedYm > attested) {
            e += ev("secure hardware says", "${attested / 100}-${(attested % 100).toString().padStart(2, '0')}", ATTEST)
            return check("device.patch.mismatch", CheckStatus.WARN, 20, "patch", FixTarget.SYSTEM_UPDATE, *e.toTypedArray())
        }
        return when {
            age > 180 -> check("device.patch.old", CheckStatus.FAIL, 20, "patch", FixTarget.SYSTEM_UPDATE, *e.toTypedArray())
            age > 90 -> check("device.patch.aging", CheckStatus.WARN, 20, "patch", FixTarget.SYSTEM_UPDATE, *e.toTypedArray())
            else -> check("device.patch.recent", CheckStatus.PASS, 20, "patch", null, *e.toTypedArray())
        }
    }

    private fun screenLock(f: DeviceFacts) = when (f.screenLockSecure) {
        false -> check("device.lock.none", CheckStatus.FAIL, 20, "lock", FixTarget.SECURITY, ev("screen lock", "none or swipe", "KeyguardManager"))
        true -> check("device.lock.set", CheckStatus.PASS, 20, "lock", null, ev("screen lock", "PIN, pattern or password", "KeyguardManager"))
        null -> check("device.lock.unknown", CheckStatus.UNKNOWN, 20, "lock", FixTarget.SECURITY)
    }

    private fun bootState(f: DeviceFacts): CheckResult {
        val a = f.attestation
        val detail = ev("attestation", a.detail, ATTEST)
        return when (a.verdict) {
            AttestationFacts.Verdict.REVOKED -> check("device.attest.revoked", CheckStatus.WARN, 20, "boot", null, detail)
            AttestationFacts.Verdict.UNVERIFIED, AttestationFacts.Verdict.UNAVAILABLE -> check("device.boot.unknown", CheckStatus.UNKNOWN, 20, "boot", null, detail)
            AttestationFacts.Verdict.VERIFIED_HARDWARE -> {
                val state = when (a.verifiedBootState) { 0 -> "verified (green)"; 1 -> "self-signed (yellow)"; 2 -> "unverified (orange)"; 3 -> "failed (red)"; else -> "unknown" }
                val e = arrayOf(detail, ev("bootloader", if (a.deviceLocked == true) "locked" else "unlocked", ATTEST), ev("verified boot", state, ATTEST))
                when {
                    a.verifiedBootState == 3 -> check("device.boot.failed", CheckStatus.FAIL, 25, "boot", null, *e)
                    a.deviceLocked == false || a.verifiedBootState == 2 -> check("device.boot.unlocked", CheckStatus.FAIL, 20, "boot", null, *e)
                    a.verifiedBootState == 0 || a.verifiedBootState == 1 -> check("device.boot.verified", CheckStatus.PASS, 20, "boot", null, *e)
                    else -> check("device.boot.unknown", CheckStatus.UNKNOWN, 20, "boot", null, *e)
                }
            }
        }
    }

    private fun root(f: DeviceFacts) = if (f.rootIndicators.isNotEmpty()) {
        check("device.root.signs", CheckStatus.FAIL, 25, "root", null, *f.rootIndicators.take(4).map { ev("sign", it, "file system / packages") }.toTypedArray())
    } else {
        check("device.root.none", CheckStatus.PASS, 25, "root", null, ev("checked", "su binaries, root-manager apps, build keys", "file system / packages"))
    }

    private fun encryption(f: DeviceFacts) = when (f.encryption) {
        Encryption.PER_USER -> check("device.encryption.on", CheckStatus.PASS, 20, "enc", null, ev("storage", "encrypted (file-based, per user)", "DevicePolicyManager"))
        Encryption.DEFAULT_KEY -> check("device.encryption.default_key", CheckStatus.WARN, 10, "enc", FixTarget.SECURITY, ev("storage", "encrypted with a default key", "DevicePolicyManager"))
        Encryption.NONE -> check("device.encryption.off", CheckStatus.FAIL, 20, "enc", FixTarget.SECURITY, ev("storage", "not encrypted", "DevicePolicyManager"))
        Encryption.UNKNOWN -> check("device.encryption.unknown", CheckStatus.UNKNOWN, 20, "enc", null)
    }

    private fun userCas(f: DeviceFacts) = when {
        f.userCaCount == null -> check("device.ca.unknown", CheckStatus.UNKNOWN, 12, "ca", null)
        f.userCaCount > 0 -> check("device.ca.user", CheckStatus.WARN, 12, "ca", FixTarget.SECURITY, ev("user-installed certificates", f.userCaCount.toString(), "AndroidCAStore"))
        else -> check("device.ca.none", CheckStatus.PASS, 12, "ca", null, ev("user-installed certificates", "0", "AndroidCAStore"))
    }

    private fun usbDebugging(f: DeviceFacts) = when (f.adbEnabled) {
        true -> check("device.dev.usb_debugging", CheckStatus.WARN, 10, "developer", FixTarget.DEVELOPER, ev("USB debugging", "on", SETTINGS))
        false -> check("device.dev.usb_debugging_off", CheckStatus.PASS, 10, "developer", null, ev("USB debugging", "off", SETTINGS))
        null -> check("device.dev.usb_debugging_unknown", CheckStatus.UNKNOWN, 10, "developer", null)
    }

    private fun wirelessDebugging(f: DeviceFacts) = when (f.wirelessAdbEnabled) {
        true -> check("device.dev.wireless_debugging", CheckStatus.WARN, 10, "developer", FixTarget.DEVELOPER, ev("wireless debugging", "on", SETTINGS))
        else -> check("device.dev.wireless_debugging_off", if (f.wirelessAdbEnabled == false) CheckStatus.PASS else CheckStatus.UNKNOWN, 10, "developer", null)
    }

    private fun developerOptions(f: DeviceFacts) = when (f.developerOptions) {
        true -> check("device.dev.options", CheckStatus.WARN, 5, "developer", FixTarget.DEVELOPER, ev("developer options", "on", SETTINGS))
        false -> check("device.dev.options_off", CheckStatus.PASS, 5, "developer", null, ev("developer options", "off", SETTINGS))
        null -> check("device.dev.options_unknown", CheckStatus.UNKNOWN, 5, "developer", null)
    }

    private fun privateDns(f: DeviceFacts) = when (f.privateDns) {
        PrivateDns.STRICT -> check("device.dns.private", CheckStatus.PASS, 3, "dns", null, ev("Private DNS", "on (provider set)", "ConnectivityManager"))
        PrivateDns.OPPORTUNISTIC -> check("device.dns.automatic", CheckStatus.PASS, 3, "dns", null, ev("Private DNS", "automatic (encrypted)", "ConnectivityManager"))
        PrivateDns.OFF -> check("device.dns.off", CheckStatus.WARN, 3, "dns", FixTarget.NETWORK, ev("Private DNS", "off or not available on this network", "ConnectivityManager"))
        PrivateDns.UNKNOWN -> check("device.dns.unknown", CheckStatus.UNKNOWN, 3, "dns", null, ev("Private DNS", "no active network", "ConnectivityManager"))
    }

    private fun vpn(f: DeviceFacts) = check(
        if (f.vpnActive == true) "device.vpn.on" else "device.vpn.off", CheckStatus.INFO, 0, "vpn",
        if (f.vpnActive == true) FixTarget.VPN else null,
        ev("VPN", when (f.vpnActive) { true -> "active: a VPN app can see your traffic's destinations"; false -> "not active"; null -> "unknown" }, "ConnectivityManager"),
    )

    private fun advancedProtection(f: DeviceFacts) = check(
        "device.advanced_protection", CheckStatus.INFO, 0, "advanced", null,
        ev("Advanced Protection", when (f.advancedProtection) { true -> "on"; false -> "off (recommended for people at high risk)"; null -> "not available on this Android version" }, "AdvancedProtectionManager"),
    )

    /** "YYYY-MM-DD" → days since 1970-01-01 (proleptic Gregorian), without java.time's zone rules. */
    internal fun epochDay(date: String): Long? {
        val parts = date.trim().split('-')
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (y !in 2000..2100 || m !in 1..12 || d !in 1..31) return null
        // Howard Hinnant's days_from_civil.
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
