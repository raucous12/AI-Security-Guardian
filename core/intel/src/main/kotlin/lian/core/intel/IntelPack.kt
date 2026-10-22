package lian.core.intel

import lian.core.engine.app.AppIntel
import lian.core.engine.app.Indicator
import lian.core.engine.app.IndicatorSet
import lian.core.engine.app.OsSupport
import lian.core.engine.app.Store
import lian.core.engine.app.StoreCatalog
import lian.core.engine.device.AttestationTrust
import lian.core.engine.link.Brand
import lian.core.engine.link.BrandCatalog
import lian.core.engine.link.ConfusableMap
import lian.core.engine.link.IndexedBrandCatalog
import lian.core.engine.link.LinkIntel
import lian.core.engine.link.PremiumPrefix
import lian.core.engine.link.PublicSuffixList
import lian.core.engine.link.TldRisk
import lian.core.engine.link.skeleton
import lian.core.engine.message.MessageIntel
import lian.core.engine.ml.LinearModel
import java.io.ByteArrayInputStream
import java.lang.Character.UnicodeScript
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64

class IntelIntegrityException(message: String) : IllegalStateException(message)

/**
 * The bundled intel data. Every file is checked against the SHA-256 manifest generated at build time,
 * so damaged or altered data (including the two on-device models) is refused rather than silently
 * producing wrong verdicts.
 */
