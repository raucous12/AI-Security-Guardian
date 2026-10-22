package lian.app.ui.apps

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
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
import lian.app.shield.ShieldStore
import lian.app.ui.FindingRow
import lian.app.ui.Loading
import lian.app.ui.MonoText
import lian.app.ui.SystemSettings
import lian.app.ui.design.DonutRing
import lian.app.ui.design.GlassCard
import lian.app.ui.design.GradientButton
import lian.app.ui.design.HourBars
import lian.app.ui.design.IconTile
import lian.app.ui.design.Pill
import lian.app.ui.design.ScreenHeader
import lian.app.ui.design.SectionHeader
import lian.app.ui.design.appear
import lian.app.ui.openFix
import lian.app.ui.severityColor
import lian.app.ui.theme.Accent
import lian.app.ui.theme.LocalLianColors
import lian.core.engine.FixTarget
import lian.core.engine.Severity
import lian.core.engine.app.AppBand
import lian.core.engine.app.AppReport
import lian.core.engine.app.Capability
import lian.core.engine.app.Provenance
import lian.core.engine.background.BackgroundReport
import lian.core.engine.watch.WatchEvent

/** The list's lenses, as chips. Each answers one question a person actually has about their apps. */
enum class AppFilter(val label: Int) {
    ATTENTION(R.string.apps_filter_attention),
    UNSEEN(R.string.apps_filter_unseen),
    SCREEN(R.string.apps_filter_screen),
    LOCATION(R.string.apps_filter_location),
    CHANGES(R.string.apps_filter_changes),
    ALL(R.string.apps_filter_all),
}

class AppsViewModel(private val graph: AppGraph) : ViewModel() {
    data class EventLine(val event: WatchEvent, val appLabel: String?)
    data class State(
        val disclosureAccepted: Boolean,
        val filter: AppFilter = AppFilter.ATTENTION,
        val query: String = "",
        val reports: List<AppReport>? = null,
        val running: Boolean = false,
        val background: List<BackgroundReport>? = null,
        val usageAccess: Boolean = false,
        val bgRunning: Boolean = false,
        val events: List<EventLine> = emptyList(),
        val lastWatchRun: Long = 0,
    )

    private val _state = MutableStateFlow(
        State(graph.prefs.auditDisclosureAccepted, reports = graph.lastAudit.value, background = graph.lastBackground.value, lastWatchRun = graph.prefs.lastWatchRun),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        if (_state.value.disclosureAccepted) {
            if (_state.value.reports == null) refresh() else refreshBackground()
        }
    }

    fun select(filter: AppFilter) = _state.update { it.copy(filter = filter) }
    fun search(query: String) = _state.update { it.copy(query = query.take(60)) }

    fun accept() {
        graph.prefs.auditDisclosureAccepted = true
        _state.update { it.copy(disclosureAccepted = true) }
        refresh()
    }

    fun refresh() {
        if (_state.value.running) return
        _state.update { it.copy(running = true) }
        viewModelScope.launch {
            val reports = withContext(Dispatchers.IO) { graph.auditNow() }
            _state.update { it.copy(reports = reports, running = false) }
            refreshBackground()
        }
    }

    /** Re-reads Android's usage records; called on every resume, since usage access may have just been granted. */
    fun refreshBackground() {
        val apps = _state.value.reports?.map { it.facts } ?: return
        if (_state.value.bgRunning) return
        _state.update { it.copy(bgRunning = true) }
        viewModelScope.launch {
            val (access, reports) = withContext(Dispatchers.IO) { graph.backgroundNow(apps) }
            _state.update { it.copy(background = reports, usageAccess = access, bgRunning = false) }
        }
    }

