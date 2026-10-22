package lian.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import lian.app.shield.ShieldListenerService

/** Deep links into the system screens where the user grants LIAN's optional access. LIAN never toggles them itself. */
object SystemSettings {
    /** Notification access for LIAN's Message Shield (straight to LIAN's own toggle where supported). */
    fun notificationAccess(context: Context) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ShieldListenerService.component(context).flattenToString())
        start(context, detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Usage access, so LIAN can see which apps worked in the background. */
    fun usageAccess(context: Context) {
        start(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri()), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /** LIAN's own app-info page (for notifications, permissions). */
    fun appDetails(context: Context, packageName: String = context.packageName) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    private fun start(context: Context, vararg candidates: Intent) {
        for (intent in candidates.toList() + Intent(Settings.ACTION_SETTINGS)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // try the next, more general screen
            }
        }
    }
}
