package lian.core.engine.link

/** `WIFI:T:WPA;S:name;P:secret;H:true;;` (the de-facto QR format). The password is never kept. */
data class WifiPayload(val ssid: String?, val security: String?, val hidden: Boolean) {
    /** No password (`nopass` or absent) or WEP, which is broken. */
    val insecure: Boolean get() = security == null || security.equals("nopass", true) || security.equals("WEP", true)
}

data class PhonePayload(val scheme: String, val number: String) {
    /** Digits with an optional leading +; national formats need a region to become E.164. */
    fun toE164(regionHint: String?): String? {
        val n = number
        return when {
            n.startsWith("+") -> n
            n.startsWith("00") -> "+" + n.substring(2)
            regionHint.equals("GB", true) && n.startsWith("0") -> "+44" + n.substring(1)
            regionHint.equals("US", true) && n.length == 10 -> "+1$n"
            regionHint.equals("US", true) && n.length == 11 && n.startsWith("1") -> "+$n"
            else -> null
        }
    }
}

/** `intent:` URIs (`intent://host/path#Intent;scheme=https;package=…;S.browser_fallback_url=…;end`). */
data class IntentPayload(val target: String?, val params: Map<String, String>) {
    val packageName: String? get() = params["package"]
    val fallbackUrl: String? get() = params["S.browser_fallback_url"]
    val action: String? get() = params["action"]
    val mimeType: String? get() = params["type"]
    val component: String? get() = params["component"]

    /** Tries to install an app: installer action, APK MIME type, package installer, or an APK fallback. */
    val installsApp: Boolean
        get() = action == "android.intent.action.INSTALL_PACKAGE" ||
            mimeType == "application/vnd.android.package-archive" ||
            component.orEmpty().contains("packageinstaller", ignoreCase = true) ||
            fallbackUrl.orEmpty().substringBefore('?').lowercase().endsWith(".apk")
}

object Payloads {
    fun wifi(raw: String): WifiPayload? {
        if (!raw.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = HashMap<String, String>()
        val body = raw.substring(5)
        var i = 0
        while (i < body.length) {
            val colon = body.indexOf(':', i)
            if (colon < 0) break
            val key = body.substring(i, colon).uppercase()
            val value = StringBuilder()
            var j = colon + 1
            while (j < body.length && body[j] != ';') {
                if (body[j] == '\\' && j + 1 < body.length) j++
                value.append(body[j])
                j++
            }
            if (key.isNotEmpty() && key.length <= 2) fields[key] = value.toString()
            i = j + 1
            if (i < body.length && body[i] == ';') break
        }
        return WifiPayload(ssid = fields["S"], security = fields["T"]?.takeIf { it.isNotEmpty() }, hidden = fields["H"].equals("true", true))
    }

    /** tel:, sms:, smsto: → digits (and a leading +) of the number, dropping any body or parameters. */
    fun phone(scheme: String, opaque: String): PhonePayload? {
        val numberPart = opaque.removePrefix("//").substringBefore('?').substringBefore(';').let {
            if (scheme == "smsto") it.substringBefore(':') else it
        }
        val decoded = UrlParser.percentDecode(numberPart)
        val digits = buildString {
            decoded.forEachIndexed { index, c ->
                if (c in '0'..'9') append(c) else if (c == '+' && index == 0) append(c)
            }
        }
        return if (digits.count { it in '0'..'9' } >= 3) PhonePayload(scheme, digits) else null
    }

    fun intent(opaque: String): IntentPayload? {
        val marker = opaque.indexOf("#Intent;")
        if (marker < 0) return null
        val target = opaque.substring(0, marker).removePrefix("//").takeIf { it.isNotEmpty() }
        val params = opaque.substring(marker + "#Intent;".length).split(';')
            .filter { it.contains('=') && it != "end" }
            .associate { it.substringBefore('=') to UrlParser.percentDecode(it.substringAfter('=')) }
        return IntentPayload(target, params)
    }
}
