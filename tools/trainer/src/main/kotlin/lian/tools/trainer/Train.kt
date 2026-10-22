package lian.tools.trainer

import lian.core.engine.ml.DomainFeaturizer
import lian.core.engine.ml.FeatureHash
import lian.core.engine.ml.Featurizer
import lian.core.engine.ml.LinearModel
import lian.core.engine.ml.MessageFeaturizer
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deterministic training: the same corpus always produces byte-identical model files.
 *  - samples are split 80/10/10 by a hash of their normalised text (duplicates can't straddle splits);
 *  - logistic regression with AdaGrad and L2, sample order from a fixed-seed SplitMix64 shuffle;
 *  - L2 strength chosen on the validation split; weights quantised to 8 bits;
 *  - thresholds chosen on validation for target precisions; metrics reported on the untouched test split.
 */
class Train(private val props: Props, private val corpusDir: File, private val modelDir: File, private val cardFile: File) {

    class Sample(val hashes: IntArray, val y: Int, val split: Int, val source: String)

    class Spec(
        val name: String,
        val file: String,
        val featurizer: Featurizer,
        val bits: Int,
        val mediumPrecision: Double,
        val highPrecision: Double,
        val sources: List<String>,
    )

    class Result(val spec: Spec, val model: LinearModel, val report: String)

    fun run() {
        val results = listOf(
            train(
                Spec("message", "message.vgm", MessageFeaturizer, bits = 18, mediumPrecision = 0.90, highPrecision = 0.98, sources = listOf("uci", "mishra")),
                loadMessages(),
            ),
            train(
                Spec("domain", "domain.vgm", DomainFeaturizer, bits = 18, mediumPrecision = 0.90, highPrecision = 0.97, sources = listOf("majestic", "phishdb")),
                loadDomains(),
            ),
        )
        modelDir.mkdirs()
        results.forEach { File(modelDir, it.spec.file).writeBytes(it.model.encode()) }
        cardFile.parentFile.mkdirs()
        cardFile.writeText(ModelCard.render(props, results))
        results.forEach { println(it.report) }
    }

    // ---- corpora -------------------------------------------------------------------------------

    private fun loadMessages(): List<Sample> {
        val public = readGzip(File(corpusDir, "messages.tsv.gz")).mapNotNull { line ->
            val (label, source, text) = line.split('\t', limit = 3).takeIf { it.size == 3 } ?: return@mapNotNull null
            Sample(hashes(MessageFeaturizer, text), label.toInt(), splitOf(messageKey(text)), source)
        }
        // Hand-written examples of 2023â€“2026 scam formats and of genuine transactional texts (OTP codes,
        // delivery updates) that the older public datasets lack. Training split only: never used for metrics.
        val authored = File(corpusDir, "authored-messages.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> val (label, text) = line.split('\t', limit = 2); Sample(hashes(MessageFeaturizer, text), label.toInt(), TRAIN, "authored") }
        return public + authored
    }

    private fun loadDomains(): List<Sample> = readGzip(File(corpusDir, "domains.tsv.gz")).mapNotNull { line ->
        val (label, source, domain) = line.split('\t', limit = 3).takeIf { it.size == 3 } ?: return@mapNotNull null
        Sample(hashes(DomainFeaturizer, domain), label.toInt(), splitOf(domain), source)
    }

    private fun hashes(f: Featurizer, input: String) = f.features(input).map { it.hash }.toIntArray()

    private fun readGzip(file: File): List<String> =
        GZIPInputStream(file.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }

    // ---- training ------------------------------------------------------------------------------

    private fun train(spec: Spec, samples: List<Sample>): Result {
        val trainSet = samples.filter { it.split == TRAIN }
        val validation = samples.filter { it.split == VALIDATION }
        val test = samples.filter { it.split == TEST }
        println("${spec.name}: train ${trainSet.size} (${trainSet.count { it.y == 1 }} positive), validation ${validation.size}, test ${test.size}")

        val candidates = listOf(1e-6, 1e-5, 1e-4).map { l2 ->
            val w = fit(trainSet, spec.bits, l2)
            Triple(l2, w, logLoss(validation) { logitFloat(w, spec.bits, it) })
        }
        val (l2, weights, valLoss) = candidates.minWith(compareBy<Triple<Double, DoubleArray, Double>> { it.third }.thenBy { it.first })
        println("${spec.name}: chose L2=$l2 (validation log-loss ${fmt(valLoss)}; tried ${candidates.joinToString { "${it.first}â†’${fmt(it.third)}" }})")

        val (scale, q) = quantize(weights, spec.bits, validation)
        val bias = weights[1 shl spec.bits]
        fun logitQ(s: Sample): Double {
            var sum = 0L
            val mask = (1 shl spec.bits) - 1
            for (h in s.hashes) { val v = q[h and mask].toInt(); sum += if (h < 0) -v else v }
            return bias + scale * sum
        }
        val valLogits = validation.map { logitQ(it) to it.y }
        val medium = threshold(valLogits, spec.mediumPrecision)
        val high = maxOf(threshold(valLogits, spec.highPrecision), medium)
        val testLogits = test.map { logitQ(it) to it.y }

        val metrics = Metrics(testLogits, medium, high)
        val metadata = linkedMapOf(
            "name" to spec.name,
            "corpus.date" to props["corpus.date"],
            "sources" to spec.sources.joinToString(",") { "$it(${props.opt("$it.license")})" },
            "train.samples" to trainSet.size.toString(),
            "l2" to l2.toString(),
            "test.samples" to test.size.toString(),
            "test.auc" to fmt(metrics.auc),
            "test.high.precision" to fmt(metrics.high.precision),
            "test.high.recall" to fmt(metrics.high.recall),
            "test.medium.precision" to fmt(metrics.medium.precision),
            "test.medium.recall" to fmt(metrics.medium.recall),
            "test.high.fpr" to fmt(metrics.high.fpr),
        )
        val model = LinearModel(spec.featurizer.id, spec.bits, bias, scale, medium, high, metadata, q)
        // Round-trip through the file format and re-score: the shipped bytes must reproduce the reported metrics.
        val reloaded = LinearModel.decode(model.encode())
        check(test.all { abs(reloaded.logit(it.hashes) - logitQ(it)) < 1e-9 }) { "encoded model disagrees with trainer" }
        return Result(spec, model, "${spec.name}: ${metrics.summary()}")
    }

    /** AdaGrad logistic regression on hashed features with lazy L2; balanced class weights. */
    private fun fit(samples: List<Sample>, bits: Int, l2: Double): DoubleArray {
        val dims = 1 shl bits
        val mask = dims - 1
        val w = DoubleArray(dims + 1)          // last slot = bias
        val g2 = DoubleArray(dims + 1)
        val positives = samples.count { it.y == 1 }.coerceAtLeast(1)
        val negatives = (samples.size - positives).coerceAtLeast(1)
        val posWeight = samples.size / (2.0 * positives)
        val negWeight = samples.size / (2.0 * negatives)
        val order = IntArray(samples.size) { it }
        val rng = SplitMix64(0x5EED_0F_716L)
        repeat(EPOCHS) {
            for (i in order.size - 1 downTo 1) {              // Fisherâ€“Yates with a fixed seed
                val j = rng.nextInt(i + 1)
                val t = order[i]; order[i] = order[j]; order[j] = t
            }
            for (idx in order) {
                val s = samples[idx]
                var z = w[dims]
                for (h in s.hashes) z += if (h < 0) -w[h and mask] else w[h and mask]
                val p = LinearModel.sigmoid(z)
                val err = (p - s.y) * if (s.y == 1) posWeight else negWeight
                for (h in s.hashes) {
                    val b = h and mask
                    val grad = (if (h < 0) -err else err) + l2 * w[b]
                    g2[b] += grad * grad
                    w[b] -= LEARNING_RATE * grad / sqrt(g2[b] + 1e-8)
                }
                g2[dims] += err * err
                w[dims] -= LEARNING_RATE * err / sqrt(g2[dims] + 1e-8)
            }
        }
        return w
    }

    private fun logitFloat(w: DoubleArray, bits: Int, s: Sample): Double {
        val mask = (1 shl bits) - 1
        var z = w[1 shl bits]
        for (h in s.hashes) z += if (h < 0) -w[h and mask] else w[h and mask]
        return z
    }

    /** Picks the 8-bit scale (max, or a high percentile with clipping) with the best validation log-loss. */
    private fun quantize(w: DoubleArray, bits: Int, validation: List<Sample>): Pair<Double, ByteArray> {
        val dims = 1 shl bits
        val magnitudes = (0 until dims).map { abs(w[it]) }.filter { it > 0 }.sorted()
        val options = listOf(1.0, 0.9999, 0.999).map { pct -> magnitudes[((magnitudes.size - 1) * pct).roundToInt()] / 127.0 }.distinct()
        return options.map { scale ->
            val q = ByteArray(dims) { (w[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }
            val mask = dims - 1
            val loss = logLoss(validation) { s ->
                var sum = 0L
                for (h in s.hashes) { val v = q[h and mask].toInt(); sum += if (h < 0) -v else v }
                w[dims] + scale * sum
            }
            Triple(scale, q, loss)
        }.minWith(compareBy<Triple<Double, ByteArray, Double>> { it.third }.thenBy { it.first }).let { it.first to it.second }
    }

    private inline fun logLoss(samples: List<Sample>, logit: (Sample) -> Double): Double {
        var total = 0.0
        for (s in samples) {
            val p = LinearModel.sigmoid(logit(s)).coerceIn(1e-12, 1 - 1e-12)
            total -= if (s.y == 1) ln(p) else ln(1 - p)
        }
        return total / samples.size.coerceAtLeast(1)
    }

    /** Lowest logit at which everything scored at or above it reaches [precision] on validation. */
    private fun threshold(scored: List<Pair<Double, Int>>, precision: Double): Double {
        val sorted = scored.sortedWith(compareByDescending<Pair<Double, Int>> { it.first }.thenBy { it.second })
        var tp = 0
        var best = sorted.firstOrNull()?.first ?: 0.0
        for ((i, pair) in sorted.withIndex()) {
            if (pair.second == 1) tp++
            val isLastOfTie = i == sorted.size - 1 || sorted[i + 1].first != pair.first
            if (isLastOfTie && tp.toDouble() / (i + 1) >= precision) best = pair.first
        }
        return best
    }

    class Metrics(scored: List<Pair<Double, Int>>, mediumLogit: Double, highLogit: Double) {
        class At(val precision: Double, val recall: Double, val fpr: Double)

        val positives = scored.count { it.second == 1 }
        val negatives = scored.size - positives
        val medium = at(scored, mediumLogit)
        val high = at(scored, highLogit)
        val auc: Double = run {
            // Rank-sum (Mannâ€“Whitney) AUC with averaged ranks for ties.
            val sorted = scored.sortedBy { it.first }
            var rankSum = 0.0
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1].first == sorted[i].first) j++
                val avgRank = (i + j) / 2.0 + 1
                for (k in i..j) if (sorted[k].second == 1) rankSum += avgRank
                i = j + 1
            }
            (rankSum - positives * (positives + 1) / 2.0) / (positives.toDouble() * negatives).coerceAtLeast(1.0)
        }

        private fun at(scored: List<Pair<Double, Int>>, t: Double): At {
            val flagged = scored.filter { it.first >= t }
            val tp = flagged.count { it.second == 1 }
            val fp = flagged.size - tp
            return At(tp.toDouble() / flagged.size.coerceAtLeast(1), tp.toDouble() / positives.coerceAtLeast(1), fp.toDouble() / negatives.coerceAtLeast(1))
        }

        fun summary() = "test AUC ${fmt(auc)} Â· high: precision ${fmt(high.precision)} recall ${fmt(high.recall)} FPR ${fmt(high.fpr)} Â· " +
            "medium: precision ${fmt(medium.precision)} recall ${fmt(medium.recall)} FPR ${fmt(medium.fpr)} ($positives positive / $negatives negative)"
    }

    /** SplitMix64 (Steele, Lea & Flood 2014): tiny, fast, and fully specified, so the shuffle is reproducible. */
    private class SplitMix64(private var state: Long) {
        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()
    }

    companion object {
        const val TRAIN = 0
        const val VALIDATION = 1
        const val TEST = 2
        private const val EPOCHS = 6
        private const val LEARNING_RATE = 0.2

        /** Normalised text used for de-duplication and for the split: case- and whitespace-insensitive. */
        fun messageKey(text: String) = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        /** 80/10/10 by a stable hash, so a sample's split never depends on file order. */
        fun splitOf(key: String): Int = when (Math.floorMod(FeatureHash.hash('s', key), 10)) {
            in 0..7 -> TRAIN
            8 -> VALIDATION
            else -> TEST
        }

        fun fmt(d: Double): String = String.format(Locale.ROOT, "%.4f", d)
    }
}
