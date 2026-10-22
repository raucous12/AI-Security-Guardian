package lian.core.engine.link

data class Brand(
    /** The brand's registrable label, e.g. `paypal`. */
    val label: String,
    val skeleton: String,
    val officialDomains: Set<String>,
    /** The brand holds its label across country-code TLDs (amazon.de, google.co.in, …). */
    val ownsCountryDomains: Boolean,
    val packages: Set<String>,
    /** Legitimate near-matches that must never be reported (e.g. `paypay` for `paypal`). */
    val notConfusableWith: Set<String>,
    val category: String,
) {
    fun isOfficial(registrable: String, publicSuffix: String): Boolean {
        if (registrable in officialDomains) return true
        if (!ownsCountryDomains || registrable.substringBefore('.') != label) return false
        val tld = publicSuffix.substringAfterLast('.')
        return tld.length == 2 && tld.all { it in 'a'..'z' }
    }
}

interface BrandCatalog {
    val all: List<Brand>
    fun bySkeleton(skeleton: String): List<Brand>
    fun bySkeletonLength(range: IntRange): List<Brand>
}

class IndexedBrandCatalog(brands: List<Brand>) : BrandCatalog {
    override val all: List<Brand> = brands.sortedBy { it.label }
    private val skeletonIndex = all.groupBy { it.skeleton }
    private val lengthIndex = all.groupBy { it.skeleton.length }

    override fun bySkeleton(skeleton: String): List<Brand> = skeletonIndex[skeleton].orEmpty()
    override fun bySkeletonLength(range: IntRange): List<Brand> = range.flatMap { lengthIndex[it].orEmpty() }
}

enum class BrandHitKind { UNOFFICIAL_DOMAIN, HOMOGRAPH, TYPOSQUAT, COMBOSQUAT }

data class BrandHit(val brand: Brand, val kind: BrandHitKind, val label: String, val distance: Int = 0)

object BrandMatcher {
    /**
     * Shorter brand skeletons only match exactly: one-letter changes to five-letter names (chase → chaser)
     * hit too many legitimate sites.
     */
    const val MIN_TYPO_LENGTH = 6
    const val MIN_COMBO_TOKEN = 4

    /**
     * Compares the registrable label with the brand list: exact skeleton (homograph or brand on an
     * unofficial domain), then bounded OSA distance on skeletons (typosquat), then hyphen-separated
     * tokens (combosquat; Kintis et al., CCS 2017).
     */
    fun match(label: String, registrable: String, publicSuffix: String, catalog: BrandCatalog, confusables: ConfusableMap): BrandHit? {
        val sk = skeleton(label, confusables)
        catalog.bySkeleton(sk).firstOrNull()?.let { brand ->
            if (brand.isOfficial(registrable, publicSuffix)) return null
            return BrandHit(brand, if (label == brand.label) BrandHitKind.UNOFFICIAL_DOMAIN else BrandHitKind.HOMOGRAPH, label)
        }
        val max = when {
            sk.length < MIN_TYPO_LENGTH -> 0
            sk.length < 9 -> 1
            else -> 2
        }
        if (max > 0) {
            for (brand in catalog.bySkeletonLength(sk.length - max..sk.length + max)) {
                if (brand.skeleton.length < MIN_TYPO_LENGTH) continue
                if (label in brand.notConfusableWith || sk in brand.notConfusableWith) continue
                if (brand.isOfficial(registrable, publicSuffix)) continue
                val d = osaDistance(sk, brand.skeleton, max)
                if (d <= max) return BrandHit(brand, BrandHitKind.TYPOSQUAT, label, d)
            }
        }
        for (token in sk.split('-')) {
            if (token.length < MIN_COMBO_TOKEN || token == sk) continue
            val brand = catalog.bySkeleton(token).firstOrNull() ?: continue
            if (!brand.isOfficial(registrable, publicSuffix)) return BrandHit(brand, BrandHitKind.COMBOSQUAT, label)
        }
        return null
    }

    /** Brand names placed in subdomains of someone else's domain (`paypal.com.verify-id.top`). */
    fun inSubdomains(subdomainLabels: List<String>, catalog: BrandCatalog, confusables: ConfusableMap): Brand? {
        for (label in subdomainLabels) {
            for (token in skeleton(label, confusables).split('-')) {
                if (token.length < MIN_COMBO_TOKEN) continue
                catalog.bySkeleton(token).firstOrNull()?.let { return it }
            }
        }
        return null
    }
}
