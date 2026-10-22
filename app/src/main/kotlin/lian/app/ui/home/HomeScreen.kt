package lian.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lian.app.AppGraph
import lian.app.R
import lian.app.shield.ShieldListenerService
import lian.app.ui.Tab
import lian.app.ui.apps.AppIcon
import lian.app.ui.apps.needsAttention
import lian.app.ui.brand.LianMark
import lian.app.ui.design.GlassCard
import lian.app.ui.design.GradientButton
import lian.app.ui.design.IconTile
import lian.app.ui.design.Pill
import lian.app.ui.design.PulseDot
import lian.app.ui.design.ScoreRing
import lian.app.ui.design.appear
import lian.app.ui.shield.rememberShieldEnabler
import lian.app.ui.theme.Accent
import lian.app.ui.theme.LocalLianColors
import lian.core.engine.device.CheckStatus
import lian.core.engine.device.PostureBand
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Keeps Home's three summaries fresh; the results live in [AppGraph] so the other tabs share them. */
class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (graph.lastPosture.value == null) graph.postureNow()
                // The app summary too, once the user has agreed to the app audit.
                if (graph.prefs.auditDisclosureAccepted && graph.lastAudit.value == null) graph.backgroundNow(graph.auditNow().map { it.facts })
            }
        }
    }
}

/**
 * Home is an overview, not a dashboard: one line saying whether anything needs you, then one card
 * per feature that opens it. Details live in the feature's own tab or screen.
 */
@Composable
fun HomeRoute(graph: AppGraph, openTab: (Tab) -> Unit, openHealth: () -> Unit, openPrivacy: () -> Unit) {
    viewModel { HomeViewModel(graph) }
    val posture by graph.lastPosture.collectAsStateWithLifecycle()
    val audit by graph.lastAudit.collectAsStateWithLifecycle()
    val background by graph.lastBackground.collectAsStateWithLifecycle()
    val counts by graph.prefs.shieldCounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var shieldOn by remember { mutableStateOf(ShieldListenerService.enabled(context)) }
    val enableShield = rememberShieldEnabler()
    LifecycleResumeEffect(Unit) {
        shieldOn = ShieldListenerService.enabled(context)
        graph.prefs.refreshShieldCounts(System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1))
        onPauseOrDispose { }
    }

    val risky = audit?.filter { it.needsAttention() }
    val unseen = background?.count { it.facts.ranUnseen }
    val toFix = posture?.checks?.count { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN }
    val attention = listOf(!shieldOn, (risky?.size ?: 0) > 0, (unseen ?: 0) > 0, (toFix ?: 0) > 0).count { it }
    val c = LocalLianColors.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = top + 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.appear(0)) {
            LianMark(Modifier.size(48.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(greeting()), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // One line that answers "am I OK?"
        if (posture != null) {
            Box(Modifier.appear(1)) {
                if (attention == 0) Pill(stringResource(R.string.home_all_good), c.safe, R.drawable.ic_check_circle)
                else Pill(stringResource(R.string.home_attention, attention), c.caution, R.drawable.ic_warning)
            }
        }

        FeatureCard(2, R.drawable.ic_scam_shield, Accent.VIOLET, R.string.shield_title, R.string.home_shield_body, onClick = { openTab(Tab.SHIELD) }) {
            if (shieldOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(c.safe, active = true, size = 7.dp)
                    Text(stringResource(R.string.home_shield_on, counts.first, counts.second), style = MaterialTheme.typography.labelLarge, color = if (counts.second > 0) c.danger else c.safe)
                }
            } else {
                GradientButton(stringResource(R.string.shield_enable), enableShield, icon = R.drawable.ic_scam_shield)
            }
        }

        FeatureCard(3, R.drawable.ic_apps, Accent.CORAL, R.string.home_apps_title, R.string.home_apps_body, onClick = { openTab(Tab.APPS) }) {
            if (risky == null) {
                Text(stringResource(R.string.home_apps_unchecked), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.home_apps_status, risky.size, unseen ?: 0),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (risky.isEmpty() && (unseen ?: 0) == 0) c.safe else c.caution,
                        modifier = Modifier.weight(1f),
                    )
                    // The faces behind the number: up to four of the apps worth a look.
                    Box {
                        risky.take(4).forEachIndexed { i, r ->
                            Box(Modifier.offset(x = (i * 20).dp).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).clip(CircleShape)) {
                                AppIcon(r.facts.packageName, r.facts.label, 30.dp)
                            }
                        }
                        Spacer(Modifier.width((30 + 20 * (risky.take(4).size - 1).coerceAtLeast(0)).dp))
                    }
                }
            }
        }

        FeatureCard(4, R.drawable.ic_lock, Accent.MINT, R.string.health_title, R.string.home_health_body, onClick = openHealth, trailing = { ScoreRing(posture?.score, size = 58.dp) }) {
            val p = posture
            Text(
                if (p == null) stringResource(R.string.loading) else stringResource(R.string.home_health_status, stringResource(bandLabel(p.band)), toFix ?: 0),
                style = MaterialTheme.typography.labelLarge,
                color = when (p?.band) {
                    PostureBand.STRONG, PostureBand.GOOD -> c.safe
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> c.caution
                },
            )
        }

        // The promise, as a quiet link to the evidence.
        Row(
            Modifier.appear(5).fillMaxWidth().clip(CircleShape).clickable(role = Role.Button, onClick = openPrivacy).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_wifi_off), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.usp_badge) + "  ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One feature, front and centre: coloured tile, name, what it does for you, its live status; tap to open. */
@Composable
private fun FeatureCard(
    index: Int,
    icon: Int,
    accent: Accent,
    title: Int,
    body: Int,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    status: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth().appear(index), onClick = onClick, padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, accent, 50.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (trailing != null) trailing() else Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column(content = status)
        }
    }
}

private fun greeting(): Int = when (LocalTime.now().hour) {
    in 5..11 -> R.string.greeting_morning
    in 12..17 -> R.string.greeting_afternoon
    else -> R.string.greeting_evening
}

fun bandLabel(band: PostureBand) = when (band) {
    PostureBand.STRONG -> R.string.posture_strong
    PostureBand.GOOD -> R.string.posture_good
    PostureBand.NEEDS_WORK -> R.string.posture_needs_work
    PostureBand.AT_RISK -> R.string.posture_at_risk
}
