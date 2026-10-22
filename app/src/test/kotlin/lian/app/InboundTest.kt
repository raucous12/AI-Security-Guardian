package lian.app

import org.junit.Assert.assertEquals
import org.junit.Test

class InboundTest {
    private val send = "android.intent.action.SEND"
    private val apk = "application/vnd.android.package-archive"

    @Test
    fun `accepts plain text shares`() {
        assertEquals(Inbound.Text("check https://x.com"), Inbound.parse(send, "text/plain", "check https://x.com"))
    }

    @Test
    fun `accepts apk streams from other apps, not pictures`() {
        val uri = "content://com.android.providers.downloads.documents/document/42"
        assertEquals(Inbound.Apk(uri), Inbound.parse(send, apk, null, uri, "lian.app"))
        assertEquals(Inbound.Ignored, Inbound.parse(send, "image/png", null, uri, "lian.app"))
    }

    @Test
    fun `rejects other actions, types, empty and oversized text`() {
        assertEquals(Inbound.Ignored, Inbound.parse("android.intent.action.VIEW", "text/plain", "x"))
        assertEquals(Inbound.Ignored, Inbound.parse(send, "application/pdf", "x"))
        assertEquals(Inbound.Ignored, Inbound.parse(send, "text/plain", null))
        assertEquals(Inbound.Ignored, Inbound.parse(send, "text/plain", "   "))
        assertEquals(Inbound.Ignored, Inbound.parse(send, "text/plain", "a".repeat(Inbound.MAX_TEXT + 1)))
        assertEquals(Inbound.Ignored, Inbound.parse(null, null, null))
    }

    @Test
    fun `text selected in another app and LIAN's own shortcuts`() {
        assertEquals(Inbound.Text("pay at evil.top"), Inbound.parse("android.intent.action.PROCESS_TEXT", "text/plain", "pay at evil.top"))
        assertEquals(Inbound.Open(Inbound.Shortcut.CHECK_APK), Inbound.parse("lian.app.action.CHECK_APK", null, null))
        assertEquals(Inbound.Ignored, Inbound.parse("lian.app.action.SCAN_QR", null, null))
        assertEquals(Inbound.Open(Inbound.Shortcut.SHIELD_ALERT, 42), Inbound.parse("lian.app.action.SHIELD_ALERT", null, null, itemId = 42))
    }

    @Test
    fun `rejects file urls and streams pointing back into LIAN`() {
        assertEquals(Inbound.Ignored, Inbound.parse(send, apk, null, "file:///sdcard/x.apk", "lian.app"))
        assertEquals(Inbound.Ignored, Inbound.parse(send, apk, null, "content://lian.app.files/x", "lian.app"))
        assertEquals(Inbound.Ignored, Inbound.parse(send, apk, null, "content://0@lian.app.files/x", "lian.app"))
        assertEquals(Inbound.Ignored, Inbound.parse(send, apk, null, null, "lian.app"))
    }
}
