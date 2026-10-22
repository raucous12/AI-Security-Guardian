package lian.app.shield

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lian.core.engine.app.AppReport
import lian.core.engine.message.TextCheckResult

/**
 * What a message notification contains, copied out of its extras. Pure data, so the decisions below
 * are unit-testable without Android.
 */
data class NotificationParts(
    val packageName: String,
    val category: String?,
    val title: String?,
    val text: String?,
    val bigText: String?,
    /** MessagingStyle messages, oldest first. */
    val messages: List<String>,
    val groupSummary: Boolean,
    val ongoing: Boolean,
)

object MessageExtractor {
    /** Apps whose notifications are personal messages even when they don't set a category. */
    val MESSENGERS = setOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android", "com.viber.voip",
        "com.discord", "com.snapchat.android", "com.google.android.gm", "com.microsoft.office.outlook", "jp.naver.line.android",
        "com.kakao.talk", "com.tencent.mm", "com.imo.android.imoim", "com.skype.raider", "ch.protonmail.android",
    )
    const val MIN_TEXT = 12
    const val MAX_TEXT = 4_000

    /** Only real, new messages: not summaries, not ongoing (calls, media), not LIAN itself. */
    fun isMessage(p: NotificationParts, ownPackage: String, defaultSms: String?): Boolean {
        if (p.packageName == ownPackage || p.groupSummary || p.ongoing) return false
        return p.category == "msg" || p.category == "email" || p.messages.isNotEmpty() ||
            p.packageName in MESSENGERS || p.packageName == defaultSms
    }

    /** Messaging apps often title a one-to-one chat "Name: Name"; show the name once. */
    fun sender(title: String?): String? {
        val t = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val head = t.substringBefore(": ", "")
        return if (head.isNotEmpty() && t.substringAfter(": ") == head) head else t
    }

    /** The newest message text: the last MessagingStyle message, else the expanded text, else the text. */
    fun text(p: NotificationParts): String? {
        val body = p.messages.lastOrNull()?.takeIf { it.isNotBlank() } ?: p.bigText?.takeIf { it.isNotBlank() } ?: p.text
        return body?.trim()?.take(MAX_TEXT)?.takeIf { it.length >= MIN_TEXT }
    }
}

/**
 * Warnings found by the Message Shield and the install guard, kept in memory only (Phase 2.4: messages
 * are never stored). They disappear after 24 hours or when the process ends.
 */
class ShieldStore {
    data class Alert(val id: Long, val appPackage: String, val appLabel: String, val sender: String?, val text: String, val result: TextCheckResult, val atMs: Long)
    data class Install(val packageName: String, val report: AppReport, val atMs: Long)

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    private val _installs = MutableStateFlow<List<Install>>(emptyList())
    val installs: StateFlow<List<Install>> = _installs.asStateFlow()

    private var nextId = 1L
    private val recent = object : LinkedHashMap<Int, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Unit>?) = size > 200
    }

    /** True the first time a given (app, text) pair is seen, so re-posted notifications are checked once. */
    @Synchronized
    fun firstSighting(appPackage: String, text: String): Boolean = recent.put((appPackage + '\u0000' + text).hashCode(), Unit) == null

    @Synchronized
    fun addAlert(appPackage: String, appLabel: String, sender: String?, text: String, result: TextCheckResult, now: Long): Alert {
        val alert = Alert(nextId++, appPackage, appLabel, sender?.take(80), text.take(600), result, now)
        _alerts.value = (listOf(alert) + _alerts.value.filter { now - it.atMs < DAY_MS }).take(MAX_ALERTS)
        return alert
    }

    @Synchronized
    fun addInstall(packageName: String, report: AppReport, now: Long) {
        _installs.value = (listOf(Install(packageName, report, now)) + _installs.value.filter { it.packageName != packageName && now - it.atMs < WEEK_MS }).take(MAX_INSTALLS)
    }

    fun prune(now: Long) {
        _alerts.value = _alerts.value.filter { now - it.atMs < DAY_MS }
    }

    fun clear() {
        _alerts.value = emptyList()
        _installs.value = emptyList()
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        private const val WEEK_MS = 7 * DAY_MS
        private const val MAX_ALERTS = 30
        private const val MAX_INSTALLS = 20
    }
}
