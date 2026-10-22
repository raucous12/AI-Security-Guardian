package lian.core.engine.link

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

data class ParsedUrl(
    val raw: String,
    /** Lowercase; `http` when the input had no scheme. */
    val scheme: String,
    val schemeMissing: Boolean,
    /** http, https, ftp, ws, wss, file: parsed as scheme://authority/path. */
    val hierarchical: Boolean,
    /** A hierarchical scheme not followed by exactly `//` (browsers still accept `http:/x`, `http:///x`). */
    val slashAnomaly: Boolean,
    val hasBackslash: Boolean,
    val userinfo: String?,
    val rawHost: String,
    /** Percent-decoded, IDNA dots mapped to `.`, NFKC-mapped, lowercased, trailing dot removed. */
    val host: String,
    val hostPercentEncoded: Boolean,
    /** The host used full-width or other compatibility forms that browsers silently map (ｐａｙｐａｌ). */
    val compatibilityForms: Boolean,
    val unicodeDots: Boolean,
    val trailingDot: Boolean,
    val port: Int?,
    val portInvalid: Boolean,
    val path: String,
    val query: String?,
    val fragment: String?,
    /** Everything after `scheme:` for non-hierarchical schemes (tel:, javascript:, intent:, …). */
    val opaque: String?,
)

/**
 * A deliberately small URL parser following the WHATWG URL Standard where it matters for deception:
 * tab/newline stripping, backslashes as slashes, the last `@` ending the userinfo, IDNA full stops.
 * It never resolves or fetches anything.
 */
object UrlParser {
    private val HIERARCHICAL = setOf("http", "https", "ftp", "ws", "wss", "file")
    private val KNOWN = HIERARCHICAL + setOf(
        "javascript", "vbscript", "data", "intent", "tel", "sms", "smsto", "mailto", "geo", "market",
        "content", "wifi", "blob", "about", "android-app",
    )

    /** U+3002 IDEOGRAPHIC FULL STOP, U+FF0E FULLWIDTH FULL STOP, U+FF61 HALFWIDTH IDEOGRAPHIC FULL STOP (UTS #46 maps them to '.'). */
    private val IDNA_DOTS = charArrayOf('。', '．', '｡')

    fun parse(input: String): ParsedUrl? {
        val s = input.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
        if (s.isEmpty()) return null

        val colon = s.indexOf(':')
        val schemeCandidate = if (colon > 0) s.substring(0, colon) else ""
        val validScheme = schemeCandidate.isNotEmpty() && schemeCandidate[0].isAsciiLetter() &&
            schemeCandidate.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }
        val schemeKnown = validScheme && (schemeCandidate.lowercase() in KNOWN || s.startsWith("$schemeCandidate://"))

        val scheme: String
        val schemeMissing: Boolean
        var rest: String
        if (schemeKnown) {
            scheme = schemeCandidate.lowercase()
            schemeMissing = false
            rest = s.substring(colon + 1)
        } else {
            scheme = "http"
            schemeMissing = true
            rest = "//$s"
        }

        if (scheme !in HIERARCHICAL) {
            return ParsedUrl(
                raw = input, scheme = scheme, schemeMissing = false, hierarchical = false, slashAnomaly = false,
                hasBackslash = false, userinfo = null, rawHost = "", host = "", hostPercentEncoded = false,
                compatibilityForms = false, unicodeDots = false, trailingDot = false, port = null, portInvalid = false, path = "",
                query = null, fragment = null, opaque = rest,
            )
        }

        val hasBackslash = rest.contains('\\')
        rest = rest.replace('\\', '/')
        val slashes = rest.takeWhile { it == '/' }.length
        rest = rest.substring(slashes)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val afterAuthority = rest.substring(authorityEnd)

        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at) else null
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority

        var rawHost: String
        var portText: String? = null
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            rawHost = if (close > 0) hostPort.substring(0, close + 1) else hostPort
            val tail = if (close > 0) hostPort.substring(close + 1) else ""
            if (tail.startsWith(":")) portText = tail.substring(1)
        } else {
            val c = hostPort.lastIndexOf(':')
            rawHost = if (c >= 0) hostPort.substring(0, c) else hostPort
            if (c >= 0) portText = hostPort.substring(c + 1)
        }
        val port = portText?.takeIf { it.isNotEmpty() && it.all { ch -> ch in '0'..'9' } && it.length <= 5 }?.toInt()?.takeIf { it <= 65_535 }
        val portInvalid = !portText.isNullOrEmpty() && port == null

        val decodedHost = percentDecode(rawHost)
        val unicodeDots = decodedHost.any { it in IDNA_DOTS }
        val dotted = IDNA_DOTS.fold(decodedHost) { acc, dot -> acc.replace(dot, '.') }
        // Browsers apply the UTS #46 mapping to hosts, so full-width forms (ｐａｙｐａｌ.com, １２７.０.０.１)
        // reach the real site. NFKC plus lowercasing reproduces that mapping closely enough for analysis.
        val mapped = Normalizer.normalize(dotted, Normalizer.Form.NFKC)
        var host = IDNA_DOTS.fold(mapped) { acc, dot -> acc.replace(dot, '.') }.lowercase()
        val trailingDot = host.length > 1 && host.endsWith('.')
        if (trailingDot) host = host.dropLast(1)

        val queryStart = afterAuthority.indexOf('?')
        val fragmentStart = afterAuthority.indexOf('#')
        val pathEnd = listOf(queryStart, fragmentStart).filter { it >= 0 }.minOrNull() ?: afterAuthority.length
        val query = if (queryStart >= 0 && (fragmentStart < 0 || queryStart < fragmentStart)) {
            afterAuthority.substring(queryStart + 1, if (fragmentStart > queryStart) fragmentStart else afterAuthority.length)
        } else null
        val fragment = if (fragmentStart >= 0) afterAuthority.substring(fragmentStart + 1) else null

        return ParsedUrl(
            raw = input,
            scheme = scheme,
            schemeMissing = schemeMissing,
            hierarchical = true,
            slashAnomaly = !schemeMissing && slashes != 2 && scheme != "file",
            hasBackslash = hasBackslash,
            userinfo = userinfo,
            rawHost = rawHost,
            host = host,
            hostPercentEncoded = decodedHost != rawHost,
            compatibilityForms = mapped != dotted,
            unicodeDots = unicodeDots,
            trailingDot = trailingDot,
            port = port,
            portInvalid = portInvalid,
            path = afterAuthority.substring(0, pathEnd),
            query = query,
            fragment = fragment,
            opaque = null,
        )
    }

    /** Query parameters, percent-decoded. Order preserved; malformed escapes kept as written. */
    fun queryParameters(query: String?): List<Pair<String, String>> =
        query.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            percentDecode(it.substringBefore('=').replace('+', ' ')) to percentDecode(it.substringAfter('=', "").replace('+', ' '))
        }

    /** UTF-8 percent-decoding; returns the input unchanged if the escapes don't form valid UTF-8. */
    fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length && hex(text[i + 1]) >= 0 && hex(text[i + 2]) >= 0) {
                bytes.write(hex(text[i + 1]) * 16 + hex(text[i + 2]))
                i += 3
            } else {
                val width = if (Character.isHighSurrogate(c) && i + 1 < text.length) 2 else 1
                bytes.write(text.substring(i, i + width).toByteArray(Charsets.UTF_8))
                i += width
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            text
        }
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}
