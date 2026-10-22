package lian.app.ui.apps

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lian.app.AppGraph
import lian.app.R
import lian.app.ui.FindingRow
import lian.app.ui.Loading
import lian.app.ui.MonoText
import lian.app.ui.design.GlassCard
import lian.app.ui.design.GradientButton
import lian.app.ui.design.IconTile
import lian.app.ui.design.Pill
import lian.app.ui.theme.Accent
import lian.app.ui.theme.LocalLianColors
import lian.core.engine.Severity
import lian.core.engine.apk.ApkFacts
import lian.core.engine.apk.ApkFormatException
import lian.core.engine.apk.ApkReport
import lian.core.engine.apk.InstalledContext
import lian.core.platform.untrusted.UntrustedClient

/** APK files are never stored: the user's file is read through a descriptor in the isolated process (Phase 3.5). */
class ApkCheckViewModel(private val graph: AppGraph) : ViewModel() {
    data class State(val busy: Boolean = false, val report: ApkReport? = null, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun check(uri: Uri) {
        if (_state.value.busy) return
        _state.value = State(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                when (val outcome = graph.untrusted.parseApk(uri)) {
                    is UntrustedClient.Outcome.Failed -> State(error = outcome.reason)
                    is UntrustedClient.Outcome.Ok -> try {
                        val facts = ApkFacts.decode(outcome.text)
                        val installed = InstalledContext(
                            samePackageSigners = graph.appInventory.signerHistory(facts.packageName),
                            sameLabelPackage = facts.label?.let { graph.appInventory.packageWithLabel(it, except = facts.packageName) },
                        )
                        State(report = graph.apkAnalyzer.analyze(facts, installed, graph.context()))
                    } catch (e: ApkFormatException) {
                        State(error = e.message)
                    }
                }
            }
        }
    }

    fun dismiss() {
        _state.value = State()
    }
}

/** The system file picker grants read access to just the chosen file; no storage permission is needed. */
@Composable
fun rememberApkPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onPicked) }
    return remember(picker) { { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) } }
}

/** "Check before you install": one friendly card, one button. The verdict opens as a sheet. */
@Composable
fun ApkCheckCard(busy: Boolean, error: String?, onPick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(R.drawable.ic_download, Accent.MINT, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.apk_card_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.apk_card_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (busy) Loading(stringResource(R.string.apk_reading)) else GradientButton(stringResource(R.string.apk_pick), onPick, icon = R.drawable.ic_search)
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.apk_failed, it), style = MaterialTheme.typography.bodySmall, color = LocalLianColors.current.danger)
        }
    }
}

/**
 * The verdict, easy to digest: a coloured banner (safe to install or not), what the app would be able
 * to reach, the red flags in plain words, and the technical details folded away for those who want them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkResultSheet(report: ApkReport, onDismiss: () -> Unit) {
    val facts = report.facts
    var details by remember { mutableStateOf(false) }
    val color = appBandColor(report.band)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f))))) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(appBandIcon(report.band)), null, tint = Color.White)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(stringResource(apkVerdict(report)), style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(
                            listOfNotNull(facts.label, facts.versionName?.let { stringResource(R.string.apk_version, it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f),
                        )
                    }
                }
            }

            Text(stringResource(R.string.apk_sheet_access), style = MaterialTheme.typography.titleSmall)
            AccessGrid(facts.declared.sorted())

            val (good, flags) = report.findings.partition { it.severity == Severity.INFO }
            Text(stringResource(R.string.apk_sheet_flags), style = MaterialTheme.typography.titleSmall)
            if (flags.isEmpty()) {
                Pill(stringResource(R.string.apk_sheet_no_flags), LocalLianColors.current.safe, R.drawable.ic_check_circle)
            } else {
                flags.forEachIndexed { i, f -> if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); FindingRow(f) }
            }
            if (good.isNotEmpty()) {
                Text(stringResource(R.string.good_signs), style = MaterialTheme.typography.titleSmall)
                good.forEach { FindingRow(it) }
            }

            Text(stringResource(R.string.apk_install_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { details = !details }) { Text(stringResource(if (details) R.string.apk_hide_details else R.string.apk_show_details)) }
            AnimatedVisibility(details) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.apk_package), style = MaterialTheme.typography.labelLarge)
                    MonoText(facts.packageName)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.apk_signer), style = MaterialTheme.typography.labelLarge)
                    facts.signing.certificates.forEach { MonoText(it.sha256.chunked(16).joinToString(" ")); MonoText(it.subject) }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.apk_permissions, facts.permissions.size), style = MaterialTheme.typography.labelLarge)
                    facts.permissions.forEach { MonoText(it.removePrefix("android.permission.")) }
                    Text(report.ruleSetVersion, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** The headline in install terms rather than risk jargon. */
private fun apkVerdict(report: ApkReport) = when (report.band) {
    lian.core.engine.app.AppBand.LOW -> R.string.apk_verdict_ok
    lian.core.engine.app.AppBand.MEDIUM -> R.string.apk_verdict_careful
    else -> R.string.apk_verdict_dont
}
