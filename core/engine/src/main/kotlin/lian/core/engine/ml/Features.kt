package lian.core.engine.ml

import java.text.Normalizer

/**
 * One binary feature of an input. [kind] namespaces the hash (w = word, b = word pair, c = character
 * n-gram, t = domain token, x = engineered signal). [term] is what an explanation shows the user:
 * the word or domain part the feature came from, a signal id for `x` features, or null when the
 * feature spans several words and can't be attributed to one.
 */
data class Feature(val kind: Char, val key: String, val term: String?) {
    val hash: Int get() = FeatureHash.hash(kind, key)
}

/** Turns raw input into features. Training and inference share these objects, so they can't drift apart. */
interface Featurizer {
    /** Written into the model file; a model only accepts input from the featurizer it was trained with. */
    val id: String
    fun features(input: String): List<Feature>
}

/**
 * FNV-1a over UTF-16 code units, finished with MurmurHash3's fmix32. Pure integer arithmetic:
 * identical on every JVM and on ART, so a feature lands in the same bucket at training and at runtime.
 */
object FeatureHash {
    fun hash(kind: Char, text: String): Int {
        var h = -0x7ee3623b                     // FNV offset basis 0x811C9DC5
        h = (h xor kind.code) * FNV_PRIME
        for (c in text) {
            h = (h xor (c.code and 0xFF)) * FNV_PRIME
            h = (h xor (c.code ushr 8)) * FNV_PRIME
        }
        return fmix(h)
    }

    private fun fmix(x: Int): Int {
        var h = x
        h = h xor (h ushr 16)
        h *= -0x7a143595                        // 0x85EBCA6B
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb                        // 0xC2B2AE35
        return h xor (h ushr 16)
    }

    private const val FNV_PRIME = 16777619
}

/** Signal ids used as [Feature.term] for engineered `x` features; the UI maps them to text. */
object Signals {
    const val LINK = "[link]"
    const val EMAIL = "[email address]"
    const val MONEY = "[money amount]"
    const val PHONE = "[phone number]"
    const val NUMBER = "[number]"
    const val CAPS = "[capital letters]"
    const val EXCLAMATION = "[exclamation mark]"
    const val LENGTH = "[length]"
    const val HYPHENS = "[hyphens]"
    const val DIGITS = "[digits]"
    const val RANDOM_LOOKING = "[random-looking letters]"
    const val REPEATS = "[repeated characters]"
    const val IDN = "[international characters]"
}

/**
 * Message featurizer, version 1: word unigrams and pairs, character 4-grams inside words (robust to
 * misspellings such as "acc0unt"), and a few engineered signals. Links, email addresses, numbers and
 * money amounts are replaced by placeholders, so the model learns "contains a link" rather than
 * memorising particular URLs or phone numbers from the training data.
 */
object MessageFeaturizer : Featurizer {
    override val id = "msg-v1"
    const val MAX_CHARS = 2_000

    override fun features(input: String): List<Feature> {
        val raw = input.take(MAX_CHARS)
        var letters = 0
        var upper = 0
        for (c in raw) if (c.isLetter()) { letters++; if (c.isUpperCase()) upper++ }

        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        val tokens = tokenize(text)
        val out = LinkedHashMap<String, Feature>()
        fun add(f: Feature) { out.putIfAbsent("${f.kind}${f.key}", f) }

        for (t in tokens) {
            add(Feature('w', t.text, t.term))
            if (!t.placeholder && t.text.length >= 3) {
                val padded = "^${t.text}$"
                for (i in 0..padded.length - 4) add(Feature('c', padded.substring(i, i + 4), t.term))
            }
        }
        for (i in 0 until tokens.size - 1) {
            val pair = tokens[i].text + " " + tokens[i + 1].text
            val term = if (tokens[i].placeholder || tokens[i + 1].placeholder) null else pair
            add(Feature('b', pair, term))
        }

        if (tokens.any { it.text == URL }) add(Feature('x', "url", Signals.LINK))
        if (tokens.any { it.text == EMAIL }) add(Feature('x', "email", Signals.EMAIL))
        if (tokens.any { it.text == MONEY }) add(Feature('x', "money", Signals.MONEY))
        if (tokens.any { it.text == NUM_LONG }) add(Feature('x', "phone", Signals.PHONE))
        if (letters >= 10 && upper * 10 >= letters * 3) add(Feature('x', "caps", Signals.CAPS))
        if ('!' in raw) add(Feature('x', "excl", Signals.EXCLAMATION))
        val lengthBucket = when (tokens.size) {
            in 0..5 -> 0
            in 6..15 -> 1
            in 16..30 -> 2
            else -> 3
        }
        add(Feature('x', "len$lengthBucket", Signals.LENGTH))
        return out.values.toList()
    }

    private class Token(val text: String, val placeholder: Boolean) {
        val term: String get() = if (placeholder) PLACEHOLDER_TERMS.getValue(text) else text
    }

