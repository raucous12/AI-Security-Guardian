package lian.core.engine.link

sealed interface Ipv4Result {
    /** The host doesn't end in a number, so it is a domain name. */
    data object NotIpv4 : Ipv4Result

    /** Ends in a number but isn't a valid address; browsers reject such URLs. */
    data object Invalid : Ipv4Result

    data class Address(val value: Long, val obfuscated: Boolean) : Ipv4Result {
        val dotted: String get() = "${value shr 24 and 255}.${value shr 16 and 255}.${value shr 8 and 255}.${value and 255}"

        val range: String
            get() {
                val a = value shr 24 and 255
                val b = value shr 16 and 255
                return when {
                    a == 127L -> "loopback"
                    a == 10L || (a == 172L && b in 16..31) || (a == 192L && b == 168L) -> "private network"
                    a == 169L && b == 254L -> "link-local"
                    a == 0L -> "unspecified"
                    else -> "public"
                }
            }
    }
}

/**
 * The WHATWG URL Standard IPv4 parser. Accepts 1–4 parts, each decimal, octal (leading 0) or hex
 * (0x); the last part fills the remaining bytes. Only ASCII digits count: Kotlin's number parsing
 * would also accept a leading sign (`1.2.3.-4`), which the standard doesn't (Phase 4, defect #2).
 */
object Ipv4 {
    fun parse(host: String): Ipv4Result {
        if (host.isEmpty()) return Ipv4Result.NotIpv4
        val parts = host.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
        if (!endsInNumber(parts.last())) return Ipv4Result.NotIpv4
        if (parts.size > 4 || parts.any { it.isEmpty() }) return Ipv4Result.Invalid

        var obfuscated = parts.size != 4
        val numbers = LongArray(parts.size)
        for ((i, part) in parts.withIndex()) {
            val (digits, radix) = when {
                part.length >= 2 && part[0] == '0' && (part[1] == 'x' || part[1] == 'X') -> part.substring(2) to 16
                part.length >= 2 && part[0] == '0' -> part.substring(1) to 8
                else -> part to 10
            }
            if (radix != 10) obfuscated = true
            if (digits.length > 12 || !digits.all { asciiDigit(it, radix) }) return Ipv4Result.Invalid
            numbers[i] = if (digits.isEmpty()) 0 else digits.toLong(radix)
        }
        for (i in 0 until numbers.size - 1) if (numbers[i] > 255) return Ipv4Result.Invalid
        if (numbers.last() >= 1L shl (8 * (5 - numbers.size))) return Ipv4Result.Invalid

        var value = numbers.last()
        for (i in 0 until numbers.size - 1) value += numbers[i] shl (8 * (3 - i))
        return Ipv4Result.Address(value, obfuscated)
    }

    /** WHATWG "ends in a number": all ASCII digits, or 0x followed by ASCII hex digits (possibly none). */
    private fun endsInNumber(part: String): Boolean =
        part.isNotEmpty() && (
            part.all { it in '0'..'9' } ||
                (part.length >= 2 && part[0] == '0' && (part[1] == 'x' || part[1] == 'X') && part.substring(2).all { asciiDigit(it, 16) })
            )

    private fun asciiDigit(c: Char, radix: Int): Boolean = when (radix) {
        8 -> c in '0'..'7'
        10 -> c in '0'..'9'
        else -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }
}
