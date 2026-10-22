package lian.core.engine.message

import lian.core.engine.Confidence
import lian.core.engine.EvalContext
import lian.core.engine.Evidence
import lian.core.engine.Finding
import lian.core.engine.Rule
import lian.core.engine.RuleId
import lian.core.engine.RuleSet
import lian.core.engine.Scoring
import lian.core.engine.Severity
import lian.core.engine.link.LinkAnalyzer
import lian.core.engine.link.LinkBand
import lian.core.engine.link.LinkCheckResult
import lian.core.engine.ml.LinearModel
import lian.core.engine.ml.MessageFeaturizer
import lian.core.engine.ml.MlText
import java.text.Normalizer

/** The bundled data the message rules consult. */
interface MessageIntel {
    /** On-device message model (MessageFeaturizer input), or null when not bundled. */
    val messageModel: LinearModel?
}

/** Facts about a message's wording, computed once. Links are removed first: they're judged by the link rules. */
data class MessageFacts(
    val tokens: List<String>,
    val words: Set<String>,
    val hasMoney: Boolean,
    val hasPhoneNumber: Boolean,
    /** Model output; null when the text is too short or not mainly in Latin script (the model is English-only). */
    val prediction: LinearModel.Prediction?,
)

data class MessageReport(
    val findings: List<Finding>,
    val score: Int,
    val band: LinkBand,
    /** True when the model was applicable; false for short or non-English text. */
    val modelApplied: Boolean,
)

/** One check of pasted or shared text: the wording of the message and every link in it. */
data class TextCheckResult(
    val links: LinkCheckResult,
    /** Null when the text is essentially just a link (nothing to judge in the wording). */
    val message: MessageReport?,
    val score: Int,
    val band: LinkBand,
)

/**
 * Judges what a message *asks you to do*. Hand-written rules catch the requests behind most scams
 * (share a code, pay a small fee, buy gift cards, call back about a charge); the on-device model adds
 * a learned "reads like known scam texts" signal. Token matching only: no regular expressions run on
 * the message beyond one linear tokenizer.
 */
class MessageAnalyzer(private val intel: MessageIntel) {
    val rules = MessageRules.create()

    fun analyze(text: String, ctx: EvalContext): MessageReport? {
        val facts = facts(text) ?: return null
        val findings = rules.evaluate(facts, ctx)
        val score = Scoring.riskScore(findings)
        return MessageReport(findings, score, LinkBand.of(score), facts.prediction != null)
    }

    internal fun facts(text: String): MessageFacts? {
        val withoutLinks = text.take(MAX_CHARS).split(WHITESPACE)
            .filterNot { chunk -> chunk.trim(*EDGE).let { MessageFeaturizer.looksLikeUrl(it.lowercase()) || MessageFeaturizer.looksLikeEmail(it) } }
            .joinToString(" ")
        val normalized = Normalizer.normalize(withoutLinks, Normalizer.Form.NFKC).lowercase()
        val tokens = normalized.split(NON_WORD).filter { it.isNotEmpty() }
        val wordCount = tokens.count { t -> t.any { it.isLetter() } }
        if (wordCount < MIN_WORDS) return null

        val letters = normalized.count { it.isLetter() }
        val latin = normalized.count { it in 'a'..'z' }
        val english = letters > 0 && latin * 10 >= letters * 7
        val prediction = if (english) intel.messageModel?.predict(MessageFeaturizer, text) else null

        return MessageFacts(
            tokens = tokens,
            words = tokens.toSet(),
            hasMoney = normalized.any { it in CURRENCY } || tokens.any { it in MONEY_WORDS },
            hasPhoneNumber = hasPhoneNumber(withoutLinks),
            prediction = prediction,
        )
    }

    /** Nine or more digits with at most single separators between them: `0203 912 3456`, `+1-888-555-0199`. */
    private fun hasPhoneNumber(text: String): Boolean {
        var digits = 0
        var separators = 0
        for (c in text) {
            when {
                c in '0'..'9' -> { digits++; separators = 0; if (digits >= 9) return true }
                c in " -.()+" && digits > 0 -> { separators++; if (separators > 1) digits = 0 }
                else -> { digits = 0; separators = 0 }
            }
        }
        return false
    }

