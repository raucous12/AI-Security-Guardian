package lian.core.engine.ml

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * A logistic-regression classifier over hashed binary features, stored with 8-bit weights.
 *
 * Why this shape rather than a neural network or a third-party runtime:
 *  - **Explainable.** The score is a sum of per-feature weights, so every prediction can name the
 *    words or domain parts that pushed it up. A finding never says only "the model thinks so".
 *  - **Deterministic.** Weights are integers and inputs are binary, so the raw score is an exact
 *    integer sum; only the final scale and the probability use floating point, via StrictMath. The
 *    same input gives the same result on every device, as rule R4 requires.
 *  - **Small and auditable.** One file of a few hundred KB, a documented format, no native code,
 *    no interpreter; trained offline by `:tools:trainer` from public datasets (see docs/ml-model-card.md).
 *
 * File format (big-endian), "LNM1":
 * ```
 * magic "LNM1" · u8 version=1 · UTF featurizer id · u8 hash bits
 * f64 bias · f64 scale · f64 medium-threshold logit · f64 high-threshold logit
 * UTF metadata ("key=value" lines) · i8 × 2^bits weights
 * ```
 */
class LinearModel(
    val featurizerId: String,
    val bits: Int,
    val bias: Double,
    val scale: Double,
    val mediumLogit: Double,
    val highLogit: Double,
    val metadata: Map<String, String>,
    private val weights: ByteArray,
) {
    init {
        require(bits in MIN_BITS..MAX_BITS) { "hash bits $bits out of range" }
        require(weights.size == 1 shl bits) { "expected ${1 shl bits} weights, got ${weights.size}" }
        require(scale > 0 && scale.isFinite() && bias.isFinite()) { "invalid scale or bias" }
        require(mediumLogit <= highLogit) { "thresholds out of order" }
    }

    enum class Level { NONE, MEDIUM, HIGH }

    /** One word, domain part or signal and how much it moved the score (positive = towards "scam"). */
    data class Contribution(val term: String, val weight: Double)

    data class Prediction(val logit: Double, val probability: Double, val level: Level, val reasons: List<Contribution>) {
        val percent: Int get() = (probability * 100).toInt().coerceIn(0, 100)
    }

    private val mask = (1 shl bits) - 1

    fun predict(featurizer: Featurizer, input: String, maxReasons: Int = 3): Prediction {
        require(featurizer.id == featurizerId) { "model expects $featurizerId input, got ${featurizer.id}" }
        return predict(featurizer.features(input), maxReasons)
    }

    /** Raw score from precomputed [Feature.hash] values (used by the trainer to verify an encoded model). */
    fun logit(hashes: IntArray): Double {
        var sum = 0L
        for (h in hashes) {
            val q = weights[h and mask].toInt()
            sum += if (h < 0) -q else q
        }
        return bias + scale * sum
    }

    fun predict(features: List<Feature>, maxReasons: Int = 3): Prediction {
        var sum = 0L
        val byTerm = LinkedHashMap<String, Long>()
        for (f in features) {
            val h = f.hash
            val q = weights[h and mask].toInt()
            val signed = if (h < 0) -q else q
            sum += signed
            if (f.term != null && signed != 0) byTerm[f.term] = (byTerm[f.term] ?: 0L) + signed
        }
        val logit = bias + scale * sum
        val level = when {
            logit >= highLogit -> Level.HIGH
            logit >= mediumLogit -> Level.MEDIUM
            else -> Level.NONE
        }
        val reasons = byTerm.entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(maxReasons)
            .map { Contribution(it.key, it.value * scale) }
        return Prediction(logit, sigmoid(logit), level, reasons)
    }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream(weights.size + 512)
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeByte(FORMAT_VERSION)
            out.writeUTF(featurizerId)
            out.writeByte(bits)
            out.writeDouble(bias)
            out.writeDouble(scale)
            out.writeDouble(mediumLogit)
            out.writeDouble(highLogit)
            out.writeUTF(metadata.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" })
            out.write(weights)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val MIN_BITS = 10
        const val MAX_BITS = 22
        private const val FORMAT_VERSION = 1
        private val MAGIC = byteArrayOf(0x4C, 0x4E, 0x4D, 0x31)   // "LNM1"

        /** StrictMath gives bit-identical results on every platform (fdlibm), unlike Math.exp. */
        fun sigmoid(z: Double): Double = if (z >= 0) 1.0 / (1.0 + StrictMath.exp(-z)) else StrictMath.exp(z).let { it / (1.0 + it) }

        /** Throws [IllegalArgumentException] for anything that isn't a well-formed model file. */
        fun decode(bytes: ByteArray): LinearModel = try {
            DataInputStream(bytes.inputStream()).use { input ->
                val magic = ByteArray(4).also { input.readFully(it) }
                require(magic.contentEquals(MAGIC)) { "not a LNM1 model" }
                require(input.readUnsignedByte() == FORMAT_VERSION) { "unsupported model format" }
                val featurizer = input.readUTF()
                val bits = input.readUnsignedByte()
                require(bits in MIN_BITS..MAX_BITS) { "hash bits $bits out of range" }
                val bias = input.readDouble()
                val scale = input.readDouble()
                val medium = input.readDouble()
                val high = input.readDouble()
                val metadata = input.readUTF().lineSequence().filter { '=' in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
                val weights = ByteArray(1 shl bits).also { input.readFully(it) }
                require(input.read() == -1) { "trailing bytes after model weights" }
                LinearModel(featurizer, bits, bias, scale, medium, high, metadata, weights)
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("truncated model file", e)
        }
    }
}
