package lian.core.platform

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import lian.core.engine.background.BackgroundUsage

/**
 * Which apps worked while the user wasn't using them, read from Android's own usage records.
 * Needs "usage access", which only the user can grant in Settings; without it, [usage] is empty and
 * only the static signals (starts at boot, battery-unrestricted) are available.
 *
 * Read on the phone, processed on the phone; LIAN has no network access to send it anywhere.
 */
class BackgroundProbe(private val context: Context) {

    data class Snapshot(
        val usageAccess: Boolean,
        val windowMs: Long,
        val usage: Map<String, BackgroundUsage>,
        val startsAtBoot: Set<String>,
        val batteryUnrestricted: (String) -> Boolean,
    )

    @Suppress("DEPRECATION")   // unsafeCheckOpNoThrow: the replacement needs API 37; this covers minSdk 30
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: SecurityException) {
            false
        }
    }

    fun collect(nowMs: Long, windowMs: Long = DAY_MS): Snapshot {
        val access = hasUsageAccess()
        val power = context.getSystemService(PowerManager::class.java)
        return Snapshot(
            usageAccess = access,
            windowMs = windowMs,
            usage = if (access) usage(nowMs - windowMs, nowMs) else emptyMap(),
            startsAtBoot = bootReceivers(),
            batteryUnrestricted = { pkg -> power?.isIgnoringBatteryOptimizations(pkg) == true },
        )
    }

    /**
     * Foreground-service time and on-screen time per app, plus background minutes per hour from the
     * FOREGROUND_SERVICE_START/STOP event stream (API 29+).
     */
    private fun usage(begin: Long, end: Long): Map<String, BackgroundUsage> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val totals = try { usm.queryAndAggregateUsageStats(begin, end) } catch (_: SecurityException) { return emptyMap() }

        val hours = HashMap<String, IntArray>()
        val started = HashMap<String, Long>()
        fun addSpan(pkg: String, from: Long, to: Long) {
            val bins = hours.getOrPut(pkg) { IntArray(HOURS) }
            var t = maxOf(from, begin)
            while (t < to) {
                val bin = ((t - begin) / HOUR_MS).toInt().coerceIn(0, HOURS - 1)
                val binEnd = begin + (bin + 1) * HOUR_MS
                val slice = minOf(to, binEnd) - t
                bins[bin] = (bins[bin] + (slice / 60_000L).toInt()).coerceAtMost(60)
                t += slice.coerceAtLeast(1)
            }
        }
        try {
            val events = usm.queryEvents(begin, end)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                when (e.eventType) {
                    UsageEvents.Event.FOREGROUND_SERVICE_START -> started.putIfAbsent(pkg, e.timeStamp)
                    UsageEvents.Event.FOREGROUND_SERVICE_STOP -> started.remove(pkg)?.let { addSpan(pkg, it, e.timeStamp) }
                }
            }
            started.forEach { (pkg, from) -> addSpan(pkg, from, end) }   // still running now
        } catch (_: SecurityException) {
            // Access was revoked mid-read; the aggregate totals are still meaningful.
        }

        return totals.mapNotNull { (pkg, stats) ->
            if (pkg == context.packageName) return@mapNotNull null
            val fgs = stats.totalTimeForegroundServiceUsed
            if (fgs <= 0) return@mapNotNull null
            pkg to BackgroundUsage(fgs, stats.totalTimeVisible, (hours[pkg] ?: IntArray(HOURS)).toList())
        }.toMap()
    }

    /** Apps that start themselves when the phone boots. */
    private fun bootReceivers(): Set<String> {
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        val list = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryBroadcastReceivers(intent, 0)
        }
        return list.mapNotNull { it.activityInfo?.packageName }.filter { it != context.packageName }.toSet()
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        private const val HOUR_MS = 60 * 60_000L
        private const val HOURS = 24
    }
}
