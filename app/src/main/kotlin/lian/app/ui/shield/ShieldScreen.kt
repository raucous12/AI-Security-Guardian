package lian.app.ui.shield

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.text.format.DateUtils
import android.view.textclassifier.TextClassifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lian.app.AppGraph
import lian.app.Inbound
import lian.app.R
import lian.app.shield.ShieldListenerService
import lian.app.shield.ShieldStore
import lian.app.ui.Loading
import lian.app.ui.SystemSettings
import lian.app.ui.design.GlassCard
import lian.app.ui.design.GradientButton
import lian.app.ui.design.IconTile
import lian.app.ui.design.Pill
import lian.app.ui.design.PulseDot
import lian.app.ui.design.ScreenHeader
import lian.app.ui.design.SectionHeader
import lian.app.ui.design.appear
import lian.app.ui.theme.Accent
import lian.app.ui.theme.LocalLianColors
import lian.app.watch.Notifications
import lian.core.engine.link.LinkBand
import lian.core.engine.message.TextCheckResult
import java.util.concurrent.TimeUnit

/** Messages and links are never stored (Phase 2.4): the manual input lives only in this ViewModel. */
class ShieldViewModel(private val graph: AppGraph) : ViewModel() {
    data class State(val input: String = "", val checking: Boolean = false, val result: TextCheckResult? = null, val expanded: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun onInput(text: String) = _state.update { it.copy(input = text.take(Inbound.MAX_TEXT), result = null) }

    fun checkText(text: String) {
        onInput(text)
        check()
    }

    fun check() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.checking) return
        _state.update { it.copy(checking = true, result = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { graph.textAnalyzer.analyze(text, graph.context()) }
            _state.update { it.copy(checking = false, result = result) }
        }
    }

    fun clear() = _state.update { it.copy(input = "", result = null) }
    fun toggle(id: Long) = _state.update { it.copy(expanded = if (it.expanded == id) 0 else id) }
    fun expand(id: Long) = _state.update { it.copy(expanded = id) }
}

private enum class ClipHint { LINK, TEXT }

/**
 * Turning Shield on: first the notification permission (so scam alerts can actually pop up), then
 * Android's notification-access screen, where only the user can switch LIAN on.
 */
@Composable
fun rememberShieldEnabler(): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { SystemSettings.notificationAccess(context) }
    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= 33 && !Notifications.permitted(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else SystemSettings.notificationAccess(context)
        }
    }
}

