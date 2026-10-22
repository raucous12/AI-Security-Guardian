package lian.app

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import lian.core.engine.EvalContext
import lian.core.engine.apk.ApkFacts
import lian.core.engine.apk.ApkParser
import lian.core.engine.apk.ByteArraySource
import lian.core.engine.device.DeviceChecks
import lian.core.intel.IntelPack
import lian.core.platform.AppInventory
import lian.core.platform.DeviceProbe
import lian.core.platform.untrusted.UntrustedClient
import java.io.File
import java.security.MessageDigest

/** Phase 4: the new features exercised on a real Android runtime. */
@RunWith(AndroidJUnit4::class)
class OnDeviceFeaturesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownApk = File(context.applicationInfo.sourceDir)

    @Suppress("DEPRECATION")
    private val ownInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun apkParserAgreesWithPackageManagerOnLiansOwnApk() {
        val facts = ApkParser.parse(ByteArraySource(ownApk.readBytes()))
        assertEquals(context.packageName, facts.packageName)
        assertEquals(ownInfo.requestedPermissions.orEmpty().sorted(), facts.permissions)
        assertEquals(ownInfo.applicationInfo?.targetSdkVersion, facts.targetSdk)
        assertTrue(facts.hasLauncher)
        // v2/v3 signature and whole-file digest verified by LIAN's own code, and the same certificate Android reports.
        assertEquals("signing: ${facts.signing}", true, facts.signing.verified)
        val androidSigner = ownInfo.signingInfo!!.apkContentsSigners.map { sha256(it.toByteArray()) }
        assertEquals(androidSigner, facts.signing.certificates.map { it.sha256 })
        assertEquals("LIAN", facts.label)
    }

    @Test
    fun isolatedProcessParsesTheApkAndIsReallyIsolated() {
        val client = UntrustedClient(context)
        val outcome = ParcelFileDescriptor.open(ownApk, ParcelFileDescriptor.MODE_READ_ONLY).use { client.parseApk(it) }
        assertTrue("outcome $outcome", outcome is UntrustedClient.Outcome.Ok)
        assertEquals(context.packageName, ApkFacts.decode((outcome as UntrustedClient.Outcome.Ok).text).packageName)

        val self = client.selfTest()
        assertTrue("self test $self", self is UntrustedClient.Outcome.Ok)
        val text = (self as UntrustedClient.Outcome.Ok).text
        assertTrue(text, "isolated=true" in text)
        assertTrue(text, "canReadAppFiles=false" in text)
        assertFalse(text, "uid=${android.os.Process.myUid()} " in "$text ")
    }

    @Test
    fun hostileFileIsRefusedWithoutCrashingTheApp() {
        val junk = File(context.cacheDir, "junk.apk").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        val outcome = ParcelFileDescriptor.open(junk, ParcelFileDescriptor.MODE_READ_ONLY).use { UntrustedClient(context).parseApk(it) }
        junk.delete()
        assertTrue("outcome $outcome", outcome is UntrustedClient.Outcome.Failed)
    }

    @Test
    fun deviceAndAppCollectorsRunOnARealSystem() {
        val intel = IntelPack.load()
        val facts = DeviceProbe(context, intel).collect()
        val report = DeviceChecks.evaluate(facts, EvalContext(nowEpochMs = System.currentTimeMillis()), intel::osSupport)
        assertTrue(report.score in 0..100)
        assertNotNull(facts.securityPatch)

        val apps = AppInventory(context).collect()
        assertTrue("only ${apps.size} apps visible", apps.size > 20)                   // QUERY_ALL_PACKAGES works
        assertTrue(apps.any { it.packageName == "com.android.settings" && it.install.isSystemApp })
        assertTrue(apps.none { it.packageName == context.packageName })                  // LIAN doesn't audit itself
        assertTrue(apps.all { it.signerSha256.all { s -> s.length == 64 } })
    }
}
