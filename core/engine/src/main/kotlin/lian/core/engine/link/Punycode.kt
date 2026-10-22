package lian.core.engine.link

/**
 * RFC 3492 Punycode, implemented here rather than via java.net.IDN: on Android that class is backed
 * by ICU and its results differ from the desktop JDK, and the engine must behave identically on both.
 */
object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128
    private const val DELIMITER = '-'
    private const val ACE_PREFIX = "xn--"

    /** Decodes a Punycode string (without the `xn--` prefix). Returns null for malformed input. */
    fun decode(input: String): String? {
        val basicEnd = maxOf(input.lastIndexOf(DELIMITER), 0)
        val output = ArrayList<Int>(input.length)
        for (j in 0 until basicEnd) {
            val c = input[j]
            if (c.code >= 0x80) return null
            output += c.code
        }
        var n = INITIAL_N
        var i = 0
        var bias = INITIAL_BIAS
        var index = if (basicEnd > 0) basicEnd + 1 else 0
        while (index < input.length) {
            val oldI = i
            var w = 1
            var k = BASE
            while (true) {
                if (index >= input.length) return null
                val digit = digitValue(input[index++])
                if (digit < 0) return null
                if (digit > (Int.MAX_VALUE - i) / w) return null
                i += digit * w
                val t = threshold(k, bias)
                if (digit < t) break
                if (w > Int.MAX_VALUE / (BASE - t)) return null
                w *= BASE - t
                k += BASE
            }
            val length = output.size + 1
            bias = adapt(i - oldI, length, oldI == 0)
            if (i / length > Int.MAX_VALUE - n) return null
            n += i / length
            i %= length
            if (n > 0x10FFFF || n in 0xD800..0xDFFF) return null
            output.add(i, n)
            i++
        }
        return buildString(output.size) { output.forEach { appendCodePoint(it) } }
    }

    /** Encodes to Punycode (without the `xn--` prefix). Basic code points keep their case. */
    fun encode(input: String): String? {
        val codePoints = input.codePoints().toArray()
        val output = StringBuilder()
        for (cp in codePoints) if (cp < 0x80) output.append(cp.toChar())
        val basicCount = output.length
        var handled = basicCount
        if (basicCount > 0) output.append(DELIMITER)

        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (handled < codePoints.size) {
            val m = codePoints.filter { it >= n }.min()
            delta += (m - n).toLong() * (handled + 1)
            if (delta > Int.MAX_VALUE) return null
            n = m
            for (cp in codePoints) {
                if (cp < n && ++delta > Int.MAX_VALUE) return null
                if (cp == n) {
                    var q = delta.toInt()
                    var k = BASE
                    while (true) {
                        val t = threshold(k, bias)
                        if (q < t) break
                        output.append(digitChar(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    output.append(digitChar(q))
                    bias = adapt(delta.toInt(), handled + 1, handled == basicCount)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return output.toString()
    }

    /** `xn--` label → Unicode; other labels unchanged. Null if the ACE label is malformed. */
    fun toUnicodeLabel(label: String): String? =
        if (label.startsWith(ACE_PREFIX, ignoreCase = true)) decode(label.substring(ACE_PREFIX.length)) else label

    /** Unicode label → `xn--` form; ASCII labels unchanged. */
    fun toAsciiLabel(label: String): String? =
        if (label.all { it.code < 0x80 }) label else encode(label)?.let { ACE_PREFIX + it }

    private fun threshold(k: Int, bias: Int): Int = when {
        k <= bias -> TMIN
        k >= bias + TMAX -> TMAX
        else -> k - bias
    }

    private fun adapt(delta: Int, numPoints: Int, firstTime: Boolean): Int {
        var d = if (firstTime) delta / DAMP else delta / 2
        d += d / numPoints
        var k = 0
        while (d > ((BASE - TMIN) * TMAX) / 2) {
            d /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * d / (d + SKEW)
    }

    private fun digitValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0' + 26
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a'
        else -> -1
    }

    private fun digitChar(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)
}
