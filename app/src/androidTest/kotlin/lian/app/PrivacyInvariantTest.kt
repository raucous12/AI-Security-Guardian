package lian.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import lian.core.platform.PrivacyProbe

/** Phase 4: the privacy guarantee checked on a real Android runtime, not just in the build. */
@RunWith(AndroidJUnit4::class)
class PrivacyInvariantTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun doesNotRequestInternet() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(Manifest.permission.INTERNET in info.requestedPermissions.orEmpty())
    }

    @Test
    fun kernelRefusesIpSockets() {
        val report = PrivacyProbe(context, emptySet()).run()
        assertTrue("socket was not refused: ${report.socket}", report.socket is PrivacyProbe.SocketTest.BlockedByAndroid)
        assertFalse(report.internetRequested)
    }

    @Test
    fun onlyTheLauncherActivityIsExported() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS,
        )
        // Components guarded by DUMP (shell/system only, e.g. ProfileInstallReceiver) are not reachable by apps.
        val exported = buildList {
            (info.activities.orEmpty().toList() + info.receivers.orEmpty().toList())
                .filter { it.exported && it.permission != Manifest.permission.DUMP }.forEach { add(it.name) }
            info.services.orEmpty().filter { it.exported && it.permission !in SYSTEM_ONLY }.forEach { add(it.name) }
            info.providers.orEmpty().filter { it.exported }.forEach { add(it.name) }
        }
        assertEquals(listOf("lian.app.MainActivity"), exported)
    }

    @Test
    fun systemBoundServicesRequireTheirBindPermission() {
        @Suppress("DEPRECATION")
        val services = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty().associateBy { it.name }
        assertEquals(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, services["lian.app.shield.ShieldListenerService"]?.permission)
    }

    private companion object {
        /** Permissions only the system holds: services guarded by them can't be bound by other apps. */
        val SYSTEM_ONLY = setOf(Manifest.permission.DUMP, Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE)
    }
}