    fun loadEvents() {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) {
                try {
                    graph.watch.lastEvents().map { e -> EventLine(e, e.appFp?.let(graph.watch::labelFor)) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            _state.update { it.copy(events = lines, lastWatchRun = graph.prefs.lastWatchRun) }
        }
    }
}

/** An app needs attention when any finding carries weight (informational findings don't count). */
fun AppReport.needsAttention(): Boolean = score >= 20 || findings.any { it.weight >= 30 }

private fun AppReport.matches(filter: AppFilter, background: Map<String, BackgroundReport>): Boolean = when (filter) {
    AppFilter.ATTENTION -> needsAttention()
    AppFilter.UNSEEN -> (background[facts.packageName]?.score ?: 0) > 0
    AppFilter.SCREEN -> Capability.ACCESSIBILITY in facts.active || Capability.NOTIFICATION_LISTENER in facts.active
    AppFilter.LOCATION -> Capability.BACKGROUND_LOCATION in facts.active
    AppFilter.CHANGES, AppFilter.ALL -> true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsRoute(graph: AppGraph) {
    val vm: AppsViewModel = viewModel { AppsViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val installs by graph.shield.installs.collectAsStateWithLifecycle()
    val pending by graph.inbound.collectAsStateWithLifecycle()
    val apk: ApkCheckViewModel = viewModel { ApkCheckViewModel(graph) }
    val apkState by apk.state.collectAsStateWithLifecycle()
    val pickApk = rememberApkPicker(apk::check)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        vm.loadEvents()
        if (state.disclosureAccepted) vm.refreshBackground()
        onPauseOrDispose { }
    }
    LaunchedEffect(pending) {
        when (val p = pending) {
            is Inbound.Apk -> { graph.inbound.value = null; apk.check(p.uri.toUri()) }
            is Inbound.Open -> when (p.target) {
                Inbound.Shortcut.APP_ALERT -> { graph.inbound.value = null; vm.select(AppFilter.CHANGES) }
                Inbound.Shortcut.CHECK_APK -> { graph.inbound.value = null; pickApk() }
                else -> Unit
            }
            else -> Unit
        }
    }

    val bgByPkg = remember(state.background) { state.background.orEmpty().associateBy { it.facts.app.packageName } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = top + 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(stringResource(R.string.apps_title), stringResource(R.string.apps_subtitle)) {
                if (state.disclosureAccepted) {
                    IconButton(onClick = vm::refresh, enabled = !state.running) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.retry)) }
                }
            }
        }
        if (!state.disclosureAccepted) {
            item { Disclosure(vm::accept) }
            item { ApkCheckCard(apkState.busy, apkState.error, pickApk) }
            return@LazyColumn
        }
        val reports = state.reports
        if (reports == null) {
            item { Loading() }
            return@LazyColumn
        }
        item { Overview(reports, state.background, vm::select) }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::search,
                placeholder = { Text(stringResource(R.string.apps_search_hint)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.search("") }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.link_clear)) } },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            val counts = AppFilter.entries.associateWith { f ->
                when (f) {
                    AppFilter.CHANGES -> installs.size + state.events.size
                    else -> reports.count { it.matches(f, bgByPkg) }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AppFilter.entries) { f ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { vm.select(f) },
                        label = { Text("${stringResource(f.label)}  ${counts[f] ?: 0}") },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        }

        when (state.filter) {
            AppFilter.CHANGES -> {
                item { Text(stringResource(R.string.apps_install_guard), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(installs, key = { "i:" + it.packageName + it.atMs }) { i -> InstallRow(i, Modifier.animateItem()) { selected = i.packageName } }
                item { WatchCard(state.lastWatchRun, state.events) }
            }
            else -> {
                if (state.filter == AppFilter.UNSEEN && !state.usageAccess) {
                    item { UsageAccessCard(LocalContext.current.let { ctx -> { SystemSettings.usageAccess(ctx) } }) }
                }
                val q = state.query.trim().lowercase()
                val shown = reports.filter { it.matches(state.filter, bgByPkg) && (q.isEmpty() || q in it.facts.label.lowercase() || q in it.facts.packageName) }
                    .let { list -> if (state.filter == AppFilter.UNSEEN) list.sortedByDescending { bgByPkg[it.facts.packageName]?.score ?: 0 } else list }
                if (shown.isEmpty()) item { AllClear(stringResource(if (q.isEmpty()) R.string.apps_empty_filter else R.string.apps_empty_search)) }
                items(shown, key = { "r:" + it.facts.packageName }) { r -> AppRow(r, bgByPkg[r.facts.packageName], Modifier.animateItem()) { selected = r.facts.packageName } }
            }
        }
        item { ApkCheckCard(apkState.busy, apkState.error, pickApk) }
    }

    // Details slide up as a sheet: quick to open, quick to swipe away.
    selected?.let { pkg ->
        val report = state.reports?.firstOrNull { it.facts.packageName == pkg } ?: installs.firstOrNull { it.packageName == pkg }?.report
        if (report != null) {
            ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                AppDetail(report, bgByPkg[pkg])
            }
        }
    }
    apkState.report?.let { ApkResultSheet(it, onDismiss = apk::dismiss) }
}