    companion object {
        const val MAX_CHARS = 4_000
        const val MIN_WORDS = 4
        private val WHITESPACE = Regex("\\s+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private val EDGE = charArrayOf('(', ')', '[', ']', '<', '>', '"', '\'', ',', '.', ';', ':', '!', '?')
        private val CURRENCY = setOf('£', '$', '€', '₹', '¥', '₦', '₱')
        private val MONEY_WORDS = setOf("rs", "inr", "usd", "gbp", "eur", "dollars", "pounds", "rupees", "euros", "naira", "pesos")
    }
}

/** Runs the message rules and the link rules over the same text and combines them into one verdict. */
class TextAnalyzer(private val links: LinkAnalyzer, private val messages: MessageAnalyzer) {
    fun analyze(text: String, ctx: EvalContext): TextCheckResult {
        val linkResult = links.analyze(text, ctx)
        val message = messages.analyze(text, ctx)
        // Wording and the riskiest link are independent evidence about the same message: score them together.
        val worst = linkResult.reports.maxByOrNull { it.score }
        val combined = if (message != null) Scoring.riskScore(message.findings + worst?.findings.orEmpty()) else 0
        val score = maxOf(linkResult.score, message?.score ?: 0, combined)
        return TextCheckResult(linkResult, message, score, LinkBand.of(score))
    }
}

class MessageRule(
    id: String,
    override val version: Int,
    private val severity: Severity,
    private val weight: Int,
    private val confidence: Confidence,
    private val group: String? = null,
    override val needsCorroboration: Boolean = false,
    private val test: (MessageFacts) -> List<Evidence>?,
) : Rule<MessageFacts> {
    override val id = RuleId(id)

    override fun evaluate(facts: MessageFacts, ctx: EvalContext): Finding? {
        val evidence = test(facts) ?: return null
        return Finding(this.id, version, severity, weight, confidence, evidence, correlationGroup = group)
    }
}

/** The message rule catalogue. English wording; weights are set by people and changing one changes [VERSION]. */
object MessageRules {
    const val VERSION = "msg-2026.10.1"

    private const val WORDING = "message wording"
    private const val MODEL = "on-device model message.vgm"

    private fun words(vararg found: String?) = listOf(Evidence("words", found.filterNotNull().distinct().joinToString(", ") { "\"$it\"" }, WORDING))

    private fun List<String>.hasPhrase(phrase: List<String>): Boolean {
        if (phrase.size > size) return false
        outer@ for (i in 0..size - phrase.size) {
            for (j in phrase.indices) if (this[i + j] != phrase[j]) continue@outer
            return true
        }
        return false
    }

    private fun MessageFacts.firstPhrase(phrases: List<List<String>>): String? = phrases.firstOrNull { tokens.hasPhrase(it) }?.joinToString(" ")
    private fun MessageFacts.first(set: Set<String>): String? = tokens.firstOrNull { it in set }

    private fun modelEvidence(p: LinearModel.Prediction) = listOf(
        Evidence("similar to known scam and spam texts", "${p.percent}/100", MODEL),
        Evidence("main signals", MlText.reasons(p), MODEL),
    )

