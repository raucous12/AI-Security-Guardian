package lian.app.ui.intro

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import lian.app.AppGraph
import lian.app.R
import lian.app.shield.ShieldListenerService
import lian.app.ui.SystemSettings
import lian.app.ui.brand.LianLogoAnimated
import lian.app.ui.design.GlassCard
import lian.app.ui.design.GradientButton
import lian.app.ui.design.IconTile
import lian.app.ui.design.LianBackground
import lian.app.ui.design.Pill
import lian.app.ui.theme.Accent
import lian.app.ui.theme.LocalLianColors
import lian.app.watch.Notifications
import lian.app.watch.WatchScheduler

/** First run only: what LIAN promises, why it can keep that promise, and one-tap automatic protection. */
@Composable
fun OnboardingScreen(graph: AppGraph, onDone: () -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    LianBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.onb_skip)) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    when (page) {
                        0 -> {
                            LianLogoAnimated(Modifier.size(200.dp), startAtEnd = true)
                            Text(stringResource(R.string.onb1_title), style = MaterialTheme.typography.headlineLarge)
                            Text(stringResource(R.string.usp_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                            Text(stringResource(R.string.usp_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        1 -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                IconTile(R.drawable.ic_wifi_off, Accent.CORAL, 64.dp)
                                IconTile(R.drawable.ic_lock, Accent.VIOLET, 64.dp)
                                IconTile(R.drawable.ic_eye, Accent.MINT, 64.dp)
                            }
                            Text(stringResource(R.string.onb2_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                            Text(stringResource(R.string.onb2_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Pill(stringResource(R.string.usp_badge), LocalLianColors.current.safe, R.drawable.ic_check_circle)
                        }
                        else -> ProtectionSetup(graph)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    repeat(3) { i ->
                        val on = pager.currentPage == i
                        val w by animateDpAsState(if (on) 22.dp else 8.dp, label = "dot")
                        val c by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, label = "dotc")
                        Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(c))
                    }
                }
                val last = pager.currentPage == 2
                GradientButton(stringResource(if (last) R.string.onb_start else R.string.onb_next), onClick = {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                })
            }
        }
    }
}

@Composable
private fun ProtectionSetup(graph: AppGraph) {
    val context = LocalContext.current
    var shieldOn by remember { mutableStateOf(ShieldListenerService.enabled(context)) }
    var usageOn by remember { mutableStateOf(graph.backgroundProbe.hasUsageAccess()) }
    var watchOn by remember { mutableStateOf(graph.prefs.watchEnabled.value) }
    LifecycleResumeEffect(Unit) {
        shieldOn = ShieldListenerService.enabled(context)
        usageOn = graph.backgroundProbe.hasUsageAccess()
        onPauseOrDispose { }
    }
    // Alerts need notifications; ask once, then open the system toggle for notification access.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { SystemSettings.notificationAccess(context) }
    val watchPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enableWatch(graph, context) ; watchOn = true }

    Text(stringResource(R.string.onb3_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text(stringResource(R.string.onb3_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SetupRow(R.drawable.ic_scam_shield, Accent.VIOLET, R.string.onb_shield_title, R.string.onb_shield_body, shieldOn) {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.permitted(context)) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else SystemSettings.notificationAccess(context)
    }
    SetupRow(R.drawable.ic_bolt, Accent.AMBER, R.string.onb_usage_title, R.string.onb_usage_body, usageOn) { SystemSettings.usageAccess(context) }
    SetupRow(R.drawable.ic_clock, Accent.MINT, R.string.onb_watch_title, R.string.onb_watch_body, watchOn) {
        if (watchOn) { graph.prefs.setWatchEnabled(false); WatchScheduler.cancel(context); watchOn = false }
        else if (Build.VERSION.SDK_INT >= 33 && !Notifications.permitted(context)) watchPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else { enableWatch(graph, context); watchOn = true }
    }
    Spacer(Modifier.height(4.dp))
}

private fun enableWatch(graph: AppGraph, context: android.content.Context) {
    graph.prefs.setWatchEnabled(true)
    Notifications.createChannel(context)
    WatchScheduler.schedule(context)
}

@Composable
private fun SetupRow(icon: Int, accent: Accent, title: Int, body: Int, on: Boolean, onToggle: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, accent, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = on, onCheckedChange = { onToggle() })
        }
    }
}