@Composable
private fun Disclosure(onAccept: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().appear(0)) {
        IconTile(R.drawable.ic_apps, Accent.VIOLET, 52.dp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.apps_disclosure_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.apps_disclosure_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        GradientButton(stringResource(R.string.continue_label), onAccept)
    }
}

/** The whole picture in one glance: a ring of fine / worth a look / risky, and how many ran unseen today. */
@Composable
private fun Overview(reports: List<AppReport>, background: List<BackgroundReport>?, onFilter: (AppFilter) -> Unit) {
    val c = LocalLianColors.current
    val fine = reports.count { !it.needsAttention() }
    val risky = reports.count { it.needsAttention() && it.band >= AppBand.HIGH }
    val look = reports.size - fine - risky
    val unseen = background?.count { it.facts.ranUnseen }
    GlassCard(Modifier.fillMaxWidth().appear(0)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutRing(listOf(fine to c.safe, look to c.caution, risky to c.danger), size = 124.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(reports.size.toString(), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.apps_ring_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Legend(c.safe, stringResource(R.string.apps_legend_fine), fine) { onFilter(AppFilter.ALL) }
                Legend(c.caution, stringResource(R.string.apps_legend_look), look) { onFilter(AppFilter.ATTENTION) }
                Legend(c.danger, stringResource(R.string.apps_legend_risky), risky) { onFilter(AppFilter.ATTENTION) }
                Legend(MaterialTheme.colorScheme.primary, stringResource(R.string.apps_legend_unseen), unseen) { onFilter(AppFilter.UNSEEN) }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: Int?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value?.toString() ?: "–", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun AllClear(text: String) {
    GlassCard(Modifier.fillMaxWidth().appear(1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(R.drawable.ic_check_circle, Accent.MINT, 44.dp)
            Spacer(Modifier.width(14.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
    }
}

/** One app: its real icon, name, a short line, what it can reach (tiny coloured icons), and its verdict. */
@Composable
private fun AppRow(r: AppReport, bg: BackgroundReport?, modifier: Modifier, onClick: () -> Unit) {
    val unseen = bg?.facts?.usage?.takeIf { bg.facts.ranUnseen }
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(r.facts.packageName, r.facts.label, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.facts.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    if (unseen != null) stringResource(R.string.bg_unseen, duration(unseen.unseenMs)) else provenanceText(r),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unseen != null) LocalLianColors.current.caution else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                val access = r.facts.active.sorted()
                if (access.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    AccessDots(access)
                }
            }
            Spacer(Modifier.width(8.dp))
            Pill(stringResource(appBandLabel(r.band)), appBandColor(r.band))
        }
    }
}

@Composable
private fun AccessDots(access: List<Capability>, max: Int = 6) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        access.take(max).forEach { cap ->
            val color = accessColor(cap)
            Box(Modifier.size(22.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(accessIcon(cap)), stringResource(accessLabel(cap)), tint = color, modifier = Modifier.size(13.dp))
            }
        }
        if (access.size > max) Text("+${access.size - max}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Everything about one app, in a sheet: what it can reach, what it did in the background, and why it was flagged. */
@Composable
private fun AppDetail(report: AppReport, background: BackgroundReport?) {
    val context = LocalContext.current
    var warn by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(report.facts.packageName, report.facts.label, 60.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(report.facts.label, style = MaterialTheme.typography.titleLarge)
                MonoText(report.facts.packageName)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(appBandLabel(report.band)), appBandColor(report.band), appBandIcon(report.band))
            Pill(provenanceText(report), MaterialTheme.colorScheme.primary)
        }

        Text(stringResource(R.string.app_access_title), style = MaterialTheme.typography.titleSmall)
        AccessGrid(report.facts.active.sorted())

        background?.facts?.usage?.takeIf { it.hourlyMinutes.any { m -> m > 0 } }?.let { u ->
            Text(stringResource(R.string.bg_last24), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.bg_unseen, duration(u.unseenMs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HourBars(u.hourlyMinutes, if (background.band >= AppBand.HIGH) Accent.CORAL else Accent.VIOLET)
        }

        val (good, flags) = (report.findings + background?.findings.orEmpty()).partition { it.severity == Severity.INFO }
        if (flags.isNotEmpty()) {
            Text(stringResource(R.string.app_why_title), style = MaterialTheme.typography.titleSmall)
            flags.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FindingRow(f.copy(fix = f.fix?.takeIf { it != FixTarget.APP_DETAILS }), report.facts.packageName)
            }
        }
        if (good.isNotEmpty()) {
            Text(stringResource(R.string.good_signs), style = MaterialTheme.typography.titleSmall)
            good.forEach { FindingRow(it) }
        }
        if (background?.findings?.isNotEmpty() == true) {
            Text(stringResource(R.string.bg_restrict_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GradientButton(
            stringResource(R.string.apps_open_details),
            { if (report.band == AppBand.CRITICAL) warn = true else openFix(context, FixTarget.APP_DETAILS, report.facts.packageName) },
            Modifier.fillMaxWidth(),
            icon = R.drawable.ic_settings,
        )
    }
    // Safety ordering for critical findings (Phase 3.4): warn that removal may alert whoever installed it.
    if (warn) {
        AlertDialog(
            onDismissRequest = { warn = false },
            title = { Text(stringResource(R.string.apps_critical_warning_title)) },
            text = { Text(stringResource(R.string.apps_critical_warning_body)) },
            confirmButton = { TextButton(onClick = { warn = false; openFix(context, FixTarget.APP_DETAILS, report.facts.packageName) }) { Text(stringResource(R.string.apps_critical_continue)) } },
            dismissButton = { TextButton(onClick = { warn = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** What an app can reach, as friendly labelled chips with icons (or a calm "nothing sensitive"). */
@Composable
fun AccessGrid(access: List<Capability>) {
    if (access.isEmpty()) {
        Pill(stringResource(R.string.app_access_none), LocalLianColors.current.safe, R.drawable.ic_check_circle)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        access.forEach { Pill(stringResource(accessLabel(it)), accessColor(it), accessIcon(it)) }
    }
}

@Composable
private fun UsageAccessCard(onGrant: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().appear(0)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(R.drawable.ic_eye, Accent.AMBER, 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.bg_usage_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.bg_usage_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        GradientButton(stringResource(R.string.bg_usage_grant), onGrant, icon = R.drawable.ic_eye)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.bg_static_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun InstallRow(i: ShieldStore.Install, modifier: Modifier, onClick: () -> Unit) {
    val r = i.report
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(r.facts.packageName, r.facts.label, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.facts.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(stringResource(R.string.apps_installed_at, DateUtils.getRelativeTimeSpanString(i.atMs).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill(stringResource(appBandLabel(r.band)), appBandColor(r.band), appBandIcon(r.band))
        }
    }
}

@Composable
private fun WatchCard(lastRun: Long, events: List<AppsViewModel.EventLine>) {
    SectionHeader(stringResource(R.string.changes_watch_title))
    Spacer(Modifier.height(8.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            if (lastRun > 0) stringResource(R.string.watch_last_run, DateUtils.getRelativeTimeSpanString(lastRun).toString(), events.size)
            else stringResource(R.string.watch_never_run),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text(stringResource(if (lastRun > 0) R.string.watch_no_events else R.string.changes_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        events.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(severityColor(e.event.severity)))
                Spacer(Modifier.width(10.dp))
                Text(eventText(e), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun duration(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** The app's own launcher icon, loaded off the main thread; a lettered tile until then (or if it has none). */
@Composable
fun AppIcon(packageName: String, label: String, size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    val shape = RoundedCornerShape(size * 0.28f)
    val bmp = icon
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun provenanceText(r: AppReport): String {
    val store = r.storeName.orEmpty()
    return when (r.provenance) {
        Provenance.SYSTEM -> stringResource(R.string.prov_system)
        Provenance.STORE_REVIEWED -> stringResource(R.string.prov_store_reviewed, store)
        Provenance.STORE_INDEPENDENT -> stringResource(R.string.prov_store_independent, store)
        Provenance.FILE_INSTALL -> stringResource(R.string.prov_file)
        Provenance.INSTALLED_BY_APP -> stringResource(R.string.prov_by_app, store)
        Provenance.SPOOFED_STORE -> stringResource(R.string.prov_spoofed, store)
        Provenance.ADB_OR_UNKNOWN -> stringResource(R.string.prov_adb)
    }
}

@Composable
private fun eventText(line: AppsViewModel.EventLine): String {
    val app = line.appLabel ?: stringResource(R.string.event_unknown_app)
    val e = line.event
    return when (e.type) {
        WatchEvent.Type.SIGNER_CHANGED -> stringResource(R.string.event_signer_changed, app)
        WatchEvent.Type.ICON_HIDDEN -> stringResource(R.string.event_icon_hidden, app)
        WatchEvent.Type.SPECIAL_ACCESS_GAINED -> stringResource(R.string.event_special_access, app, e.detail.lowercase().replace('_', ' '))
        WatchEvent.Type.NEW_APP -> stringResource(R.string.event_new_app, app)
        WatchEvent.Type.RISK_ROSE -> stringResource(R.string.event_risk_rose, app, e.detail)
        WatchEvent.Type.USER_CA_ADDED -> stringResource(R.string.event_user_ca, e.detail)
        WatchEvent.Type.POSTURE_REGRESSED -> stringResource(R.string.event_posture, e.detail)
    }
}

fun accessIcon(cap: Capability) = when (cap) {
    Capability.ACCESSIBILITY -> R.drawable.ic_accessibility
    Capability.DEVICE_ADMIN -> R.drawable.ic_lock
    Capability.NOTIFICATION_LISTENER -> R.drawable.ic_bell
    Capability.KEYBOARD -> R.drawable.ic_keyboard
    Capability.SMS -> R.drawable.ic_message
    Capability.OVERLAY -> R.drawable.ic_layers
    Capability.BACKGROUND_LOCATION -> R.drawable.ic_location
    Capability.USAGE_ACCESS -> R.drawable.ic_eye
    Capability.INSTALLS_APPS -> R.drawable.ic_download
    Capability.VPN -> R.drawable.ic_key
    Capability.CAMERA -> R.drawable.ic_camera
    Capability.MICROPHONE -> R.drawable.ic_mic
    Capability.CONTACTS -> R.drawable.ic_person
    Capability.CALL_LOG -> R.drawable.ic_phone
}

fun accessLabel(cap: Capability) = when (cap) {
    Capability.ACCESSIBILITY -> R.string.access_accessibility
    Capability.DEVICE_ADMIN -> R.string.access_device_admin
    Capability.NOTIFICATION_LISTENER -> R.string.access_notifications
    Capability.KEYBOARD -> R.string.access_keyboard
    Capability.SMS -> R.string.access_sms
    Capability.OVERLAY -> R.string.access_overlay
    Capability.BACKGROUND_LOCATION -> R.string.access_location
    Capability.USAGE_ACCESS -> R.string.access_usage
    Capability.INSTALLS_APPS -> R.string.access_installs
    Capability.VPN -> R.string.access_vpn
    Capability.CAMERA -> R.string.access_camera
    Capability.MICROPHONE -> R.string.access_mic
    Capability.CONTACTS -> R.string.access_contacts
    Capability.CALL_LOG -> R.string.access_call_log
}

/** Reaching your screen or messages is the most sensitive (red); personal data amber; the rest violet. */
@Composable
fun accessColor(cap: Capability): Color {
    val c = LocalLianColors.current
    return when (cap) {
        Capability.ACCESSIBILITY, Capability.DEVICE_ADMIN, Capability.NOTIFICATION_LISTENER, Capability.SMS, Capability.OVERLAY -> c.danger
        Capability.BACKGROUND_LOCATION, Capability.MICROPHONE, Capability.CAMERA, Capability.CONTACTS, Capability.CALL_LOG, Capability.KEYBOARD -> c.caution
        Capability.USAGE_ACCESS, Capability.INSTALLS_APPS, Capability.VPN -> MaterialTheme.colorScheme.primary
    }
}

fun appBandLabel(band: AppBand) = when (band) {
    AppBand.LOW -> R.string.app_band_low
    AppBand.MEDIUM -> R.string.app_band_medium
    AppBand.HIGH -> R.string.app_band_high
    AppBand.CRITICAL -> R.string.app_band_critical
}

fun appBandIcon(band: AppBand) = when (band) {
    AppBand.LOW -> R.drawable.ic_check_circle
    AppBand.MEDIUM -> R.drawable.ic_warning
    AppBand.HIGH, AppBand.CRITICAL -> R.drawable.ic_error
}

@Composable
fun appBandColor(band: AppBand): Color {
    val c = LocalLianColors.current
    return when (band) {
        AppBand.LOW -> c.safe
        AppBand.MEDIUM -> c.caution
        AppBand.HIGH, AppBand.CRITICAL -> c.danger
    }
}