    fun create(): RuleSet<MessageFacts> = RuleSet(
        VERSION,
        listOf(
            MessageRule("msg.gift_card", 1, Severity.HIGH, 50, Confidence.LIKELY) { f ->
                val card = f.firstPhrase(GIFT_CARD_PHRASES) ?: f.first(setOf("giftcard", "giftcards")) ?: return@MessageRule null
                f.first(GIFT_CARD_ACTIONS)?.let { words(card, it) }
            },
            MessageRule("msg.code_request", 1, Severity.HIGH, 45, Confidence.LIKELY, group = "credential") { f -> codeRequest(f.tokens)?.let { words(*it) } },
            MessageRule("msg.authority_threat", 1, Severity.HIGH, 40, Confidence.LIKELY) { f ->
                val threat = f.first(AUTHORITY) ?: return@MessageRule null
                f.first(AUTHORITY_DEMANDS)?.let { words(threat, it) }
            },
            MessageRule("msg.family_new_number", 1, Severity.HIGH, 40, Confidence.LIKELY) { f ->
                val phone = f.firstPhrase(NEW_PHONE) ?: return@MessageRule null
                (f.first(FAMILY) ?: f.first(FAVOUR))?.let { words(phone, it) }
            },
            MessageRule("msg.fee_delivery", 1, Severity.HIGH, 40, Confidence.LIKELY) { f ->
                val delivery = f.first(DELIVERY) ?: return@MessageRule null
                val fee = f.first(FEES) ?: if (f.hasMoney) f.first(PAY) else null
                fee?.let { words(delivery, it) }
            },
            MessageRule("msg.account_threat", 1, Severity.MEDIUM, 35, Confidence.LIKELY) { f ->
                val thing = f.first(ACCOUNT_THINGS) ?: return@MessageRule null
                val threat = f.first(ACCOUNT_THREATS) ?: return@MessageRule null
                f.first(ACCOUNT_ACTIONS)?.let { words(thing, threat, it) }
            },
            MessageRule("msg.prize", 1, Severity.MEDIUM, 30, Confidence.LIKELY) { f ->
                val hits = f.tokens.filter { it in PRIZE }.distinct()
                if (hits.size >= 2) words(*hits.take(3).toTypedArray()) else null
            },
            MessageRule("msg.crypto", 1, Severity.MEDIUM, 30, Confidence.LIKELY) { f ->
                val coin = f.first(CRYPTO) ?: return@MessageRule null
                f.first(CRYPTO_LURES)?.let { words(coin, it) }
            },
            MessageRule("msg.ml.pattern", 1, Severity.MEDIUM, 35, Confidence.INDICATOR, group = "ml") { f ->
                f.prediction?.takeIf { it.level == LinearModel.Level.HIGH }?.let(::modelEvidence)
            },
            MessageRule("msg.ml.pattern_weak", 1, Severity.LOW, 20, Confidence.INDICATOR, group = "ml", needsCorroboration = true) { f ->
                f.prediction?.takeIf { it.level == LinearModel.Level.MEDIUM }?.let(::modelEvidence)
            },
            // Genuine banks also say "not you? call us", so this only counts alongside another warning sign.
            MessageRule("msg.callback", 1, Severity.LOW, 20, Confidence.INDICATOR, needsCorroboration = true) { f ->
                val phrase = f.firstPhrase(NOT_YOU) ?: f.first(setOf("unauthorised", "unauthorized")) ?: return@MessageRule null
                val call = f.first(setOf("call", "ring", "dial", "contact")) ?: return@MessageRule null
                if (f.hasPhoneNumber) words(phrase, call) + Evidence("phone number", "present", WORDING) else null
            },
            MessageRule("msg.urgency", 1, Severity.LOW, 15, Confidence.INDICATOR, needsCorroboration = true) { f ->
                (f.firstPhrase(URGENT_PHRASES) ?: f.first(URGENT_WORDS))?.let { words(it) }
            },
        ),
    )

    /** "send/share/forward … code" with no negation just before the verb ("don't share this code" is fine). */
    private fun codeRequest(t: List<String>): Array<String>? {
        for (i in t.indices) {
            if (t[i] !in REQUEST_VERBS) continue
            if ((maxOf(0, i - 3) until i).any { t[it] in NEGATIONS }) continue
            val noun = (maxOf(0, i - 8)..minOf(t.size - 1, i + 6)).map { t[it] }.firstOrNull { it in CODE_NOUNS } ?: continue
            return arrayOf(t[i], noun)
        }
        return null
    }

    private fun phrases(vararg p: String) = p.map { it.split(' ') }

