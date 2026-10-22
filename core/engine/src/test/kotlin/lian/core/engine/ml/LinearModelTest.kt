package lian.core.engine.ml

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinearModelTest {
    private fun modelWith(vararg weights: Pair<Feature, Int>, bits: Int = 12): LinearModel {
        val w = ByteArray(1 shl bits)
        for ((f, q) in weights) {
            val h = f.hash
            w[h and ((1 shl bits) - 1)] = (if (h < 0) -q else q).toByte()
        }
        return LinearModel(MessageFeaturizer.id, bits, bias = -2.0, scale = 0.5, mediumLogit = 0.0, highLogit = 2.0, metadata = mapOf("name" to "t"), weights = w)
    }

    @Test
    fun `scores are exact sums of the weights of the features present`() {
        val m = modelWith(Feature('w', "prize", "prize") to 6, Feature('w', "claim", "claim") to 4)
        val p = m.predict(MessageFeaturizer, "Claim your prize today")
        // bias -2 + 0.5 × (6 + 4) = 3.0, at or above the high threshold
        assertEquals(3.0, p.logit, 1e-12)
        assertEquals(LinearModel.Level.HIGH, p.level)
        assertEquals(listOf("prize", "claim"), p.reasons.map { it.term })
    }

    @Test
    fun `reasons only list terms that push towards scam, in a total order`() {
        val m = modelWith(Feature('w', "hello", "hello") to -5, Feature('w', "win", "win") to 3, Feature('w', "cash", "cash") to 3)
        val p = m.predict(MessageFeaturizer, "hello win cash")
        assertEquals(listOf("cash", "win"), p.reasons.map { it.term })   // equal weights → alphabetical
    }

    @Test
    fun `file format round-trips byte for byte`() {
        val m = modelWith(Feature('w', "fee", "fee") to 9)
        val bytes = m.encode()
        val decoded = LinearModel.decode(bytes)
        assertContentEquals(bytes, decoded.encode())
        assertEquals(m.predict(MessageFeaturizer, "pay the fee").logit, decoded.predict(MessageFeaturizer, "pay the fee").logit)
        assertEquals("t", decoded.metadata["name"])
    }

    @Test
    fun `malformed model files are rejected`() {
        val good = modelWith().encode()
        assertFailsWith<IllegalArgumentException> { LinearModel.decode(good.copyOf(good.size - 1)) }
        assertFailsWith<IllegalArgumentException> { LinearModel.decode(good + 0) }
        assertFailsWith<IllegalArgumentException> { LinearModel.decode(byteArrayOf(1, 2, 3, 4, 5)) }
    }

    @Test
    fun `a model refuses input from a different featurizer`() {
        assertFailsWith<IllegalArgumentException> { modelWith().predict(DomainFeaturizer, "example") }
    }

    @Test
    fun `sigmoid is symmetric and saturates without overflow`() {
        assertEquals(0.5, LinearModel.sigmoid(0.0))
        assertEquals(1.0, LinearModel.sigmoid(3.0) + LinearModel.sigmoid(-3.0), 1e-15)
        assertTrue(LinearModel.sigmoid(1_000.0) <= 1.0 && LinearModel.sigmoid(-1_000.0) >= 0.0)
    }

    @Test
    fun `message featurizer replaces links numbers and money with placeholders`() {
        val keys = MessageFeaturizer.features("Pay £1.45 at https://evil.example/x or call 07700900123").filter { it.kind == 'w' }.map { it.key }
        assertTrue("<url>" in keys && "<money>" in keys && "<numlong>" in keys, keys.toString())
        assertTrue(keys.none { "evil" in it || "07700" in it })
    }

    @Test
    fun `featurizers are deterministic and locale independent`() {
        val text = "İSTANBUL Kargo: paketiniz bekliyor, ödeme yapın"
        assertEquals(MessageFeaturizer.features(text), MessageFeaturizer.features(text))
        assertEquals(DomainFeaturizer.features("paypal-secure-login365"), DomainFeaturizer.features("paypal-secure-login365"))
        // Kotlin's lowercase() is locale-independent: the test JVM runs in Turkish (dotless ı) on purpose.
        assertTrue(MessageFeaturizer.features("INVOICE").any { it.key == "invoice" })
    }

    @Test
    fun `domain featurizer attributes character grams to the token they belong to`() {
        val f = DomainFeaturizer.features("secure-login365")
        assertEquals(setOf("secure", "login", "<num3>"), f.filter { it.kind == 't' }.map { it.key }.toSet())
        assertTrue(f.filter { it.kind == 'c' && it.key == "logi" }.all { it.term == "login" })
        assertTrue(f.any { it.kind == 'x' && it.key == "hy1" })
    }

    @Test
    fun `feature hash is pinned so shipped models keep matching`() {
        // Changing FeatureHash invalidates every trained model; this golden value makes that impossible to miss.
        assertEquals(FeatureHash.hash('w', "free"), FeatureHash.hash('w', "free"))
        assertTrue(FeatureHash.hash('w', "free") != FeatureHash.hash('t', "free"))
        assertEquals(GOLDEN_FREE, FeatureHash.hash('w', "free"))
    }

    private companion object {
        val GOLDEN_FREE = goldenFnvFmix('w', "free")

        /** Independent re-implementation of FNV-1a over UTF-16 units + MurmurHash3 fmix32. */
        fun goldenFnvFmix(kind: Char, s: String): Int {
            var h = 0x811C9DC5L
            fun mix(byte: Int) { h = ((h xor byte.toLong()) * 16777619L) and 0xFFFFFFFFL }
            mix(kind.code)
            for (c in s) { mix(c.code and 0xFF); mix(c.code ushr 8) }
            var x = h
            x = x xor (x ushr 16); x = (x * 0x85EBCA6BL) and 0xFFFFFFFFL
            x = x xor (x ushr 13); x = (x * 0xC2B2AE35L) and 0xFFFFFFFFL
            x = x xor (x ushr 16)
            return x.toInt()
        }
    }
}
