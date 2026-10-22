package lian.app.ui.settings

import android.Manifest
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
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
import lian.app.BuildConfig
import lian.app.R
import lian.app.shield.ShieldListenerService
import lian.app.ui.Loading
import lian.app.ui.SystemSettings
import lian.app.ui.brand.LianMark
import lian.app.ui.brand.LianWordmark
import lian.app.ui.design.GlassCard
import lian.app.ui.design.IconTile
import lian.app.ui.design.ScreenHeader
import lian.app.ui.design.SectionHeader
import lian.app.ui.design.SegmentedTabs
import lian.app.ui.design.appear
import lian.app.ui.shield.rememberShieldEnabler
import lian.app.ui.theme.Accent
import lian.app.ui.theme.LocalLianColors
import lian.app.ui.theme.ThemeMode
import lian.app.watch.Notifications
import lian.app.watch.WatchScheduler
import lian.core.storage.KeystoreKeys

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    data class State(val running: Boolean = false, val lastRun: Long = 0, val changes: Int = 0)

    private val _state = MutableStateFlow(State(lastRun = graph.prefs.lastWatchRun))
    val state: StateFlow<State> = _state.asStateFlow()

    init { load() }

    /** Called whenever the screen is shown again: a background Watch run may have happened meanwhile. */
    fun load() {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { try { graph.watch.lastEvents().size } catch (_: Exception) { 0 } }
            _state.update { it.copy(changes = n, lastRun = graph.prefs.lastWatchRun) }
        }
    }

    fun setWatch(enabled: Boolean) {
        graph.prefs.setWatchEnabled(enabled)
        if (enabled) {
            Notifications.createChannel(graph.app)
            WatchScheduler.schedule(graph.app)
            runNow()
        } else {
            WatchScheduler.cancel(graph.app)
        }
    }

    fun runNow() {
        if (_state.value.running) return
        _state.update { it.copy(running = true) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { try { graph.watch.run() } catch (_: Exception) { null } }
            _state.update { it.copy(running = false) }
            load()
        }
    }

    fun deleteAll() {
        WatchScheduler.cancel(graph.app)
        graph.watch.deleteAll()
        graph.lastAudit.value = null
        graph.lastBackground.value = null
        graph.shield.clear()
        graph.prefs.clear()
        try { KeystoreKeys.destroyAll() } catch (_: Exception) { }
        _state.value = State()
    }
}

