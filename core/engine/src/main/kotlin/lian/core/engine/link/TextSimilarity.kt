package lian.core.engine.link

import java.text.Normalizer

/**
 * Optimal-string-alignment distance (Damerau–Levenshtein restricted to adjacent transpositions),
 * abandoning early once every cell in a row exceeds [max]. Returns `min(distance, max + 1)`.
 * Note: OSA is not a metric (no triangle inequality); it isn't used as one.
 */
fun osaDistance(a: String, b: String, max: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > max) return max + 1
    var prev2 = IntArray(b.length + 1)
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        var rowMin = i
        for (j in 1..b.length) {
            var d = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d = minOf(d, prev2[j - 2] + 1)
            cur[j] = d
            if (d < rowMin) rowMin = d
        }
        if (rowMin > max) return max + 1
        val recycled = prev2
        prev2 = prev
        prev = cur
        cur = recycled
    }
    return minOf(prev[b.length], max + 1)
}

/** Maps a code point to its UTS #39 prototype (a sequence of code points), or null if it has none. */
fun interface ConfusableMap {
    fun prototype(codePoint: Int): String?
}

/**
 * UTS #39 §4 skeleton: NFD → replace each code point with its prototype → NFD, then lowercased
 * (DNS labels are case-insensitive, and the table maps some characters to capitals, e.g. 0 → O).
 * Two labels with the same skeleton are visually confusable.
 */
fun skeleton(text: String, confusables: ConfusableMap): String {
    val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
    val mapped = StringBuilder(nfd.length)
    var i = 0
    while (i < nfd.length) {
        val cp = nfd.codePointAt(i)
        val prototype = confusables.prototype(cp)
        if (prototype != null) mapped.append(prototype) else mapped.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return Normalizer.normalize(mapped, Normalizer.Form.NFD).lowercase()
}
