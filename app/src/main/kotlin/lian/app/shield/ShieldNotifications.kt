package lian.app.shield

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import lian.app.Inbound
import lian.app.MainActivity
import lian.app.R
import lian.app.watch.Notifications

/**
 * Shield alerts. They name the app the message came from but never quote the message, so other apps
 * that can read notifications (including stalkerware) learn nothing useful from them.
 */
object ShieldNotifications {
    private const val CHANNEL = "shield"
    private const val SCAM_BASE_ID = 1_000
    private const val NEW_APP_ID = 2

    private fun channel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_shield_channel), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.notif_shield_channel_body) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun open(context: Context, shortcut: Inbound.Shortcut, item: Long, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).setAction(shortcut.action).putExtra(Inbound.EXTRA_ITEM, item)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun scam(context: Context, appLabel: String, alertId: Long) =
        post(context, SCAM_BASE_ID + (alertId % 50).toInt(), context.getString(R.string.notif_shield_title, appLabel), context.getString(R.string.notif_shield_text), open(context, Inbound.Shortcut.SHIELD_ALERT, alertId, alertId.toInt()))

    fun newApp(context: Context) =
        post(context, NEW_APP_ID, context.getString(R.string.notif_app_title), context.getString(R.string.notif_app_text), open(context, Inbound.Shortcut.APP_ALERT, 0, -2))

    private fun post(context: Context, id: Int, title: String, text: String, intent: PendingIntent) {
        if (!Notifications.permitted(context)) return
        channel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}
