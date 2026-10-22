package lian.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import lian.app.shield.ShieldStore
import lian.app.shortcuts.Shortcuts
import lian.app.watch.WatchRunner
import lian.core.engine.EvalContext
import lian.core.engine.apk.ApkAnalyzer
import lian.core.engine.app.AppAnalyzer
import lian.core.engine.app.AppFacts
import lian.core.engine.app.AppReport
import lian.core.engine.background.BackgroundAnalyzer
import lian.core.engine.background.BackgroundFacts
import lian.core.engine.background.BackgroundReport
import lian.core.engine.device.DeviceChecks
import lian.core.engine.device.DeviceFacts
import lian.core.engine.device.PostureReport
import lian.core.engine.link.LinkAnalyzer
import lian.core.engine.message.MessageAnalyzer
import lian.core.engine.message.TextAnalyzer
import lian.core.intel.IntelPack
import lian.core.platform.AppInventory
import lian.core.platform.BackgroundProbe
import lian.core.platform.DeviceProbe
import lian.core.platform.PrivacyProbe
import lian.core.platform.RegionHint
import lian.core.platform.untrusted.UntrustedClient

class LianApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // This also runs in the isolated file-checker process, which has no system services (no
        // shortcuts, no files of ours): main-process start-up work only belongs in the main process.
        if (Process.isIsolated()) return
        // Copies of shared files left behind by a crash are removed before anything else runs.
        UntrustedClient.sweepInbox(this)
        try {
            Shortcuts.publish(this)
        } catch (_: IllegalStateException) {
            // Shortcuts can't be updated while the user is locked (direct boot); next start will do it.
        }
    }
}

/** Manual dependency graph: a handful of objects doesn't justify a DI framework and its supply chain. */
class AppGraph(val app: Application) {
    val intel: IntelPack by lazy { IntelPack.load() }
    val linkAnalyzer: LinkAnalyzer by lazy { LinkAnalyzer(intel) }
    val messageAnalyzer: MessageAnalyzer by lazy { MessageAnalyzer(intel) }
    val textAnalyzer: TextAnalyzer by lazy { TextAnalyzer(linkAnalyzer, messageAnalyzer) }
    val appAnalyzer: AppAnalyzer by lazy { AppAnalyzer(intel) }
    val apkAnalyzer: ApkAnalyzer by lazy { ApkAnalyzer(intel) }
    val backgroundAnalyzer: BackgroundAnalyzer by lazy { BackgroundAnalyzer() }
    val deviceProbe: DeviceProbe by lazy { DeviceProbe(app, intel) }
    val appInventory: AppInventory by lazy { AppInventory(app) }
    val backgroundProbe: BackgroundProbe by lazy { BackgroundProbe(app) }
    val untrusted: UntrustedClient by lazy { UntrustedClient(app) }
    val prefs: Prefs by lazy { Prefs(app) }
    val watch: WatchRunner by lazy { WatchRunner(this) }
    val shield: ShieldStore by lazy { ShieldStore() }
    val privacyProbe: PrivacyProbe by lazy {
        PrivacyProbe(app, BuildConfig.EXPECTED_SIGNERS.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())
    }

    /** Content or a shortcut from outside, waiting for its screen. Held in memory only, never stored. */
    val inbound = MutableStateFlow<Inbound?>(null)

    // Latest results, kept in memory so the tabs share them. Never written to disk.
    val lastAudit = MutableStateFlow<List<AppReport>?>(null)
    val lastBackground = MutableStateFlow<List<BackgroundReport>?>(null)
    val lastPosture = MutableStateFlow<PostureReport?>(null)
    val lastDevice = MutableStateFlow<DeviceFacts?>(null)

    /** Device check-up, shared by Home and Phone health. Blocking: call off the main thread. */
    fun postureNow(): PostureReport {
        val facts = deviceProbe.collect()
        return DeviceChecks.evaluate(facts, context(), intel::osSupport).also {
            lastDevice.value = facts
            lastPosture.value = it
        }
    }

    /** App audit, shared by the Home and Apps tabs. Blocking: call off the main thread. */
    fun auditNow(): List<AppReport> = appAnalyzer.analyzeAll(appInventory.collect(), context()).also { lastAudit.value = it }

    /** Who worked unseen, from Android's usage records (if granted). Blocking. Returns (usage access, reports). */
    fun backgroundNow(apps: List<AppFacts>): Pair<Boolean, List<BackgroundReport>> {
        val snap = backgroundProbe.collect(System.currentTimeMillis())
        val facts = apps.map { a -> BackgroundFacts(a, snap.usage[a.packageName], a.packageName in snap.startsAtBoot, snap.batteryUnrestricted(a.packageName)) }
        val reports = backgroundAnalyzer.analyzeAll(facts, context())
        lastBackground.value = reports
        return snap.usageAccess to reports
    }

    fun regionHint(): String? = RegionHint.of(app)

    fun context(): EvalContext = EvalContext(nowEpochMs = System.currentTimeMillis(), sdkInt = Build.VERSION.SDK_INT, regionHint = regionHint())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var warmed = false

    /**
     * Loads the intel data and runs one throwaway analysis in the background as soon as the UI starts,
     * so class loading, model decoding and Unicode table initialisation overlap with the intro instead
     * of the user's first check. Idempotent; runs at most once per process.
     */
    @Synchronized
    fun warmUp() {
        if (warmed) return
        warmed = true
        scope.launch { textAnalyzer.analyze("Your parcel is waiting, confirm at https://www.example.com/login", EvalContext(nowEpochMs = 0)) }
    }
}

val Context.graph: AppGraph get() = (applicationContext as LianApp).graph
