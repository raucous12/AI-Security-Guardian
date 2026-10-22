package lian.core.platform

import android.content.Context
import android.telephony.TelephonyManager

/** ISO 3166 region for reading national phone-number formats: SIM country first, then the locale. No permission needed. */
object RegionHint {
    fun of(context: Context): String? {
        val sim = context.getSystemService(TelephonyManager::class.java)?.simCountryIso?.takeIf { it.length == 2 }
        val locale = context.resources.configuration.locales[0]?.country?.takeIf { it.length == 2 }
        return (sim ?: locale)?.uppercase()
    }
}
