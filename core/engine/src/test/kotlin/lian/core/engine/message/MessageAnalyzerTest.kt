package lian.core.engine.message

import org.junit.jupiter.api.Test
import lian.core.engine.EvalContext
import lian.core.engine.TestIntel
import lian.core.engine.link.LinkAnalyzer
import lian.core.engine.link.LinkBand
import lian.core.engine.ml.LinearModel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageAnalyzerTest {
    private val noModel = object : MessageIntel { override val messageModel: LinearModel? = null }
    private val analyzer = MessageAnalyzer(noModel)
    private val ctx = EvalContext(nowEpochMs = 0)

    private fun rules(text: String) = analyzer.analyze(text, ctx)?.findings?.map { it.rule.value }.orEmpty()

    @Test
    fun `asking for a one-time code is flagged, the code message itself is not`() {
        assertTrue("msg.code_request" in rules("Sorry, I sent you a 6 digit code by mistake. Can you please send it back to me?"))
        assertTrue("msg.code_request" in rules("To cancel the transaction please share the OTP you just received"))
        assertTrue("msg.code_request" !in rules("123456 is your Google verification code. Don't share it with anyone."))
        assertTrue("msg.code_request" !in rules("HDFC Bank: OTP for txn of Rs 1,250 is 482910. Do not share this OTP with anyone."))
        assertTrue("msg.code_request" !in rules("Your WhatsApp code: 482-917. Never share this code with others."))
    }

    @Test
    fun `parcel fee, gift card and family scams`() {
        assertTrue("msg.fee_delivery" in rules("Royal Mail: your parcel is held due to an unpaid shipping fee of £1.45"))
        assertTrue("msg.fee_delivery" !in rules("Your Royal Mail parcel is due for delivery today between 11:30 and 13:30."))
        assertTrue("msg.gift_card" in rules("Are you free? I need you to buy Google Play gift cards for a client"))
        assertTrue("msg.family_new_number" in rules("Hi Mum, I dropped my phone in the toilet, this is my new number"))
        assertTrue("msg.family_new_number" !in rules("Hey, it's Priya from work, this is my new number. See you at lunch!"))
    }

    @Test
    fun `account threats need a demand to act`() {
        assertTrue("msg.account_threat" in rules("Your account has been suspended. Verify your details now to restore access"))
        assertTrue("msg.account_threat" !in rules("Your card will expire soon. A replacement is on its way, no action needed."))
    }

    @Test
    fun `weak signals only count alongside a stronger one`() {
        // "urgent" alone is not a scam…
        assertTrue(rules("Urgent: please bring the signed form to the office before noon").isEmpty())
        // …but it adds weight when something else fired.
        val f = rules("URGENT: your account is locked. Call immediately to unlock it")
        assertTrue("msg.account_threat" in f && "msg.urgency" in f, f.toString())
    }

    @Test
    fun `bank callback lines are not flagged on their own`() {
        assertTrue(rules("HDFC Bank: Rs 2,500 debited from a/c XX1234. Not you? Call 18002586161").isEmpty())
    }

    @Test
    fun `short texts and bare links are left to the link check`() {
        assertNull(analyzer.analyze("ok see you", ctx))
        assertNull(analyzer.analyze("https://example.com/login", ctx))
    }

    @Test
    fun `links are removed before the wording is judged`() {
        // "fee" appears only in the URL; the wording itself asks for nothing.
        assertTrue("msg.fee_delivery" !in rules("Here is the parcel tracking page you asked for https://evri-redelivery-fee.top/track thanks"))
    }

    @Test
    fun `text analyzer combines wording with the riskiest link`() {
        val text = "Evri: your parcel is held, pay the unpaid fee of £0.99 now at http://paypal-secure.top/pay"
        val result = TextAnalyzer(LinkAnalyzer(TestIntel), analyzer).analyze(text, ctx)
        val msg = result.message
        requireNotNull(msg)
        assertTrue(result.score >= msg.score && result.score >= result.links.score)
        assertTrue(result.band >= LinkBand.LIKELY_SCAM, "band ${result.band} score ${result.score}")
    }

    @Test
    fun `the two models together can never push past Be careful on their own`() {
        // Fake models that score everything as a strong match.
        fun alwaysHigh(featurizer: String) = LinearModel(featurizer, 10, bias = 10.0, scale = 1.0, mediumLogit = 0.0, highLogit = 1.0, metadata = emptyMap(), weights = ByteArray(1 shl 10))
        val links = LinkAnalyzer(object : lian.core.engine.link.LinkIntel by TestIntel { override val domainModel = alwaysHigh("dom-v1") })
        val messages = MessageAnalyzer(object : MessageIntel { override val messageModel = alwaysHigh("msg-v1") })
        val result = TextAnalyzer(links, messages).analyze("Lunch at noon tomorrow? The menu is here https://example.com/menu", ctx)
        val fired = (result.message?.findings.orEmpty() + result.links.reports.flatMap { it.findings }).map { it.rule.value }
        assertTrue("msg.ml.pattern" in fired && "link.ml.domain" in fired, fired.toString())
        assertEquals(LinkBand.CAUTION, result.band, "score ${result.score}")
    }

    @Test
    fun `results are identical across runs`() {
        val text = "Congratulations! You have won a prize. Claim it within 24 hours"
        assertEquals(analyzer.analyze(text, ctx), analyzer.analyze(text, ctx))
    }
}
