package lian.core.engine.app

import lian.core.engine.link.BrandCatalog
import lian.core.engine.link.ConfusableMap

/** A store or installer from the curated catalogue (stores.tsv). */
data class Store(val kind: Kind, val packageName: String, val name: String, val reviewed: Boolean, val mustBeSystem: Boolean) {
    enum class Kind { STORE, INSTALLER, SHELL }
}

class StoreCatalog(stores: List<Store>) {
    private val byPackage = stores.associateBy { it.packageName }
    operator fun get(packageName: String): Store? = byPackage[packageName]
    val all: Collection<Store> get() = byPackage.values
}

/** One monitoring product from the stalkerware indicator data (Echap, CC BY 4.0). */
data class Indicator(val type: Type, val name: String, val packages: Set<String>, val certSha1: Set<String>, val websites: Set<String>) {
    enum class Type { STALKERWARE, WATCHWARE }
}

class IndicatorSet(val all: List<Indicator>) {
    private val byPackage = HashMap<String, Indicator>().apply { all.forEach { i -> i.packages.forEach { putIfAbsent(it, i) } } }
    private val byCert = HashMap<String, Indicator>().apply { all.forEach { i -> i.certSha1.forEach { putIfAbsent(it, i) } } }

    fun byPackage(packageName: String): Indicator? = byPackage[packageName]
    fun byCertSha1(sha1Hex: String): Indicator? = byCert[sha1Hex.lowercase()]
    val packageCount: Int get() = byPackage.size
    val certificateCount: Int get() = byCert.size
}

enum class OsSupport { SUPPORTED, ENDING, ENDED }

/** Bundled data used by the app audit, APK check and device check-up. Implemented by :core:intel. */
interface AppIntel {
    val version: String
    val stores: StoreCatalog
    val indicators: IndicatorSet
    val brands: BrandCatalog
    val confusables: ConfusableMap

    /** Security-fix status of an Android API level, with a human description ("Android 13"). */
    fun osSupport(sdkInt: Int): Pair<OsSupport, String>
}
