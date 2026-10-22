package lian.app.shield

import lian.core.engine.link.LinkBand
import lian.core.engine.link.LinkCheckResult
import lian.core.engine.message.TextCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldTest {
    private fun parts(
        pkg: String = "com.example.chat",
        category: String? = null,
        text: String? = "Your parcel is held, pay the fee at http://example.top/pay",
        bigText: String? = null,
        messages: List<String> = emptyList(),
        summary: Boolean = false,
        ongoing: Boolean = false,
    ) = NotificationParts(pkg, category, "Sender", text, bigText, messages, summary, ongoing)

    @Test
    fun messagesAreRecognisedByCategoryStyleOrKnownMessenger() {
        assertTrue(MessageExtractor.isMessage(parts(category = "msg"), "lian.app", null))
        assertTrue(MessageExtractor.isMessage(parts(category = "email"), "lian.app", null))
        assertTrue(MessageExtractor.isMessage(parts(messages = listOf("hi there, long enough")), "lian.app", null))
        assertTrue(MessageExtractor.isMessage(parts(pkg = "com.whatsapp"), "lian.app", null))
        assertTrue(MessageExtractor.isMessage(parts(pkg = "com.vendor.sms"), "lian.app", "com.vendor.sms"))
        assertFalse(MessageExtractor.isMessage(parts(), "lian.app", null))   // a game's promo, say
    }

    @Test
    fun summariesOngoingAndOwnNotificationsAreIgnored() {
        assertFalse(MessageExtractor.isMessage(parts(category = "msg", summary = true), "lian.app", null))
        assertFalse(MessageExtractor.isMessage(parts(category = "msg", ongoing = true), "lian.app", null))
        assertFalse(MessageExtractor.isMessage(parts(pkg = "lian.app", category = "msg"), "lian.app", null))
    }

    @Test
    fun theNewestTextIsChecked() {
        assertEquals("newest message text", MessageExtractor.text(parts(messages = listOf("older message text", "newest message text"), bigText = "expanded text here")))
        assertEquals("expanded text here", MessageExtractor.text(parts(bigText = "expanded text here", text = "short text here")))
        assertEquals("short text here", MessageExtractor.text(parts(text = "  short text here  ")))
    }

    @Test
    fun tooShortTextIsSkippedAndLongTextIsCapped() {
        assertNull(MessageExtractor.text(parts(text = "ok see u")))
        assertEquals(MessageExtractor.MAX_TEXT, MessageExtractor.text(parts(text = "a".repeat(10_000)))!!.length)
    }

    @Test
    fun repeatedChatTitlesShowTheNameOnce() {
        assertEquals("Courier", MessageExtractor.sender("Courier: Courier"))
        assertEquals("Family: Mum", MessageExtractor.sender("Family: Mum"))
        assertEquals("Bank", MessageExtractor.sender(" Bank "))
        assertNull(MessageExtractor.sender("  "))
    }

    private val clean =TextCheckResult(LinkCheckResult(emptyList(), 0, LinkBand.NO_WARNING_SIGNS, "r", "i"), null, 0, LinkBand.NO_WARNING_SIGNS)

    @Test
    fun repostedNotificationsAreCheckedOnce() {
        val store = ShieldStore()
        assertTrue(store.firstSighting("com.whatsapp", "same text"))
        assertFalse(store.firstSighting("com.whatsapp", "same text"))
        assertTrue(store.firstSighting("com.whatsapp", "different text"))
    }

    @Test
    fun alertsExpireAfterADayAndLiveOnlyInMemory() {
        val store = ShieldStore()
        store.addAlert("com.whatsapp", "WhatsApp", "Sender", "text", clean, now = 0)
        store.addAlert("com.whatsapp", "WhatsApp", "Sender", "text 2", clean, now = 1_000)
        assertEquals(2, store.alerts.value.size)
        assertEquals("text 2", store.alerts.value.first().text)   // newest first
        store.prune(ShieldStore.DAY_MS + 500)
        assertEquals(1, store.alerts.value.size)
        store.clear()
        assertTrue(store.alerts.value.isEmpty())
    }
}
