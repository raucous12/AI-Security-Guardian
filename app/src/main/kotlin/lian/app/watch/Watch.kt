package lian.app.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import lian.app.AppGraph
import lian.app.MainActivity
import lian.app.R
import lian.app.graph
import lian.core.engine.app.AppRules
import lian.core.engine.device.DeviceChecks
import lian.core.engine.watch.Snapshot
import lian.core.engine.watch.WatchDiff
import lian.core.engine.watch.WatchEvent
import lian.core.storage.AtomicWrite
import lian.core.storage.Fingerprinter
import lian.core.storage.KeystoreKeys
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Watch (Phase 3.7): re-check apps and settings, compare with the previous snapshot, notify on
 * important changes. Snapshots hold keyed one-way fingerprints, never package names, and each file
 * carries an HMAC so a modified file is treated as "no baseline" rather than trusted.
 */
class WatchRunner(private val graph: AppGraph) {
    data class Outcome(val events: List<WatchEvent>, val ranAtMs: Long, val firstRun: Boolean)

    private val dir: File get() = File(graph.app.noBackupFilesDir, "watch")
    private val lock = Any()

    fun run(): Outcome = synchronized(lock) {
        val ctx = graph.context()
        val reports = graph.appAnalyzer.analyzeAll(graph.appInventory.collect(), ctx)
        graph.lastAudit.value = reports
        val device = graph.deviceProbe.collect()
        val posture = DeviceChecks.evaluate(device, ctx, graph.intel::osSupport)
        val fp = fingerprinter()
        val now = Snapshot.of(reports, posture, device.userCaCount ?: 0, AppRules.VERSION + "+" + DeviceChecks.VERSION) { fp.of(it) }
        val previous = readVerified("snapshot.txt", fp)?.let(Snapshot::decode)
        val events = WatchDiff.diff(previous, now)
        val today = ctx.nowEpochMs / TimeUnit.DAYS.toMillis(1)
        val notify = WatchDiff.shouldNotify(events, previous?.lastNotifiedDay ?: -1, today)
        if (notify) Notifications.post(graph.app)
        writeSigned("snapshot.txt", now.copy(lastNotifiedDay = if (notify) today else previous?.lastNotifiedDay ?: -1).encode(), fp)
        writeSigned("events.txt", events.joinToString("\n") { "${it.type}\t${it.severity}\t${it.appFp.orEmpty()}\t${it.detail}" }, fp)
        graph.prefs.lastWatchRun = ctx.nowEpochMs
        Outcome(events, ctx.nowEpochMs, previous == null)
    }

    /** Events from the last run, for the Watch screen. */
    fun lastEvents(): List<WatchEvent> {
        val text = readVerified("events.txt", fingerprinter()) ?: return emptyList()
        return text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('\t', limit = 4)
            if (f.size < 4) return@mapNotNull null
            val type = WatchEvent.Type.entries.firstOrNull { it.name == f[0] } ?: return@mapNotNull null
            val severity = lian.core.engine.Severity.entries.firstOrNull { it.name == f[1] } ?: return@mapNotNull null
            WatchEvent(type, severity, f[2].ifEmpty { null }, f[3])
        }
    }

    /** Resolves an event's fingerprint back to the installed app's label, from the current audit. */
    fun labelFor(appFp: String): String? {
        val fp = fingerprinter()
        return graph.lastAudit.value?.firstOrNull { fp.of("pkg:" + it.facts.packageName) == appFp }?.facts?.label
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        cachedFingerprinter = null
    }

    @Volatile private var cachedFingerprinter: Fingerprinter? = null

    private fun fingerprinter(): Fingerprinter =
        cachedFingerprinter ?: Fingerprinter.fromKeystore(KeystoreKeys.watchMacKey()).also { cachedFingerprinter = it }

    private fun writeSigned(name: String, body: String, fp: Fingerprinter) {
        AtomicWrite.write(File(dir, name), (body + "\n" + MAC + fp.of("file:$name", body)).toByteArray(Charsets.UTF_8))
    }

    private fun readVerified(name: String, fp: Fingerprinter): String? {
        val file = File(dir, name)
        if (!file.exists()) return null
        val text = file.readText(Charsets.UTF_8)
        val cut = text.lastIndexOf("\n" + MAC)
        if (cut < 0) return null
        val body = text.substring(0, cut)
        return body.takeIf { text.substring(cut + 1 + MAC.length) == fp.of("file:$name", it) }
    }

    private companion object {
        const val MAC = "mac\t"
    }
}

object Notifications {
    private const val CHANNEL = "watch"
    private const val ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.notif_channel_body) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun permitted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Always generic text: stalkerware with notification access must not learn what LIAN found (Phase 2, channel 10). */
    fun post(context: Context) {
        if (!permitted(context)) return
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val generic = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_text))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(generic)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}

object WatchScheduler {
    private const val JOB_ID = 4_201

    /** Daily, only when the battery isn't low; survives reboots (RECEIVE_BOOT_COMPLETED). */
    fun schedule(context: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, WatchJobService::class.java))
            .setPeriodic(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(6))
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()
        context.getSystemService(JobScheduler::class.java)?.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }
}

/** The platform JobScheduler runs Watch; no WorkManager, so no extra library, database or exported components. */
class WatchJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val graph = applicationContext.graph
        if (!graph.prefs.watchEnabled.value) return false
        thread(name = "lian-watch") {
            val ok = try { graph.watch.run(); true } catch (_: Exception) { false }
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