class IntelPack private constructor(
    override val version: String,
    override val publicSuffixList: PublicSuffixList,
    private val confusableTable: Map<Int, String>,
    override val brands: BrandCatalog,
    private val shorteners: Set<String>,
    private val tldRisks: Map<String, TldRisk>,
    private val premium: List<PremiumPrefix>,
    private val scriptTlds: Map<UnicodeScript, Set<String>>,
    override val messageModel: LinearModel,
    override val domainModel: LinearModel,
    override val indicators: IndicatorSet,
    private val vendorSites: Map<String, String>,
    override val stores: StoreCatalog,
    private val osTable: Map<Int, Pair<OsSupport, String>>,
    override val rootPublicKeys: List<ByteArray>,
    private val revokedSerials: Set<String>,
) : LinkIntel, MessageIntel, AppIntel, AttestationTrust {

    override val confusables = ConfusableMap { confusableTable[it] }

    val confusableCount: Int get() = confusableTable.size
    val revokedCount: Int get() = revokedSerials.size

    override fun isShortener(registrable: String) = registrable in shorteners

    override fun tldRisk(tld: String) = tldRisks[tld]

    override fun premiumPrefix(e164: String) = premium.firstOrNull { e164.startsWith(it.prefix) }

    override fun tldsForScript(script: UnicodeScript) = scriptTlds[script].orEmpty()

    override fun stalkerwareVendor(registrable: String): String? = vendorSites[registrable]

    override fun osSupport(sdkInt: Int): Pair<OsSupport, String> =
        osTable[sdkInt] ?: if (sdkInt > (osTable.keys.maxOrNull() ?: 0)) OsSupport.SUPPORTED to "Android (API $sdkInt)" else OsSupport.ENDED to "Android (API $sdkInt)"

    override fun isRevoked(serialHex: String): Boolean = serialHex.lowercase() in revokedSerials

    companion object {
        private const val ROOT = "/lian/intel/"

        fun load(): IntelPack = load(::resource)

        /** [read] returns the bytes of a packaged data file; tests substitute it to prove tampering is refused. */
        internal fun load(read: (String) -> ByteArray): IntelPack {
            val manifestText = read("manifest.tsv").toString(Charsets.UTF_8)
            val manifest = rows(manifestText).associate { it[0] to it[1] }

            fun verifiedBytes(name: String): ByteArray {
                val bytes = read(name)
                val expected = manifest[name] ?: throw IntelIntegrityException("$name is not in the intel manifest")
                val actual = sha256(bytes)
                if (actual != expected) throw IntelIntegrityException("$name: SHA-256 $actual does not match manifest $expected")
                return bytes
            }
            fun verified(name: String) = verifiedBytes(name).toString(Charsets.UTF_8)

            // The two large files (~10k and ~6k lines) are parsed without per-line list allocations:
            // on a cold, debuggable runtime the generic row parser dominated start-up time.
            val pslRules = ArrayList<Pair<String, PublicSuffixList.Section>>(12_000)
            forEachDataLine(verified("psl.txt")) { line ->
                val section = if (line[0] == 'P') PublicSuffixList.Section.PRIVATE else PublicSuffixList.Section.ICANN
                pslRules += line.substring(2) to section
            }
            val psl = PublicSuffixList(pslRules)

            val confusableTable = HashMap<Int, String>(8_192)
            forEachDataLine(verified("confusables.tsv")) { line ->
                // substring + toInt rather than Integer.parseInt(CharSequence, …): that overload is API 33+.
                val tab = line.indexOf('\t')
                val source = line.substring(0, tab).toInt(16)
                val prototype = StringBuilder(4)
                var start = tab + 1
                while (start < line.length) {
                    val end = line.indexOf(' ', start).let { if (it < 0) line.length else it }
                    prototype.appendCodePoint(line.substring(start, end).toInt(16))
                    start = end + 1
                }
                confusableTable[source] = prototype.toString()
            }
            val confusables = ConfusableMap { confusableTable[it] }

            val brandList = rows(verified("brands.tsv")).map { c ->
                val label = c[0]
                Brand(
                    label = label,
                    skeleton = skeleton(label, confusables),
                    officialDomains = csv(c[1]),
                    ownsCountryDomains = c[2] == "yes",
                    packages = csv(c.getOrElse(3) { "" }),
                    notConfusableWith = csv(c.getOrElse(4) { "" }),
                    category = c.getOrElse(5) { "" },
                )
            }
            val tldRisks = rows(verified("tld_risk.tsv")).associate { (tld, weight, note) -> tld to TldRisk(tld, weight.toInt(), note) }
            val premium = rows(verified("premium_numbers.tsv")).map { (prefix, region, description) -> PremiumPrefix(prefix, region, description) }
            val scriptTlds = rows(verified("script_tlds.tsv")).associate { (script, tlds) -> UnicodeScript.valueOf(script) to csv(tlds) }
            val shorteners = verified("shorteners.txt").lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

            fun model(name: String, featurizer: String): LinearModel {
                val m = try { LinearModel.decode(verifiedBytes(name)) } catch (e: IllegalArgumentException) { throw IntelIntegrityException("$name: ${e.message}") }
                if (m.featurizerId != featurizer) throw IntelIntegrityException("$name expects ${m.featurizerId}, app provides $featurizer")
                return m
            }

            val indicators = rows(verified("stalkerware.tsv")).map { c ->
                Indicator(
                    type = if (c[0] == "watchware") Indicator.Type.WATCHWARE else Indicator.Type.STALKERWARE,
                    name = c[1],
                    packages = csv(c.getOrElse(2) { "" }),
                    certSha1 = csv(c.getOrElse(3) { "" }),
                    websites = csv(c.getOrElse(4) { "" }),
                )
            }
            // Vendor sites for link checks: stalkerware only (watchware sites belong to parental-control
            // vendors), reduced to registrable domains, never a domain a listed brand officially owns.
            val official = brandList.flatMap { it.officialDomains }.toSet()
            val vendorSites = LinkedHashMap<String, String>()
            indicators.filter { it.type == Indicator.Type.STALKERWARE }.forEach { i ->
                i.websites.forEach { host ->
                    psl.match(host.substringBefore('/'))?.registrable?.takeIf { it !in official }?.let { vendorSites.putIfAbsent(it, i.name) }
                }
            }

            val stores = rows(verified("stores.tsv")).map { (kind, pkg, name, reviewed, system) ->
                Store(Store.Kind.valueOf(kind.uppercase()), pkg, name, reviewed == "yes", system == "yes")
            }
            val osTable = rows(verified("os_support.tsv")).associate { (sdk, status, name) -> sdk.toInt() to (OsSupport.valueOf(status.uppercase()) to name) }

            val factory = CertificateFactory.getInstance("X.509")
            val roots = Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----")
                .findAll(verified("attestation_roots.pem"))
                .map { m -> factory.generateCertificate(ByteArrayInputStream(Base64.getMimeDecoder().decode(m.groupValues[1]))).publicKey.encoded }
                .toList()
            if (roots.isEmpty()) throw IntelIntegrityException("no attestation roots")
            val revoked = rows(verified("attestation_revoked.tsv")).map { it[0].lowercase() }.toSet()

            return IntelPack(
                version = "intel-" + sha256(manifestText.toByteArray()).take(12),
                publicSuffixList = psl,
                confusableTable = confusableTable,
                brands = IndexedBrandCatalog(brandList),
                shorteners = shorteners,
                tldRisks = tldRisks,
                premium = premium,
                scriptTlds = scriptTlds,
                messageModel = model("message.vgm", "msg-v1"),
                domainModel = model("domain.vgm", "dom-v1"),
                indicators = IndicatorSet(indicators),
                vendorSites = vendorSites,
                stores = StoreCatalog(stores),
                osTable = osTable,
                rootPublicKeys = roots,
                revokedSerials = revoked,
            )
        }

        private fun resource(name: String): ByteArray =
            IntelPack::class.java.getResourceAsStream(ROOT + name)?.use { it.readBytes() }
                ?: throw IntelIntegrityException("intel resource $name is missing from the build")

        private inline fun forEachDataLine(text: String, action: (String) -> Unit) {
            var start = 0
            while (start < text.length) {
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                if (end > start && text[start] != '#') action(text.substring(start, end).trimEnd('\r'))
                start = end + 1
            }
        }

        private fun rows(text: String): List<List<String>> =
            text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line -> line.split('\t').map { it.trim() } }.toList()

        private fun csv(field: String): Set<String> = field.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        /** Locale-independent hex (String.format would localise digits in some locales). */
        private fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = "0123456789abcdef"
            return buildString(digest.size * 2) {
                for (b in digest) append(hex[(b.toInt() shr 4) and 0xF]).append(hex[b.toInt() and 0xF])
            }
        }
    }
}
