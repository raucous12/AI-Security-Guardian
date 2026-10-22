package lian.app.ui.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lian.app.AppGraph
import lian.app.R
import lian.app.ui.LianTopBar
import lian.app.ui.MonoText
import lian.app.ui.RuleText
import lian.app.ui.design.GlassCard
import lian.app.ui.design.ScoreGauge
import lian.app.ui.design.SectionHeader
import lian.app.ui.design.appear
import lian.app.ui.home.bandLabel
import lian.app.ui.openFix
import lian.app.ui.theme.LocalLianColors
import lian.core.engine.device.CheckResult
import lian.core.engine.device.CheckStatus
import lian.core.engine.link.DisplayText

/** Phone health: the dial and every device check, with what to fix first. */
@Composable
fun HealthRoute(graph: AppGraph, onBack: () -> Unit) {
    val report by graph.lastPosture.collectAsStateWithLifecycle()
    val facts by graph.lastDevice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var running by rememberSaveable { mutableStateOf(false) }
    var showPassed by rememberSaveable { mutableStateOf(false) }
    val refresh: () -> Unit = {
        if (!running) {
            running = true
            scope.launch {
                withContext(Dispatchers.IO) { graph.postureNow() }
                running = false
            }
        }
    }

    Scaffold(containerColor = Color.Transparent, topBar = { LianTopBar(stringResource(R.string.health_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GlassCard(Modifier.fillMaxWidth().appear(0)) {
                    val r = report
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        ScoreGauge(r?.score, r?.band?.let { stringResource(bandLabel(it)) } ?: stringResource(R.string.loading), size = 200.dp)
                        facts?.let { Text(stringResource(R.string.device_model, it.manufacturer, it.model, it.release), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        r?.let {
                            val attention = it.checks.count { c -> c.status == CheckStatus.FAIL || c.status == CheckStatus.WARN }
                            Text(stringResource(R.string.home_checks_summary, attention, it.checks.count { c -> c.status != CheckStatus.INFO }), style = MaterialTheme.typography.labelLarge)
                        }
                        TextButton(onClick = refresh, enabled = !running) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            val r = report ?: return@LazyColumn
            val (passed, rest) = r.checks.partition { it.status == CheckStatus.PASS }
            if (rest.isNotEmpty()) item { SectionHeader(stringResource(R.string.health_to_fix)) }
            items(rest, key = { it.id.value }) { CheckCard(it) }
            item {
                SectionHeader(stringResource(R.string.home_passed, passed.size)) {
                    TextButton(onClick = { showPassed = !showPassed }) { Text(stringResource(if (showPassed) R.string.home_hide else R.string.home_show)) }
                }
            }
            item {
                AnimatedVisibility(showPassed) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { passed.forEach { CheckCard(it) } }
                }
            }
            item { Text(stringResource(R.string.device_unknown_note) + "  ·  " + r.ruleSetVersion, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
private fun CheckCard(check: CheckResult) {
    val copy = RuleText.of(check.id)
    val context = LocalContext.current
    val c = LocalLianColors.current
    val (icon, color) = when (check.status) {
        CheckStatus.PASS -> R.drawable.ic_check_circle to c.safe
        CheckStatus.WARN -> R.drawable.ic_warning to c.caution
        CheckStatus.FAIL -> R.drawable.ic_error to c.danger
        CheckStatus.UNKNOWN -> R.drawable.ic_info to MaterialTheme.colorScheme.outline
        CheckStatus.INFO -> R.drawable.ic_info to c.info
    }
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(copy.title), style = MaterialTheme.typography.titleSmall)
                if (check.status != CheckStatus.PASS) copy.body?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                check.evidence.forEach { MonoText("${it.key}: ${DisplayText.escape(it.value)}") }
                val fix = check.fix
                if (fix != null && (check.status == CheckStatus.WARN || check.status == CheckStatus.FAIL)) {
                    TextButton(onClick = { openFix(context, fix) }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.open_settings)) }
                }
            }
        }
    }
}
