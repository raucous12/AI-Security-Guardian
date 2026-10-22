package lian.core.intel

import org.junit.jupiter.api.Test
import lian.core.engine.EvalContext
import lian.core.engine.app.OsSupport
import lian.core.engine.link.LinkAnalyzer
import lian.core.engine.link.LinkBand
import lian.core.engine.message.MessageAnalyzer
import lian.core.engine.message.TextAnalyzer
import lian.core.engine.ml.DomainFeaturizer
import lian.core.engine.ml.LinearModel.Level
import lian.core.engine.ml.MessageFeaturizer
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The shipped models and the data added for the app audit, checked against behaviour a person can judge. */
class BundledModelsTest {
    private val intel = IntelPack.load()
    private val ctx = EvalContext(nowEpochMs = 0)

    private fun msg(text: String) = intel.messageModel.predict(MessageFeaturizer, text)
    private fun dom(label: String) = intel.domainModel.predict(DomainFeaturizer, label)

    @Test
    fun `message model separates obvious scams from everyday texts`() {
        val scams = listOf(
            "URGENT! You have won a £1000 cash prize. To claim call 09061701461 now. T&Cs apply",
            "Your parcel could not be delivered. Pay the £1.99 redelivery fee within 24 hours: https://royalmail-fee.top/uk",
            "Congratulations, you've been selected for a free iPhone 16. Claim here: http://bit.ly/x",
        )
        val everyday = listOf(
            "Are we still on for dinner tonight? I can pick you up at 7",
            "123456 is your verification code. Don't share it with anyone.",
            "Running late, the train is delayed. See you in 20 minutes",
            "Happy birthday! Hope you have a lovely day xx",
        )
        scams.forEach { assertTrue(msg(it).level != Level.NONE, "missed: $it (${msg(it)})") }
        everyday.forEach { assertEquals(Level.NONE, msg(it).level, "false alarm: $it (${msg(it)})") }
        assertTrue(msg(scams[0]).reasons.isNotEmpty())
    }

    @Test
    fun `domain model scores invented phishing names above ordinary ones`() {
        val phishy = listOf("secure-paypal-login-verify", "appleid-unlock-support-center", "microsoft365-auth-update")
        val ordinary = listOf("google", "wikipedia", "bbc", "nytimes", "github", "ikea", "mozilla")
        val worstOrdinary = ordinary.maxOf { dom(it).logit }
        phishy.forEach { assertTrue(dom(it).logit > worstOrdinary, "$it scored ${dom(it).logit}, ordinary max $worstOrdinary") }
        ordinary.forEach { assertEquals(Level.NONE, dom(it).level, "false alarm on $it") }
    }

    @Test
    fun `model metadata records provenance and test metrics`() {
        for (m in listOf(intel.messageModel, intel.domainModel)) {
            assertTrue(m.metadata["sources"]!!.isNotEmpty())
            assertTrue(m.metadata["test.auc"]!!.toDouble() > 0.8, "AUC ${m.metadata["test.auc"]}")
            assertTrue(m.mediumLogit <= m.highLogit)
        }
        assertTrue(intel.messageModel.metadata["test.high.precision"]!!.toDouble() >= 0.95)
    }

    @Test
    fun `models make the text check stronger without overruling the rules`() {
        val analyzer = TextAnalyzer(LinkAnalyzer(intel), MessageAnalyzer(intel))
        val scam = analyzer.analyze("Evri: your parcel is on hold. Pay the unpaid fee of £1.45 now: https://evri-parcel-redelivery.top/pay", ctx)
        assertTrue(scam.band >= LinkBand.LIKELY_SCAM, "score ${scam.score}")
        val official = analyzer.analyze("Your Amazon order has shipped. Track it at https://www.amazon.co.uk/orders", ctx)
        assertEquals(LinkBand.NO_WARNING_SIGNS, official.band, official.toString())
    }

    @Test
    fun `stalkerware indicators load and vendor sites resolve to registrable domains`() {
        assertTrue(intel.indicators.all.size > 100, "indicators ${intel.indicators.all.size}")
        assertTrue(intel.indicators.packageCount > 300 && intel.indicators.certificateCount > 200)
        assertEquals("TheTruthSpy", intel.indicators.byPackage("com.thetruth")?.name)
        assertEquals("TheTruthSpy", intel.stalkerwareVendor("thetruthspy.com"))
        // A watchware (parental-control) vendor on a big platform's domain must never become a "stalkerware site".
        assertEquals(null, intel.stalkerwareVendor("microsoft.com"))
        val link = LinkAnalyzer(intel).analyze("https://thetruthspy.com/pricing", ctx)
        assertTrue(link.reports.single().findings.any { it.rule.value == "link.stalkerware.site" })
    }

    @Test
    fun `attestation roots are genuine self-signed Google roots`() {
        val pem = javaClass.getResourceAsStream("/lian/intel/attestation_roots.pem")!!.readBytes().toString(Charsets.UTF_8)
        val certs = Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----").findAll(pem).map {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(java.util.Base64.getMimeDecoder().decode(it.groupValues[1]))) as X509Certificate
        }.toList()
        assertEquals(2, certs.size)
        certs.forEach { it.verify(it.publicKey) }   // throws if a single byte of the copied PEM were wrong
        assertEquals(2, intel.rootPublicKeys.size)
        assertTrue(intel.revokedCount > 500)
    }

    @Test
    fun `os support table and store catalogue`() {
        assertEquals(OsSupport.ENDED, intel.osSupport(33).first)
        assertEquals(OsSupport.ENDING, intel.osSupport(34).first)
        assertEquals(OsSupport.SUPPORTED, intel.osSupport(36).first)
        assertEquals(OsSupport.SUPPORTED, intel.osSupport(40).first)   // newer than the table
        assertEquals("Google Play", intel.stores["com.android.vending"]?.name)
    }

    @Test
    fun `a tampered model is refused`() {
        val genuine = { name: String -> javaClass.getResourceAsStream("/lian/intel/$name")!!.readBytes() }
        assertFailsWith<IntelIntegrityException> {
            IntelPack.load { name -> if (name == "message.vgm") genuine(name).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } else genuine(name) }
        }
    }
}
