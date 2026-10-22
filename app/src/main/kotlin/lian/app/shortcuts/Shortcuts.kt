package lian.app.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import lian.app.Inbound
import lian.app.MainActivity
import lian.app.R

/** Long-press launcher shortcuts: straight to a check, without opening the app first. */
object Shortcuts {
    private val IDS = mapOf(
        Inbound.Shortcut.CHECK_MESSAGE to "check_message",
        Inbound.Shortcut.CHECK_APK to "check_apk",
    )

    /** Lets the launcher rank the shortcuts by use. Only the shortcut id is reported, on the phone. */
    fun reportUsed(context: Context, target: Inbound.Shortcut) {
        IDS[target]?.let { ShortcutManagerCompat.reportShortcutUsed(context, it) }
    }

    fun publish(context: Context) {
        fun shortcut(target: Inbound.Shortcut, label: Int, icon: Int) =
            ShortcutInfoCompat.Builder(context, IDS.getValue(target))
                .setShortLabel(context.getString(label))
                .setIcon(IconCompat.createWithResource(context, icon))
                .setIntent(Intent(context, MainActivity::class.java).setAction(target.action))
                .build()
        ShortcutManagerCompat.setDynamicShortcuts(
            context,
            listOf(
                shortcut(Inbound.Shortcut.CHECK_MESSAGE, R.string.qa_message, R.drawable.ic_shortcut_message),
                shortcut(Inbound.Shortcut.CHECK_APK, R.string.qa_apk, R.drawable.ic_shortcut_apk),
            ),
        )
    }
}