    private val GIFT_CARD_PHRASES = phrases(
        "gift card", "gift cards", "itunes card", "itunes cards", "steam card", "steam cards", "play card", "play cards",
        "apple card", "apple cards", "amazon card", "amazon cards", "ebay card", "ebay cards", "gift voucher", "gift vouchers",
    )
    private val GIFT_CARD_ACTIONS = setOf("buy", "purchase", "get", "send", "codes", "code", "pick", "claim", "scratch")
    private val REQUEST_VERBS = setOf("send", "share", "forward", "tell", "give", "read", "provide")
    private val NEGATIONS = setOf("not", "never", "don", "dont", "didn", "no", "won", "nobody")
    private val CODE_NOUNS = setOf("code", "codes", "otp", "pin", "passcode", "password", "cvv")
    private val AUTHORITY = setOf("arrest", "arrested", "police", "warrant", "court", "lawsuit", "prosecution", "prosecute", "prosecuted", "jail", "bail")
    private val AUTHORITY_DEMANDS = setOf("call", "contact", "pay", "immediately", "avoid", "urgently", "press", "officer")
    private val NEW_PHONE = phrases(
        "new number", "new phone", "lost my phone", "dropped my phone", "broke my phone", "phone is broken", "phone broke",
        "friend s phone", "friends phone", "phone got stolen", "phone was stolen",
    )
    private val FAMILY = setOf("mum", "mom", "mummy", "mommy", "dad", "daddy", "mother", "father", "son", "daughter", "grandma", "grandpa", "nan", "nana", "granny", "auntie")
    private val FAVOUR = setOf("help", "favour", "favor", "money", "pay", "send", "transfer", "bill", "urgent", "urgently", "whatsapp", "banking")
    private val DELIVERY = setOf(
        "parcel", "parcels", "package", "packages", "shipment", "delivery", "redelivery", "courier", "customs", "toll", "tolls",
        "shipping", "postage", "consignment",
    )
    private val FEES = setOf("fee", "fees", "duty", "unpaid", "outstanding", "vat", "clearance")
    private val PAY = setOf("pay", "payment", "paid", "settle")
    private val ACCOUNT_THINGS = setOf(
        "account", "card", "membership", "subscription", "sim", "kyc", "netbanking", "banking", "id", "wallet", "licence",
        "license", "service", "connection", "electricity", "power", "number", "profile", "mailbox", "storage", "access",
        "registration", "aadhaar", "pan",
    )
    private val ACCOUNT_THREATS = setOf(
        "suspended", "suspend", "suspension", "locked", "blocked", "block", "disabled", "deactivated", "deactivate",
        "restricted", "limited", "closed", "terminated", "expire", "expired", "expires", "disconnected", "disconnection",
        "deleted", "compromised", "flagged", "hold", "frozen",
    )
    private val ACCOUNT_ACTIONS = setOf(
        "update", "verify", "confirm", "click", "tap", "call", "contact", "login", "log", "sign", "reactivate", "restore",
        "unlock", "avoid", "immediately", "now", "today", "tonight", "within",
    )
    private val PRIZE = setOf(
        "won", "winner", "winners", "win", "prize", "prizes", "congratulations", "congrats", "selected", "lucky", "draw",
        "reward", "rewards", "claim", "jackpot", "lottery", "free",
    )
    private val CRYPTO = setOf("bitcoin", "btc", "usdt", "crypto", "cryptocurrency", "ethereum", "eth", "binance", "coinbase", "wallet")
    private val CRYPTO_LURES = setOf(
        "invest", "investment", "profit", "profits", "double", "earn", "returns", "mining", "guaranteed", "seed", "phrase",
        "withdraw", "withdrawal", "trading",
    )
    private val NOT_YOU = phrases(
        "if not you", "not you", "wasn t you", "was not you", "didn t make", "did not make", "not done by you",
        "didn t place", "did not place", "not authorised", "not authorized", "to cancel",
    )
    private val URGENT_PHRASES = phrases(
        "within 24 hours", "within 12 hours", "within 48 hours", "within 24h", "final notice", "last chance", "act now",
        "expires today", "right away", "before midnight", "final reminder",
    )
    private val URGENT_WORDS = setOf("urgent", "urgently", "immediately", "asap", "tonight")
}