@Composable
fun ShieldRoute(graph: AppGraph) {
    val vm: ShieldViewModel = viewModel { ShieldViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val alerts by graph.shield.alerts.collectAsStateWithLifecycle()
    val counts by graph.prefs.shieldCounts.collectAsStateWithLifecycle()
    val pending by graph.inbound.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var shieldOn by remember { mutableStateOf(ShieldListenerService.enabled(context)) }
    var alertsAllowed by remember { mutableStateOf(Notifications.permitted(context)) }
    val enableShield = rememberShieldEnabler()
    val allowAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alertsAllowed = granted
        if (!granted) SystemSettings.appDetails(context)   // denied before: the switch is in LIAN's app settings
    }
    var clipHint by remember { mutableStateOf<ClipHint?>(null) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()

    LifecycleResumeEffect(Unit) {
        shieldOn = ShieldListenerService.enabled(context)
        alertsAllowed = Notifications.permitted(context)
        val now = System.currentTimeMillis()
        graph.shield.prune(now)
        graph.prefs.refreshShieldCounts(now / TimeUnit.DAYS.toMillis(1))
        clipHint = clipboardHint(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(pending) {
        when (val p = pending) {
            is Inbound.Text -> { graph.inbound.value = null; vm.checkText(p.text); list.animateScrollToItem(manualIndex(alerts.size)) }
            is Inbound.Open -> when (p.target) {
                Inbound.Shortcut.SHIELD_ALERT -> { graph.inbound.value = null; vm.expand(p.itemId) }
                Inbound.Shortcut.CHECK_MESSAGE -> { graph.inbound.value = null; list.animateScrollToItem(manualIndex(alerts.size)); runCatching { focus.requestFocus() } }
                else -> Unit
            }
            else -> Unit
        }
    }

    var confirmOpen by remember { mutableStateOf<Pair<String, LinkBand>?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = top + 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.shield_title), stringResource(R.string.shield_subtitle)) }
        item {
            StatusHero(
                shieldOn, counts, alertsAllowed,
                onEnable = enableShield,
                onAllowAlerts = { if (Build.VERSION.SDK_INT >= 33) allowAlerts.launch(Manifest.permission.POST_NOTIFICATIONS) else SystemSettings.appDetails(context) },
            )
        }
        item { HowItWorks() }
        item { Catches() }
        item { SectionHeader(stringResource(R.string.shield_recent)) }
        if (alerts.isEmpty()) {
            // Counters persist, warnings don't (memory only): say so rather than contradict the counter.
            val text = if (counts.second > 0) R.string.shield_recent_cleared else R.string.shield_recent_empty
            item { Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(alerts, key = { it.id }) { a ->
            AlertCard(a, expanded = state.expanded == a.id, onToggle = { vm.toggle(a.id) }, onCopy = { copySensitive(context, it) }, onOpenRequest = { u, b -> confirmOpen = u to b })
        }
        item(key = "manual") {
            SectionHeader(stringResource(R.string.shield_manual))
            Text(stringResource(R.string.shield_manual_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        clipHint?.let { hint ->
            item {
                GlassCard(Modifier.fillMaxWidth(), onClick = { readClipboard(context)?.let(vm::checkText); clipHint = null }, padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(R.drawable.ic_paste, Accent.SKY, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(if (hint == ClipHint.LINK) R.string.shield_clip_link else R.string.shield_clip_text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.shield_clip_check), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = vm::onInput,
                    label = { Text(stringResource(R.string.link_input_label)) },
                    placeholder = { Text(stringResource(R.string.link_input_hint)) },
                    minLines = 3,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { readClipboard(context)?.let(vm::onInput) }) { Text(stringResource(R.string.link_paste)) }
                    GradientButton(stringResource(R.string.link_check), vm::check, enabled = state.input.isNotBlank() && !state.checking, icon = R.drawable.ic_search)
                    TextButton(onClick = vm::clear) { Text(stringResource(R.string.link_clear)) }
                }
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.shield_tip), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.checking) item { Loading() }
        state.result?.let { r -> item { TextResult(r, onCopy = { copySensitive(context, it) }, onOpenRequest = { u, b -> confirmOpen = u to b }) } }
    }
    confirmOpen?.let { (url, band) -> OpenLinkDialog(url, band, onDismiss = { confirmOpen = null }) { openInBrowser(context, it) } }
}

/** Index of the manual-check section: header, status, how-it-works, catches, "recent" header, then the alerts (or one empty line). */
private fun manualIndex(alerts: Int) = 5 + alerts.coerceAtLeast(1)

/**
 * The first thing on the tab: is Scam Shield protecting me? Off: what it does and one button. On: a live
 * dot and today's numbers, big enough to read at a glance.
 */
@Composable
private fun StatusHero(on: Boolean, counts: Pair<Int, Int>, alertsAllowed: Boolean, onEnable: () -> Unit, onAllowAlerts: () -> Unit) {
    val c = LocalLianColors.current
    GlassCard(Modifier.fillMaxWidth().appear(0)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(R.drawable.ic_scam_shield, if (on) Accent.MINT else Accent.VIOLET, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (on) R.string.shield_is_on else R.string.shield_is_off), style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(if (on) c.safe else MaterialTheme.colorScheme.outline, active = on, size = 7.dp)
                    Text(stringResource(if (on) R.string.shield_auto_on else R.string.shield_auto_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.size(16.dp))
        if (on) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigNumber(counts.first, stringResource(R.string.shield_stat_checked), c.safe, Modifier.weight(1f))
                BigNumber(counts.second, stringResource(R.string.shield_stat_warnings), if (counts.second > 0) c.danger else c.safe, Modifier.weight(1f))
            }
            if (!alertsAllowed) {
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.shield_alerts_off), style = MaterialTheme.typography.bodySmall, color = c.danger)
                TextButton(onClick = onAllowAlerts, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.shield_allow_alerts)) }
            }
            TextButton(onClick = onEnable, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.manage)) }
        } else {
            GradientButton(stringResource(R.string.shield_enable), onEnable, Modifier.fillMaxWidth(), icon = R.drawable.ic_scam_shield)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.shield_access_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BigNumber(value: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Three steps that say exactly what Scam Shield does, and that it all happens on the phone. */
@Composable
private fun HowItWorks() {
    GlassCard(Modifier.fillMaxWidth().appear(1)) {
        Text(stringResource(R.string.shield_how_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(14.dp))
        Row(verticalAlignment = Alignment.Top) {
            Step(R.drawable.ic_message, Accent.SKY, R.string.shield_how_1, Modifier.weight(1f))
            StepArrow()
            Step(R.drawable.ic_lock, Accent.VIOLET, R.string.shield_how_2, Modifier.weight(1f))
            StepArrow()
            Step(R.drawable.ic_warning, Accent.CORAL, R.string.shield_how_3, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Step(icon: Int, accent: Accent, label: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconTile(icon, accent, 44.dp)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StepArrow() {
    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp).size(20.dp))
}

/** Concrete examples beat abstractions: the scams people actually get. */
@Composable
private fun Catches() {
    val c = LocalLianColors.current
    Column(Modifier.appear(2)) {
        Text(stringResource(R.string.shield_catches_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.shield_catch_delivery), c.danger, R.drawable.ic_download)
            Pill(stringResource(R.string.shield_catch_bank), c.caution, R.drawable.ic_lock)
            Pill(stringResource(R.string.shield_catch_family), MaterialTheme.colorScheme.primary, R.drawable.ic_person)
            Pill(stringResource(R.string.shield_catch_codes), c.danger, R.drawable.ic_key)
            Pill(stringResource(R.string.shield_catch_links), c.info, R.drawable.ic_search)
        }
    }
}

@Composable
private fun AlertCard(a: ShieldStore.Alert, expanded: Boolean, onToggle: () -> Unit, onCopy: (String) -> Unit, onOpenRequest: (String, LinkBand) -> Unit) {
    val color = bandColor(a.result.band)
    GlassCard(Modifier.fillMaxWidth().animateContentSize(), onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_message), null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(bandTitle(a.result.band), style = MaterialTheme.typography.titleSmall, color = color)
                Text(
                    stringResource(R.string.shield_from, a.sender ?: a.appLabel, relativeTime(a.atMs)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Pill(a.appLabel, MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            Spacer(Modifier.size(12.dp))
            Text(
                a.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
            )
            Spacer(Modifier.size(12.dp))
            TextResult(a.result, onCopy, onOpenRequest)
        }
    }
}

@Composable
private fun relativeTime(atMs: Long): String {
    val now = System.currentTimeMillis()
    return if (now - atMs < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.just_now)
    else DateUtils.getRelativeTimeSpanString(atMs, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/**
 * Peeks at the clipboard's description only (type and Android's own URL classification), which shows
 * no system notice; the content itself is read only if the user taps the suggestion.
 */
private fun clipboardHint(context: android.content.Context): ClipHint? {
    val desc = try { context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription } catch (_: SecurityException) { null } ?: return null
    if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType("text/*")) return null
    if (Build.VERSION.SDK_INT >= 31 && desc.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE) {
        return if (desc.getConfidenceScore(TextClassifier.TYPE_URL) > 0.5f) ClipHint.LINK else ClipHint.TEXT
    }
    return ClipHint.TEXT
}
