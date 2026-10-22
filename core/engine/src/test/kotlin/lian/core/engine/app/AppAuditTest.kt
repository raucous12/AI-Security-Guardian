package lian.core.engine.app

import org.junit.jupiter.api.Test
import lian.core.engine.EvalContext
import lian.core.engine.Severity
import lian.core.engine.TestIntel
import lian.core.engine.link.Brand
import lian.core.engine.link.BrandCatalog
import lian.core.engine.link.ConfusableMap
import lian.core.engine.link.IndexedBrandCatalog
import lian.core.engine.link.skeleton
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object AppTestIntel : AppIntel {
    override val version = "test-fixture"
    override val confusables: ConfusableMap = TestIntel.confusables
    override val stores = StoreCatalog(
        listOf(
            Store(Store.Kind.STORE, "com.android.vending", "Google Play", reviewed = true, mustBeSystem = true),
            Store(Store.Kind.STORE, "org.fdroid.fdroid", "F-Droid", reviewed = false, mustBeSystem = false),
            Store(Store.Kind.INSTALLER, "com.google.android.packageinstaller", "Package installer", reviewed = false, mustBeSystem = true),
            Store(Store.Kind.SHELL, "com.android.shell", "adb", reviewed = false, mustBeSystem = true),
        ),
    )
    override val indicators = IndicatorSet(
        listOf(
            Indicator(Indicator.Type.STALKERWARE, "SpyThing", setOf("com.spy.thing"), setOf("aa".repeat(20)), setOf("spything.example")),
            Indicator(Indicator.Type.WATCHWARE, "KidWatch", setOf("com.kidwatch"), emptySet(), emptySet()),
        ),
    )
    override val brands: BrandCatalog = IndexedBrandCatalog(
        listOf(Brand("paypal", skeleton("paypal", confusables), setOf("paypal.com"), false, setOf("com.paypal.android.p2pmobile"), emptySet(), "payments")),
    )
    override fun osSupport(sdkInt: Int) = when {
        sdkInt <= 33 -> OsSupport.ENDED to "old"
        sdkInt == 34 -> OsSupport.ENDING to "Android 14"
        else -> OsSupport.SUPPORTED to "new"
    }
}

class AppAuditTest {
    private val analyzer = AppAnalyzer(AppTestIntel)
    private val ctx = EvalContext(nowEpochMs = 0)

    private fun app(
        pkg: String = "com.example.app",
        label: String = "Example",
        initiator: String? = "com.android.vending",
        initiatorSystem: Boolean = true,
        system: Boolean = false,
        active: Set<Capability> = emptySet(),
        launcher: Boolean = true,
        targetSdk: Int = 35,
        sha1: List<String> = listOf("bb".repeat(20)),
        tool: Boolean? = null,
    ) = AppFacts(
        packageName = pkg, label = label, versionName = "1", targetSdk = targetSdk, signerSha256 = listOf("cc".repeat(32)),
        signerSha1 = sha1, debugCertificate = false, debuggable = false, testOnly = false,
        install = InstallFacts(system, initiator, initiatorSystem), hasLauncherIcon = launcher,
        requestedPermissions = emptySet(), active = active, declared = emptySet(), accessibilityTool = tool,
    )

    private fun ids(r: AppReport) = r.findings.map { it.rule.value }

    @Test
    fun `provenance comes from the initiating installer`() {
        val s = AppTestIntel.stores
        assertEquals(Provenance.STORE_REVIEWED, ProvenanceClassifier.classify(InstallFacts(false, "com.android.vending", true), s).first)
        assertEquals(Provenance.SPOOFED_STORE, ProvenanceClassifier.classify(InstallFacts(false, "com.android.vending", false), s).first)
        assertEquals(Provenance.STORE_INDEPENDENT, ProvenanceClassifier.classify(InstallFacts(false, "org.fdroid.fdroid", false), s).first)
        assertEquals(Provenance.FILE_INSTALL, ProvenanceClassifier.classify(InstallFacts(false, "com.google.android.packageinstaller", true), s).first)
        assertEquals(Provenance.ADB_OR_UNKNOWN, ProvenanceClassifier.classify(InstallFacts(false, "com.android.shell", true), s).first)
        assertEquals(Provenance.ADB_OR_UNKNOWN, ProvenanceClassifier.classify(InstallFacts(false, null, false), s).first)
        assertEquals(Provenance.INSTALLED_BY_APP, ProvenanceClassifier.classify(InstallFacts(false, "com.some.browser", false), s).first)
        assertEquals(Provenance.SYSTEM, ProvenanceClassifier.classify(InstallFacts(true, null, false), s).first)
    }

