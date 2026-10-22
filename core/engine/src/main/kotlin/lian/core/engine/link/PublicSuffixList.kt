package lian.core.engine.link

/**
 * Public Suffix List matcher (https://publicsuffix.org/list/): exception rules win, otherwise the
 * longest matching rule, otherwise the implicit rule `*`. Rules are stored in Unicode form; `xn--`
 * input labels are decoded for matching, and results keep the caller's original label form.
 */
class PublicSuffixList(rules: Iterable<Pair<String, Section>>) {
    enum class Section { ICANN, PRIVATE }

    data class Match(
        val publicSuffix: String,
        /** Public suffix plus one label: the domain someone actually registered. Null if the input is itself a suffix. */
        val registrable: String?,
        /** Section of the prevailing rule; null when only the implicit `*` rule matched. */
        val section: Section?,
    ) {
        /** Under a PRIVATE-section suffix (github.io, web.app, …) anyone can create a site. */
        val sharedHosting: Boolean get() = section == Section.PRIVATE
    }

    private val exact = HashMap<String, Section>()
    private val wildcard = HashMap<String, Section>()
    private val exception = HashMap<String, Section>()

    init {
        for ((rule, section) in rules) {
            when {
                rule.startsWith("!") -> exception[rule.substring(1)] = section
                rule.startsWith("*.") -> wildcard[rule.substring(2)] = section
                else -> exact[rule] = section
            }
        }
    }

    val size: Int get() = exact.size + wildcard.size + exception.size

    /** True if [tld] is a top-level domain known to the list (ICANN or PRIVATE). */
    fun isKnownTld(tld: String): Boolean {
        val unicode = Punycode.toUnicodeLabel(tld) ?: return false
        return exact.containsKey(unicode) || wildcard.containsKey(unicode)
    }

    /** [domain] must already be lowercase. Returns null for empty labels or a leading dot. */
    fun match(domain: String): Match? {
        if (domain.isEmpty() || domain.startsWith('.')) return null
        val labels = domain.split('.')
        if (labels.any { it.isEmpty() }) return null
        val unicode = labels.map { Punycode.toUnicodeLabel(it) ?: it }
        val n = labels.size
        fun candidate(from: Int) = unicode.subList(from, n).joinToString(".")

        for (i in 0 until n) exception[candidate(i)]?.let { return result(labels, i + 1, it) }
        for (i in 0 until n) {
            val c = candidate(i)
            exact[c]?.let { return result(labels, i, it) }
            if (i + 1 < n) wildcard[candidate(i + 1)]?.let { return result(labels, i, it) }
        }
        return result(labels, n - 1, null)
    }

    private fun result(labels: List<String>, suffixStart: Int, section: Section?): Match = Match(
        publicSuffix = labels.subList(suffixStart, labels.size).joinToString("."),
        registrable = if (suffixStart == 0) null else labels.subList(suffixStart - 1, labels.size).joinToString("."),
        section = section,
    )
}
