package lian.app.shield

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import lian.app.graph
import lian.core.engine.app.AppBand
import lian.core.engine.link.LinkBand
import java.util.concurrent.TimeUnit

/**
 * Message Shield: checks each new message notification the moment it arrives, entirely on the phone,
 * and warns only when it looks like a scam. Nothing is stored or sent; the text lives in memory just
 * long enough to be analysed (and, for warnings, to be shown when the user taps the alert).
 *
 * While connected it also runs the install guard: each newly installed app is audited immediately.
 */
class ShieldListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var installReceiver: BroadcastReceiver? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                val pkg = intent.data?.schemeSpecificPart ?: return
                val pending = goAsync()
                scope.launch {
                    try { checkInstall(pkg) } finally { pending.finish() }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        installReceiver = receiver
    }

    override fun onListenerDisconnected() {
        unregisterInstallReceiver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregisterInstallReceiver()
        scope.cancel()
        super.onDestroy()
    }

    private fun unregisterInstallReceiver() {
        installReceiver?.let { try { unregisterReceiver(it) } catch (_: IllegalArgumentException) { } }
        installReceiver = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val parts = parts(sbn.packageName, n)
        val defaultSms = try { Telephony.Sms.getDefaultSmsPackage(this) } catch (_: SecurityException) { null }
        if (!MessageExtractor.isMessage(parts, packageName, defaultSms)) return
        val text = MessageExtractor.text(parts) ?: return
        val graph = applicationContext.graph
        if (!graph.shield.firstSighting(parts.packageName, text)) return

        scope.launch {
            val ctx = graph.context()
            val result = graph.textAnalyzer.analyze(text, ctx)
            val warn = result.band >= LinkBand.LIKELY_SCAM
            graph.prefs.countShield(warn, ctx.nowEpochMs / TimeUnit.DAYS.toMillis(1))
            if (result.band >= LinkBand.CAUTION) {
                val label = appLabel(parts.packageName)
                val alert = graph.shield.addAlert(parts.packageName, label, MessageExtractor.sender(parts.title), text, result, ctx.nowEpochMs)
                if (warn) ShieldNotifications.scam(this@ShieldListenerService, label, alert.id)
            }
        }
    }

    private fun checkInstall(pkg: String) {
        val graph = applicationContext.graph
        val facts = graph.appInventory.collectOne(pkg) ?: return
        val report = graph.appAnalyzer.analyze(facts, graph.context())
        graph.shield.addInstall(pkg, report, System.currentTimeMillis())
        if (report.band >= AppBand.HIGH) ShieldNotifications.newApp(this)
    }

    private fun appLabel(pkg: String): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(pkg, 0)
        }
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        fun component(context: Context) = ComponentName(context, ShieldListenerService::class.java)

        /** True when the user has granted LIAN notification access. */
        fun enabled(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Copies the relevant extras into plain data. */
        fun parts(pkg: String, n: Notification): NotificationParts {
            val e: Bundle = n.extras ?: Bundle.EMPTY
            val messages = messagesOf(e)
            return NotificationParts(
                packageName = pkg,
                category = n.category,
                title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                bigText = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                messages = messages,
                groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            )
        }

        private fun messagesOf(e: Bundle): List<String> {
            val raw = if (Build.VERSION.SDK_INT >= 33) {
                e.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                e.getParcelableArray(Notification.EXTRA_MESSAGES)
            } ?: return emptyList()
            return raw.mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }
        }
    }
}
