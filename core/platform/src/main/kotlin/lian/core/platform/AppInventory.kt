package lian.core.platform

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.view.accessibility.AccessibilityManager
import lian.core.engine.app.AppFacts
import lian.core.engine.app.Capability
import lian.core.engine.app.InstallFacts
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Reads every installed app's facts for the audit (needs QUERY_ALL_PACKAGES, declared for Play as a
 * security app). The list is processed on the phone only; LIAN can't send it anywhere.
 *
 * Bulk queries are used wherever possible (one query for launcher entries, one per special-access
 * type) so the whole inventory costs a few dozen binder calls plus one per app.
 */
class AppInventory(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    fun collect(): List<AppFacts> = collect(installedPackages(), null)

    /** One app, e.g. right after it was installed (install guard). Null if it isn't visible or installed. */
    fun collectOne(packageName: String): AppFacts? = try {
        collect(listOf(packageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)), packageName).firstOrNull()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun collect(packages: List<PackageInfo>, single: String?): List<AppFacts> {
        val known = packages.associate { it.packageName to it.applicationInfo.isSystem() }
        val systemByPackage: (String) -> Boolean = { name ->
            known[name] ?: if (single == null) false else try { packageInfo(name, 0).applicationInfo.isSystem() } catch (_: PackageManager.NameNotFoundException) { false }
        }
        val launchers = launcherPackages()
        val enabledA11y = enabledAccessibility()
        val admins = context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty().map { it.packageName }.toSet()
        val listeners = enabledComponents("enabled_notification_listeners")
        val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }
        val defaultSms = try { Telephony.Sms.getDefaultSmsPackage(context) } catch (_: SecurityException) { null }
        val declared = declaredCapabilities()
        val appOps = context.getSystemService(AppOpsManager::class.java)

        val tasks = packages.filter { it.packageName != context.packageName }.map { info -> Callable {
            val app = info.applicationInfo
            val pkg = info.packageName
            val granted = grantedPermissions(info)
            val active = buildSet {
                if (pkg in enabledA11y) add(Capability.ACCESSIBILITY)
                if (pkg in admins) add(Capability.DEVICE_ADMIN)
                if (pkg in listeners) add(Capability.NOTIFICATION_LISTENER)
                if (pkg == keyboard) add(Capability.KEYBOARD)
                if (granted.any { it in SMS_PERMISSIONS }) add(Capability.SMS)
                if (Manifest.permission.ACCESS_BACKGROUND_LOCATION in granted) add(Capability.BACKGROUND_LOCATION)
                if (app != null && opAllowed(appOps, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.uid, pkg, Manifest.permission.SYSTEM_ALERT_WINDOW in granted)) add(Capability.OVERLAY)
                if (app != null && opAllowed(appOps, AppOpsManager.OPSTR_GET_USAGE_STATS, app.uid, pkg, false)) add(Capability.USAGE_ACCESS)
                if (Manifest.permission.CAMERA in granted) add(Capability.CAMERA)
                if (Manifest.permission.RECORD_AUDIO in granted) add(Capability.MICROPHONE)
                if (Manifest.permission.READ_CONTACTS in granted) add(Capability.CONTACTS)
                if (Manifest.permission.READ_CALL_LOG in granted) add(Capability.CALL_LOG)
            }
            val signers = signers(info)
            AppFacts(
                packageName = pkg,
                label = app?.loadLabel(pm)?.toString()?.take(100) ?: pkg,
                versionName = info.versionName?.take(50),
                targetSdk = app?.targetSdkVersion ?: 0,
                signerSha256 = signers.map { it.first },
                signerSha1 = signers.map { it.second },
                debugCertificate = signers.any { it.third },
                debuggable = app != null && app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                testOnly = app != null && app.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
                install = installFacts(pkg, app.isSystem(), systemByPackage),
                hasLauncherIcon = pkg in launchers,
                requestedPermissions = info.requestedPermissions.orEmpty().toSet(),
                active = active,
                declared = declared[pkg].orEmpty(),
                accessibilityTool = enabledA11y[pkg],
                defaultSmsApp = pkg == defaultSms,
            )
        } }
        // Labels and per-app binder calls dominate (Android loads each app's resources for its name):
        // four workers, as planned in Phase 2.3, keep the system's binder threads from being flooded.
        val pool = Executors.newFixedThreadPool(WORKERS)
        try {
            return pool.invokeAll(tasks).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /** Label of an installed app with exactly this name (case-insensitive), for the APK impostor check. */
    fun packageWithLabel(label: String, except: String): String? {
        val wanted = label.trim().lowercase()
        return installedPackages(0).firstOrNull { p ->
            p.packageName != except && p.applicationInfo?.loadLabel(pm)?.toString()?.trim()?.lowercase() == wanted
        }?.packageName
    }

    /** Signing-certificate SHA-256 history of an installed package, or null if it isn't installed. */
    fun signerHistory(packageName: String): List<String>? = try {
        val info = packageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return emptyList()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        certs.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun ApplicationInfo?.isSystem(): Boolean =
        this != null && (flags and ApplicationInfo.FLAG_SYSTEM != 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)

    private fun installedPackages(extra: Int = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(extra.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(extra)
        }

    private fun packageInfo(pkg: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }

    private fun launcherPackages(): Set<String> {
        fun query(category: String): List<ResolveInfo> {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            return if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
        }
        return (query(Intent.CATEGORY_LAUNCHER) + query(Intent.CATEGORY_LEANBACK_LAUNCHER)).map { it.activityInfo.packageName }.toSet()
    }

    /** Enabled Accessibility services → whether each declares itself an accessibility tool (API 31+). */
    private fun enabledAccessibility(): Map<String, Boolean> {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return emptyMap()
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).associate { s ->
            val pkg = s.resolveInfo.serviceInfo.packageName
            pkg to (Build.VERSION.SDK_INT >= 31 && s.isAccessibilityTool)
        }
    }

    /** Package names from a colon-separated component list in Settings.Secure (what NotificationManagerCompat reads). */
    private fun enabledComponents(key: String): Set<String> = try {
        Settings.Secure.getString(context.contentResolver, key).orEmpty().split(':')
            .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
    } catch (_: SecurityException) {
        emptySet()
    }

    /** Which apps declare a service or receiver that could be granted a takeover power. */
    private fun declaredCapabilities(): Map<String, Set<Capability>> {
        val out = HashMap<String, MutableSet<Capability>>()
        fun services(action: String, permission: String, capability: Capability) {
            val list = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(Intent(action), PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(Intent(action), PackageManager.MATCH_ALL)
            }
            list.filter { it.serviceInfo.permission == permission }.forEach { out.getOrPut(it.serviceInfo.packageName) { HashSet() } += capability }
        }
        services("android.accessibilityservice.AccessibilityService", Manifest.permission.BIND_ACCESSIBILITY_SERVICE, Capability.ACCESSIBILITY)
        services("android.service.notification.NotificationListenerService", Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, Capability.NOTIFICATION_LISTENER)
        services("android.view.InputMethod", Manifest.permission.BIND_INPUT_METHOD, Capability.KEYBOARD)
        services("android.net.VpnService", Manifest.permission.BIND_VPN_SERVICE, Capability.VPN)
        val admins = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryBroadcastReceivers(Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED), PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryBroadcastReceivers(Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED), PackageManager.MATCH_ALL)
        }
        admins.filter { it.activityInfo.permission == Manifest.permission.BIND_DEVICE_ADMIN }.forEach { out.getOrPut(it.activityInfo.packageName) { HashSet() } += Capability.DEVICE_ADMIN }
        return out
    }

    private fun grantedPermissions(info: PackageInfo): Set<String> {
        val names = info.requestedPermissions ?: return emptySet()
        val flags = info.requestedPermissionsFlags ?: return emptySet()
        return names.indices.filter { flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }.map { names[it] }.toSet()
    }

    /** App-op mode for another app; "default" falls back to whether the permission itself is granted. */
    @Suppress("DEPRECATION")   // unsafeCheckOpNoThrow: the replacement needs API 37; this covers minSdk 30
    private fun opAllowed(appOps: AppOpsManager?, op: String, uid: Int, pkg: String, permissionGranted: Boolean): Boolean {
        if (appOps == null) return false
        return try {
            when (appOps.unsafeCheckOpNoThrow(op, uid, pkg)) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_DEFAULT -> permissionGranted
                else -> false
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun installFacts(pkg: String, system: Boolean, systemByPackage: (String) -> Boolean): InstallFacts {
        val initiator = try { pm.getInstallSourceInfo(pkg).initiatingPackageName } catch (_: PackageManager.NameNotFoundException) { null }
        return InstallFacts(system, initiator, initiator != null && systemByPackage(initiator))
    }

    /** (SHA-256, SHA-1, is debug certificate) for each current signer. */
    private fun signers(info: PackageInfo): List<Triple<String, String, Boolean>> {
        val signing = info.signingInfo ?: return emptyList()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()
        return certs.orEmpty().map { sig ->
            val der = sig.toByteArray()
            Triple(
                hex(MessageDigest.getInstance("SHA-256").digest(der)),
                hex(MessageDigest.getInstance(SHA1).digest(der)),
                isDebugCertificate(der),
            )
        }
    }

    /**
     * The SDK debug certificate's subject is "CN=Android Debug, O=Android, C=US". Parsing every app's
     * certificate is slow on cold start, so the bytes are searched first and only a hit is parsed.
     */
    private fun isDebugCertificate(der: ByteArray): Boolean {
        if (!der.contains(DEBUG_CN)) return false
        return try {
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            "CN=Android Debug" in cert.subjectX500Principal.name
        } catch (_: Exception) {
            false
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private companion object {
        const val WORKERS = 4
        val DEBUG_CN = "Android Debug".toByteArray(Charsets.US_ASCII)
        val SMS_PERMISSIONS = setOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        const val SHA1 = "SHA-1" // privacy-gate: allow crypto.weak (lookup key for published stalkerware certificate lists, not integrity)

        fun hex(bytes: ByteArray): String {
            val h = "0123456789abcdef"
            return buildString(bytes.size * 2) { for (b in bytes) append(h[(b.toInt() shr 4) and 0xF]).append(h[b.toInt() and 0xF]) }
        }
    }
}