    @Test
    fun `known stalkerware is critical by package or by certificate`() {
        val byPackage = analyzer.analyze(app(pkg = "com.spy.thing"), ctx)
        assertTrue("app.ioc.stalkerware" in ids(byPackage))
        assertEquals(AppBand.CRITICAL, byPackage.band)
        val byCert = analyzer.analyze(app(pkg = "com.renamed", sha1 = listOf("AA".repeat(20))), ctx)
        assertTrue("app.ioc.stalkerware" in ids(byCert))
    }

    @Test
    fun `the same power weighs less from a reviewed store`() {
        val store = analyzer.analyze(app(active = setOf(Capability.ACCESSIBILITY)), ctx)
        val file = analyzer.analyze(app(active = setOf(Capability.ACCESSIBILITY), initiator = "com.google.android.packageinstaller"), ctx)
        assertTrue(file.score > store.score, "${file.score} vs ${store.score}")
    }

    @Test
    fun `hidden app with accessibility and sms looks like a banking trojan`() {
        val r = analyzer.analyze(app(active = setOf(Capability.ACCESSIBILITY, Capability.SMS), launcher = false, initiator = "com.google.android.packageinstaller"), ctx)
        assertTrue(listOf("app.combo.trojan", "app.stealth.surveil", "app.a11y.non_tool", "app.sms").all { it in ids(r) }, ids(r).toString())
        assertTrue(r.band >= AppBand.HIGH)
    }

    @Test
    fun `system apps don't score for capabilities`() {
        val r = analyzer.analyze(app(system = true, initiator = null, active = setOf(Capability.ACCESSIBILITY, Capability.DEVICE_ADMIN), launcher = false), ctx)
        assertEquals(0, r.score)
    }

    @Test
    fun `an app named after a brand but from another publisher is an impostor`() {
        val fake = analyzer.analyze(app(pkg = "com.secure.wallet", label = "PayPal", initiator = "com.google.android.packageinstaller"), ctx)
        assertTrue("app.impostor" in ids(fake))
        assertTrue(fake.findings.first { it.rule.value == "app.impostor" }.severity == Severity.CRITICAL)
        val real = analyzer.analyze(app(pkg = "com.paypal.android.p2pmobile", label = "PayPal", initiator = "com.google.android.packageinstaller"), ctx)
        assertTrue("app.impostor" !in ids(real))
        val sameOrg = analyzer.analyze(app(pkg = "com.paypal.business", label = "PayPal", initiator = "com.google.android.packageinstaller"), ctx)
        assertTrue("app.impostor" !in ids(sameOrg))
    }

    @Test
    fun `a declared accessibility tool weighs less than a non-tool`() {
        val tool = analyzer.analyze(app(active = setOf(Capability.ACCESSIBILITY), tool = true, initiator = "org.fdroid.fdroid", initiatorSystem = false), ctx)
        val nonTool = analyzer.analyze(app(active = setOf(Capability.ACCESSIBILITY), tool = false, initiator = "org.fdroid.fdroid", initiatorSystem = false), ctx)
        assertTrue("app.a11y.tool" in ids(tool) && "app.a11y.non_tool" in ids(nonTool))
        assertTrue(tool.score < nonTool.score)
    }

    @Test
    fun `audit order is riskiest first and stable`() {
        val reports = analyzer.analyzeAll(listOf(app(pkg = "b", label = "B"), app(pkg = "com.spy.thing", label = "Z"), app(pkg = "a", label = "A")), ctx)
        assertEquals(listOf("com.spy.thing", "a", "b"), reports.map { it.facts.packageName })
    }
}