    private fun tokenize(text: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            if (start == i) break
            val chunk = text.substring(start, i).trim { !it.isLetterOrDigit() && it !in CURRENCY }
            when {
                chunk.isEmpty() -> Unit
                looksLikeEmail(chunk) -> out += Token(EMAIL, true)
                looksLikeUrl(chunk) -> out += Token(URL, true)
                else -> splitWords(chunk, out)
            }
        }
        return out
    }

    /** Splits on anything that isn't a letter or digit; currency symbols and digit runs become placeholders. */
    private fun splitWords(chunk: String, out: MutableList<Token>) {
        var j = 0
        while (j < chunk.length) {
            val c = chunk[j]
            when {
                c in CURRENCY -> { out += Token(MONEY, true); j++ }
                c.isLetterOrDigit() -> {
                    val s = j
                    while (j < chunk.length && chunk[j].isLetterOrDigit()) j++
                    val word = chunk.substring(s, j)
                    out += if (word.all { it in '0'..'9' }) Token(numberPlaceholder(word.length), true) else Token(word, false)
                }
                else -> j++
            }
        }
    }

    private fun numberPlaceholder(length: Int) = when {
        length <= 2 -> "<num2>"
        length <= 4 -> "<num4>"
        length <= 6 -> "<num6>"
        length <= 9 -> "<num9>"
        else -> NUM_LONG
    }

    internal fun looksLikeEmail(s: String): Boolean {
        val at = s.indexOf('@')
        return at > 0 && s.indexOf('.', at) > at + 1
    }

    /** `scheme://…`, `www.…`, or `name.tld/…` with an alphabetic TLD of 2–6 letters. */
    internal fun looksLikeUrl(s: String): Boolean {
        if ("://" in s || s.startsWith("www.")) return true
        val host = s.substringBefore('/').substringBefore('?')
        val dot = host.lastIndexOf('.')
        if (dot <= 0 || dot == host.length - 1) return false
        val tld = host.substring(dot + 1)
        return tld.length in 2..6 && tld.all { it in 'a'..'z' } && host[dot - 1].isLetterOrDigit()
    }

    private const val URL = "<url>"
    private const val EMAIL = "<email>"
    private const val MONEY = "<money>"
    private const val NUM_LONG = "<numlong>"
    private val CURRENCY = setOf('£', '$', '€', '₹', '¥', '₦', '₱')
    private val PLACEHOLDER_TERMS = mapOf(
        URL to Signals.LINK, EMAIL to Signals.EMAIL, MONEY to Signals.MONEY, NUM_LONG to Signals.PHONE,
        "<num2>" to Signals.NUMBER, "<num4>" to Signals.NUMBER, "<num6>" to Signals.NUMBER, "<num9>" to Signals.NUMBER,
    )
}

/**
 * Domain featurizer, version 1. Input is the registrable label in ASCII form (`paypal-secure-login`
 * from `paypal-secure-login.top`, or the user-chosen part on shared hosting such as `x.web.app`).
 * The public suffix is deliberately left out: risky endings are already a separate, explainable rule,
 * and the model should judge the name itself.
 */
object DomainFeaturizer : Featurizer {
    override val id = "dom-v1"
    const val MAX_CHARS = 63

    override fun features(input: String): List<Feature> {
        val label = input.lowercase().take(MAX_CHARS)
        val out = LinkedHashMap<String, Feature>()
        fun add(f: Feature) { out.putIfAbsent("${f.kind}${f.key}", f) }

        val tokens = tokens(label)
        for (t in tokens) add(Feature('t', t.text, t.term))

        val padded = "^$label$"
        for (n in 3..5) {
            for (i in 0..padded.length - n) {
                // Span in label coordinates (the ^ adds one); attribute the gram to the token that contains it.
                val from = i - 1
                val to = i - 1 + n
                val owner = tokens.firstOrNull { from >= it.start - 1 && to <= it.end + 1 }
                add(Feature('c', padded.substring(i, i + n), owner?.term))
            }
        }

        val hyphens = label.count { it == '-' }
        val digits = label.count { it in '0'..'9' }
        val letters = label.count { it in 'a'..'z' }
        add(Feature('x', "len" + bucket(label.length, intArrayOf(4, 8, 12, 16, 24)), Signals.LENGTH))
        add(Feature('x', "hy" + minOf(hyphens, 3), Signals.HYPHENS))
        add(Feature('x', "dg" + bucket(digits, intArrayOf(0, 2, 5)), Signals.DIGITS))
        if (letters >= 6 && label.count { it in VOWELS } * 5 < letters) add(Feature('x', "vowel_low", Signals.RANDOM_LOOKING))
        if (longestRun(label) { it in 'a'..'z' && it !in VOWELS } >= 5) add(Feature('x', "cons5", Signals.RANDOM_LOOKING))
        if (longestRepeat(label) >= 4) add(Feature('x', "repeat", Signals.REPEATS))
        if (label.startsWith("xn--")) add(Feature('x', "idn", Signals.IDN))
        return out.values.toList()
    }

    private class Token(val text: String, val start: Int, val end: Int) {
        val term: String get() = if (text.startsWith("<num")) Signals.DIGITS else text
    }

    /** Splits on hyphens and on letter/digit boundaries: `secure365-login` → secure, <num>, login. */
    private fun tokens(label: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < label.length) {
            if (label[i] == '-') { i++; continue }
            val digit = label[i] in '0'..'9'
            val s = i
            while (i < label.length && label[i] != '-' && (label[i] in '0'..'9') == digit) i++
            val text = label.substring(s, i)
            out += Token(if (digit) "<num${minOf(text.length, 5)}>" else text, s, i)
        }
        return out
    }

    private fun bucket(value: Int, bounds: IntArray): Int {
        for ((i, b) in bounds.withIndex()) if (value <= b) return i
        return bounds.size
    }

    private inline fun longestRun(s: String, pred: (Char) -> Boolean): Int {
        var best = 0
        var run = 0
        for (c in s) { run = if (pred(c)) run + 1 else 0; if (run > best) best = run }
        return best
    }

    private fun longestRepeat(s: String): Int {
        var best = 0
        var run = 0
        for (i in s.indices) { run = if (i > 0 && s[i] == s[i - 1]) run + 1 else 1; if (run > best) best = run }
        return best
    }

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')
}