@Composable
fun SettingsRoute(graph: AppGraph, openPrivacy: () -> Unit) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val watchOn by graph.prefs.watchEnabled.collectAsStateWithLifecycle()
    val hide by graph.prefs.hideScreens.collectAsStateWithLifecycle()
    val theme by graph.prefs.themeMode.collectAsStateWithLifecycle()
    val wallpaper by graph.prefs.wallpaperColors.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = LocalLianColors.current
    var notificationsAllowed by remember { mutableStateOf(Notifications.permitted(context)) }
    var shieldOn by remember { mutableStateOf(ShieldListenerService.enabled(context)) }
    var usageOn by remember { mutableStateOf(graph.backgroundProbe.hasUsageAccess()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        vm.setWatch(true)
    }
    val enableShield = rememberShieldEnabler()
    var confirmDelete by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        vm.load()
        notificationsAllowed = Notifications.permitted(context)
        shieldOn = ShieldListenerService.enabled(context)
        usageOn = graph.backgroundProbe.hasUsageAccess()
        onPauseOrDispose { }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = top + 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle)) { LianMark(Modifier.size(44.dp)) }

        SectionHeader(stringResource(R.string.settings_appearance))
        GlassCard(Modifier.fillMaxWidth().appear(0)) {
            SegmentedTabs(
                listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                theme.ordinal,
                { graph.prefs.setThemeMode(ThemeMode.entries[it]) },
            )
            if (Build.VERSION.SDK_INT >= 31) {
                Spacer(Modifier.height(12.dp))
                ToggleRow(R.drawable.ic_palette, Accent.PINK, stringResource(R.string.wallpaper_colors), stringResource(R.string.wallpaper_colors_body), wallpaper) {
                    graph.prefs.setWallpaperColors(it)
                }
            }
        }

        SectionHeader(stringResource(R.string.settings_protection))
        GlassCard(Modifier.fillMaxWidth().appear(1)) {
            LinkRow(R.drawable.ic_scam_shield, Accent.MINT, stringResource(R.string.home_status_shield), stringResource(if (shieldOn) R.string.shield_auto_on else R.string.shield_auto_off), shieldOn, enableShield)
            Divider()
            LinkRow(R.drawable.ic_eye, Accent.AMBER, stringResource(R.string.usage_access_entry), stringResource(if (usageOn) R.string.usage_access_on else R.string.usage_access_off), usageOn) {
                SystemSettings.usageAccess(context)
            }
            Divider()
            ToggleRow(R.drawable.ic_clock, Accent.SKY, stringResource(R.string.watch_title), stringResource(R.string.watch_body), watchOn) { on ->
                // Notification permission is asked only when the user turns Watch on (Phase 5.7).
                if (on && Build.VERSION.SDK_INT >= 33 && !Notifications.permitted(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.setWatch(on)
            }
            if (watchOn) {
                Spacer(Modifier.height(8.dp))
                if (!notificationsAllowed) Text(stringResource(R.string.watch_notifications_denied), style = MaterialTheme.typography.bodySmall, color = c.danger)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.lastRun > 0) stringResource(R.string.watch_last_run, DateUtils.getRelativeTimeSpanString(state.lastRun).toString(), state.changes)
                        else stringResource(R.string.watch_never_run),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    if (state.running) Loading("") else OutlinedButton(onClick = vm::runNow) { Text(stringResource(R.string.watch_run_now)) }
                }
            }
        }

        SectionHeader(stringResource(R.string.settings_privacy))
        GlassCard(Modifier.fillMaxWidth().appear(2)) {
            LinkRow(R.drawable.ic_lock, Accent.VIOLET, stringResource(R.string.privacy_title), stringResource(R.string.privacy_proof_entry_body), null, openPrivacy)
            Divider()
            ToggleRow(R.drawable.ic_eye, Accent.CORAL, stringResource(R.string.secure_title), stringResource(R.string.secure_body), hide) { graph.prefs.setHideScreens(it) }
            Divider()
            LinkRow(R.drawable.ic_delete, Accent.CORAL, stringResource(R.string.delete_title), stringResource(R.string.delete_body), null) { confirmDelete = true }
        }

        SectionHeader(stringResource(R.string.settings_about))
        GlassCard(Modifier.fillMaxWidth().appear(3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LianMark(Modifier.size(52.dp), animated = false)
                Spacer(Modifier.width(14.dp))
                Column {
                    LianWordmark(fontSize = 24.sp, letterSpacing = 5.sp)
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.usp_badge), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            listOf("Message" to graph.intel.messageModel, "Link name" to graph.intel.domainModel).forEach { (name, m) ->
                Text(
                    stringResource(
                        R.string.about_model, name, m.metadata["test.auc"].orEmpty(), m.metadata["test.high.precision"].orEmpty(),
                        m.metadata["sources"].orEmpty().replace(",", ", "),
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_data), style = MaterialTheme.typography.titleSmall)
            val ind = graph.intel.indicators
            Text(stringResource(R.string.about_indicators, ind.all.size, ind.packageCount, ind.certificateCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.about_licences), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(graph.intel.version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteAll()
                    Toast.makeText(context, R.string.deleted, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.delete_confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun ToggleRow(icon: Int, accent: Accent, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, accent, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A row that opens something; [on] shows a status dot (null: no status, just a chevron). */
@Composable
private fun LinkRow(icon: Int, accent: Accent, title: String, body: String, on: Boolean?, onClick: () -> Unit) {
    val c = LocalLianColors.current
    GlassRowClickable(onClick) {
        IconTile(icon, accent, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = if (on == null) MaterialTheme.colorScheme.onSurfaceVariant else if (on) c.safe else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GlassRowClickable(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
