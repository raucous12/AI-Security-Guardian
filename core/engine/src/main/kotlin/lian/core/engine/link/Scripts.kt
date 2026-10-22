package lian.core.engine.link

import java.lang.Character.UnicodeScript

/** UTS #39 §5.2 restriction levels, from most to least restrictive. */
enum class RestrictionLevel { ASCII, SINGLE_SCRIPT, HIGHLY_RESTRICTIVE, MODERATELY_RESTRICTIVE, MINIMALLY_RESTRICTIVE }

object Scripts {
    private val IGNORED = setOf(UnicodeScript.COMMON, UnicodeScript.INHERITED, UnicodeScript.UNKNOWN)
    private val HIGHLY_RESTRICTIVE_SETS = listOf(
        setOf(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA),
        setOf(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.BOPOMOFO),
        setOf(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.HANGUL),
    )

    /** Zero-width and invisible formatting characters that let two different labels look identical. */
    private val INVISIBLE = setOf(0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180E, 0x200B, 0x200C, 0x200D, 0x2060, 0x2064, 0x3164, 0xFEFF)

    /** Bidirectional controls that can make displayed text read differently from what it is. */
    private val BIDI_CONTROLS = setOf(0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069)

    fun scriptsOf(text: String): Set<UnicodeScript> =
        text.codePoints().toArray().map { UnicodeScript.of(it) }.filterTo(LinkedHashSet()) { it !in IGNORED }

    fun restrictionLevel(label: String): RestrictionLevel {
        if (label.all { it.code < 0x80 }) return RestrictionLevel.ASCII
        val scripts = scriptsOf(label)
        if (scripts.size <= 1) return RestrictionLevel.SINGLE_SCRIPT
        if (HIGHLY_RESTRICTIVE_SETS.any { it.containsAll(scripts) }) return RestrictionLevel.HIGHLY_RESTRICTIVE
        if (scripts.size == 2 && UnicodeScript.LATIN in scripts &&
            (scripts - UnicodeScript.LATIN).single() !in setOf(UnicodeScript.CYRILLIC, UnicodeScript.GREEK)
        ) return RestrictionLevel.MODERATELY_RESTRICTIVE
        return RestrictionLevel.MINIMALLY_RESTRICTIVE
    }

    fun invisibleCodePoints(text: String): List<Int> = text.codePoints().toArray().filter { it in INVISIBLE }

    fun bidiControls(text: String): List<Int> = text.codePoints().toArray().filter { it in BIDI_CONTROLS }

    fun hex(codePoint: Int): String = "U+" + codePoint.toString(16).uppercase().padStart(4, '0')
}
