package lian.core.engine.link

import org.junit.jupiter.api.Test
import lian.core.engine.TestIntel
import lian.core.engine.forAll
import kotlin.test.assertEquals

class TextSimilarityTest {
    /** Plain full-table OSA, the reference the bounded version must agree with. */
    private fun reference(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    @Test
    fun `known distances`() {
        assertEquals(0, osaDistance("paypal", "paypal", 2))
        assertEquals(1, osaDistance("paypall", "paypal", 2))
        assertEquals(1, osaDistance("paypla", "paypal", 2))   // adjacent transposition
        assertEquals(1, osaDistance("paypa1", "paypal", 2))
        assertEquals(3, osaDistance("abc", "xyz", 2))          // > max reported as max + 1
    }

    @Test
    fun `bounded distance agrees with the reference and is symmetric`() = forAll { random ->
        fun word() = String(CharArray(random.nextInt(9)) { "abcdlp1".random(kotlin.random.Random(random.nextLong())) })
        val a = word()
        val b = word()
        val max = random.nextInt(4)
        assertEquals(minOf(reference(a, b), max + 1), osaDistance(a, b, max), "$a vs $b max=$max")
        assertEquals(osaDistance(a, b, max), osaDistance(b, a, max))
    }

    @Test
    fun `skeletons expose look-alikes and are idempotent`() {
        val map = TestIntel.confusables
        assertEquals("paypal", skeleton("pаypal", map))        // Cyrillic а
        assertEquals("paypal", skeleton("paypa1", map))        // digit one
        assertEquals("gooogle", skeleton("goo0gle", map))      // digit zero maps to capital O, then lowercased
        assertEquals(skeleton("microsoft", map), skeleton("rnicrosoft", map))
        listOf("pаypal", "rnicrosoft", "аррӏе").forEach { assertEquals(skeleton(it, map), skeleton(skeleton(it, map), map)) }
    }
}
