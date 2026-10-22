package lian.app

/**
 * Validation of content and shortcuts coming into the app (Phase 3.8.5). Pure, so it's unit-testable:
 * the activity passes the raw intent fields in. Accepted:
 *  - ACTION_SEND text/plain up to 8 KB, or a `content://` APK stream that doesn't point back into
 *    LIAN itself (no confused deputy). The sender's file name is never used.
 *  - ACTION_PROCESS_TEXT: "Check with LIAN" in any app's text-selection menu.
 *  - LIAN's own shortcuts (launcher shortcuts, alert notifications).
 */
sealed interface Inbound {
    data class Text(val text: String) : Inbound
    data class Apk(val uri: String) : Inbound
    /** Open a screen: one of the [Shortcut] targets, optionally pointing at one alert. */
    data class Open(val target: Shortcut, val itemId: Long = 0) : Inbound
    data object Ignored : Inbound

    enum class Shortcut(val action: String) {
        CHECK_MESSAGE("lian.app.action.CHECK_MESSAGE"),
        CHECK_APK("lian.app.action.CHECK_APK"),
        SHIELD_ALERT("lian.app.action.SHIELD_ALERT"),
        APP_ALERT("lian.app.action.APP_ALERT"),
    }

    companion object {
        const val MAX_TEXT = 8_192
        const val EXTRA_ITEM = "lian.app.extra.ITEM"
        private const val ACTION_SEND = "android.intent.action.SEND"
        private const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
        private const val APK_TYPE = "application/vnd.android.package-archive"

        fun parse(
            action: String?,
            type: String?,
            text: CharSequence?,
            stream: String? = null,
            ownPackage: String = "",
            itemId: Long = 0,
        ): Inbound {
            Shortcut.entries.firstOrNull { it.action == action }?.let { return Open(it, itemId) }
            if (action == ACTION_PROCESS_TEXT) return text(text)
            if (action != ACTION_SEND || type == null) return Ignored
            return when {
                type == "text/plain" -> text(text)
                type == APK_TYPE -> stream?.takeIf { safeStream(it, ownPackage) }?.let(::Apk) ?: Ignored
                else -> Ignored
            }
        }

        private fun text(text: CharSequence?): Inbound {
            val value = text?.toString() ?: return Ignored
            return if (value.isBlank() || value.length > MAX_TEXT) Ignored else Text(value)
        }

        private fun safeStream(uri: String, ownPackage: String): Boolean {
            if (!uri.startsWith("content://")) return false
            val authority = uri.removePrefix("content://").substringBefore('/').substringAfterLast('@')
            return authority.isNotEmpty() && (ownPackage.isEmpty() || !authority.startsWith(ownPackage))
        }
    }
}
