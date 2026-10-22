package lian.core.engine.link

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadsTest {
    @Test
    fun `wifi payloads, including escapes`() {
        Payloads.wifi("WIFI:S:My\\;Net;T:WPA;P:p\\:w;H:true;;")!!.let {
            assertEquals("My;Net", it.ssid)
            assertEquals("WPA", it.security)
            assertTrue(it.hidden)
            assertFalse(it.insecure)
        }
        assertTrue(Payloads.wifi("WIFI:T:nopass;S:Free Airport;;")!!.insecure)
        assertTrue(Payloads.wifi("WIFI:S:Old;T:WEP;P:x;;")!!.insecure)
        assertTrue(Payloads.wifi("WIFI:S:NoType;;")!!.insecure)
        assertNull(Payloads.wifi("https://example.com"))
    }

    @Test
    fun `phone numbers and E164 conversion`() {
        assertEquals("+442079460000", Payloads.phone("tel", "+44 20 7946 0000")!!.number)
        assertEquals("09001234567", Payloads.phone("smsto", "09001234567:WIN a prize")!!.number)
        assertEquals("+449001234567", Payloads.phone("tel", "09001234567")!!.toE164("GB"))
        assertNull(Payloads.phone("tel", "09001234567")!!.toE164(null))
        assertEquals("+19005550100", Payloads.phone("tel", "(900) 555-0100")!!.toE164("US"))
        assertNull(Payloads.phone("tel", "12"))
    }

    @Test
    fun `intent payloads that install apps`() {
        val intent = Payloads.intent("//download#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url=https%3A%2F%2Fevil.top%2Fapp.apk;end")!!
        assertEquals("com.android.chrome", intent.packageName)
        assertEquals("https://evil.top/app.apk", intent.fallbackUrl)
        assertTrue(intent.installsApp)
        assertFalse(Payloads.intent("//x#Intent;scheme=https;package=org.example;end")!!.installsApp)
        assertNull(Payloads.intent("//x"))
    }
}
