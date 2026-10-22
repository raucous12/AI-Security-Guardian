package lian.core.engine.link

data class LinkCandidate(val text: String, val kind: Kind) {
    enum class Kind {
        /** Has a scheme or starts with www. */
        EXPLICIT,

        /** Looks like `example.com`; kept only if its TLD exists in the Public Suffix List. */
        BARE_DOMAIN,

        /** `name@example.com`, analysed as a mailto: link. */
        EMAIL,

        /** A whole-message payload such as a Wi-Fi QR code. */
        PAYLOAD,
    }
}

/**
 * Finds link candidates in free text with one linear pass, deliberately without regular expressions:
 * the text is attacker-controlled, and backtracking patterns can be made to run for a very long time.
 */
object LinkScanner {
    const val MAX_INPUT = 8_192
    const val MAX_CANDIDATES = 5

    private val SCHEMES = listOf(
        "http:", "https:", "ftp:", "file:", "javascript:", "vbscript:", "data:", "intent:", "market:",
        "tel:", "sms:", "smsto:", "mailto:", "geo:", "content:",
    )
    private val LEADING = "([{<\"'«“‘"
    private val TRAILING = ".,;:!?)]}>\"'»”’"

    fun extract(text: String): List<LinkCandidate> {
        val input = text.trim().take(MAX_INPUT)
        if (input.isEmpty()) return emptyList()
        if (input.startsWith("WIFI:", ignoreCase = true)) return listOf(LinkCandidate(input, LinkCandidate.Kind.PAYLOAD))

        val found = ArrayList<LinkCandidate>()
        var start = -1
        for (i in 0..input.length) {
            val boundary = i == input.length || input[i].isWhitespace()
            if (!boundary) {
                if (start < 0) start = i
                continue
            }
            if (start >= 0) {
                classify(input.substring(start, i))?.let { if (found.none { f -> f.text == it.text }) found += it }
                start = -1
                if (found.size == MAX_CANDIDATES) break
            }
        }
        return found
    }

    private fun classify(rawToken: String): LinkCandidate? {
        val token = trim(rawToken)
        if (token.length < 4) return null
        val lower = token.lowercase()
        return when {
            SCHEMES.any { lower.startsWith(it) } || lower.contains("://") -> LinkCandidate(token, LinkCandidate.Kind.EXPLICIT)
            lower.startsWith("www.") -> LinkCandidate(token, LinkCandidate.Kind.EXPLICIT)
            looksLikeEmail(token) -> LinkCandidate("mailto:$token", LinkCandidate.Kind.EMAIL)
            looksLikeBareDomain(token) -> LinkCandidate(token, LinkCandidate.Kind.BARE_DOMAIN)
            else -> null
        }
    }

    /** Strips wrapping punctuation; keeps a closing parenthesis that balances one inside the link. */
    private fun trim(token: String): String {
        var s = 0
        var e = token.length
        while (s < e && token[s] in LEADING) s++
        var open = 0
        var close = 0
        for (i in s until e) {
            if (token[i] == '(') open++ else if (token[i] == ')') close++
        }
        while (e > s && token[e - 1] in TRAILING) {
            if (token[e - 1] == ')') {
                if (open >= close) break
                close--
            }
            e--
        }
        return token.substring(s, e)
    }

    private fun looksLikeEmail(token: String): Boolean {
        val at = token.indexOf('@')
        if (at <= 0 || at != token.lastIndexOf('@') || token.contains('/')) return false
        return looksLikeBareDomain(token.substring(at + 1))
    }

    /** host[.host…].tld[/path], TLD at least two letters (or an xn-- label); no credentials or scheme. */
    private fun looksLikeBareDomain(token: String): Boolean {
        val hostPart = token.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if (!hostPart.contains('.') || hostPart.contains('@')) return false
        val labels = hostPart.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return false
        val tld = labels.last()
        val tldOk = tld.startsWith("xn--", ignoreCase = true) || (tld.length >= 2 && tld.all { it.isLetter() })
        return tldOk && labels.all { label -> label.all { it.isLetterOrDigit() || it == '-' } }
    }
}
