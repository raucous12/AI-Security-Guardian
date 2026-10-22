package lian.core.platform

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import lian.core.engine.device.AttestationFacts
import lian.core.engine.device.AttestationTrust
import lian.core.engine.device.AttestationVerifier
import lian.core.engine.device.DeviceFacts
import lian.core.engine.device.Encryption
import lian.core.engine.device.PrivateDns
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Reads device security facts from public Android APIs (Phase 3.6). Every read that can fail returns
 * null / UNKNOWN rather than a guess, and nothing here needs a dangerous permission.
 */
class DeviceProbe(private val context: Context, private val trust: AttestationTrust) {

    @Volatile private var cachedAttestation: AttestationFacts? = null

    fun collect(): DeviceFacts {
        val cr = context.contentResolver
        // The slow reads (attestation key generation, the CA store's first load, package lookups) overlap.
        val pool = Executors.newFixedThreadPool(3)
        try {
            val cas = pool.submit(Callable { userCaCount() })
            val roots = pool.submit(Callable { rootIndicators() })
            val attested = pool.submit(Callable { attestation() })
            return facts(cr, cas.get(), roots.get(), attested.get())
        } finally {
            pool.shutdown()
        }
    }

    private fun facts(cr: android.content.ContentResolver, cas: Int?, roots: List<String>, attested: AttestationFacts): DeviceFacts {
        return DeviceFacts(
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
            screenLockSecure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure,
            encryption = encryption(),
            adbEnabled = globalFlag(cr, Settings.Global.ADB_ENABLED),
            wirelessAdbEnabled = globalFlag(cr, "adb_wifi_enabled"),
            developerOptions = globalFlag(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            userCaCount = cas,
            rootIndicators = roots,
            attestation = attested,
            privateDns = privateDns(),
            vpnActive = vpnActive(),
            advancedProtection = advancedProtection(),
        )
    }

    private fun globalFlag(cr: android.content.ContentResolver, key: String): Boolean? = try {
        Settings.Global.getString(cr, key)?.let { it == "1" } ?: false
    } catch (_: SecurityException) {
        null
    }

    private fun encryption(): Encryption = when (context.getSystemService(DevicePolicyManager::class.java)?.storageEncryptionStatus) {
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER, DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> Encryption.PER_USER
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> Encryption.DEFAULT_KEY
        DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE, DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> Encryption.NONE
        else -> Encryption.UNKNOWN
    }

    /** User-installed CA certificates (they let whoever installed them read TLS traffic). */
    private fun userCaCount(): Int? = try {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        store.aliases().toList().count { it.startsWith("user:") }
    } catch (_: Exception) {
        null
    }

    /** Signs of root. Absence proves nothing (root can hide), so the finding is marked INDICATOR. */
    private fun rootIndicators(): List<String> {
        val out = ArrayList<String>()
        SU_PATHS.firstOrNull { File(it).exists() }?.let { out += "su binary at $it" }
        if (Build.TAGS?.contains("test-keys") == true) out += "built with test keys"
        val pm = context.packageManager
        ROOT_APPS.firstOrNull { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
        }?.let { out += "root manager app installed ($it)" }
        return out
    }

    /**
     * Hardware key attestation: generate a throwaway EC key with a fresh challenge, read its
     * certificate chain, delete the key. Cached for the process lifetime (boot state can't change
     * without a reboot, which ends the process).
     */
    fun attestation(): AttestationFacts {
        cachedAttestation?.let { return it }
        val alias = "lian.attest." + System.nanoTime()
        val keyStore = try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } } catch (_: Exception) { return AttestationFacts.unavailable("Keystore unavailable") }
        val result = try {
            val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            val chain = keyStore.getCertificateChain(alias)?.map { it.encoded }.orEmpty()
            AttestationVerifier.verify(chain, challenge, trust)
        } catch (_: GeneralSecurityException) {
            AttestationFacts.unavailable("attestation not supported")
        } catch (_: ProviderException) {
            AttestationFacts.unavailable("attestation not supported")
        } finally {
            try { keyStore.deleteEntry(alias) } catch (_: Exception) { }
        }
        cachedAttestation = result
        return result
    }

    private fun privateDns(): PrivateDns {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return PrivateDns.UNKNOWN
        val props = try { cm.getLinkProperties(cm.activeNetwork) } catch (_: SecurityException) { null } ?: return PrivateDns.UNKNOWN
        return when {
            props.privateDnsServerName != null -> PrivateDns.STRICT
            props.isPrivateDnsActive -> PrivateDns.OPPORTUNISTIC
            else -> PrivateDns.OFF
        }
    }

    private fun vpnActive(): Boolean? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
        } catch (_: SecurityException) {
            null
        }
    }

    private fun advancedProtection(): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled
        } catch (_: SecurityException) {
            null
        }
    }

    private companion object {
        val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/system/bin/.ext/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/system/sd/xbin/su", "/su/bin/su", "/data/adb/magisk", "/data/adb/ksu",
        )
        val ROOT_APPS = listOf(
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu", "me.bmax.apatch",
            "eu.chainfire.supersu", "com.koushikdutta.superuser", "com.noshufou.android.su", "com.kingroot.kinguser",
        )
    }
}
