package lian.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import lian.app.R
import lian.app.ui.theme.LocalLianColors
import lian.core.engine.Finding
import lian.core.engine.FixTarget
import lian.core.engine.Severity
import lian.core.engine.link.DisplayText

/** Top bar for pushed screens (app detail, privacy proof); transparent over the page background. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LianTopBar(title: String, onBack: (() -> Unit)?) {
    TopAppBar(
        title = { Text(title) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                }
            }
        },
    )
}

/** Machine values (hashes, domains) in monospace, laid out left-to-right so RTL layouts can't reorder them. */
@Composable
fun MonoText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
}

@Composable
fun Loading(text: String = stringResource(R.string.loading)) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Verdict colour for a severity: red, amber or the "fine" green, from the brand palette. */
@Composable
fun severityColor(severity: Severity): Color {
    val c = LocalLianColors.current
    return when (severity) {
        Severity.CRITICAL, Severity.HIGH -> c.danger
        Severity.MEDIUM, Severity.LOW -> c.caution
        Severity.INFO -> c.safe
    }
}

fun severityIcon(severity: Severity): Int = when (severity) {
    Severity.CRITICAL, Severity.HIGH -> R.drawable.ic_error
    Severity.MEDIUM, Severity.LOW -> R.drawable.ic_warning
    Severity.INFO -> R.drawable.ic_check_circle
}

/** One finding: a tinted badge, the localized title and explanation, then the evidence and its source. */
@Composable
fun FindingRow(finding: Finding, packageName: String? = null) {
    val copy = RuleText.of(finding.rule)
    val color = severityColor(finding.severity)
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(severityIcon(finding.severity)), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(copy.title), style = MaterialTheme.typography.titleSmall)
            copy.body?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            finding.evidence.forEach { MonoText("${it.key}: ${DisplayText.escape(it.value)}") }
            val sources = finding.evidence.map { it.source }.distinct().joinToString(", ")
            if (sources.isNotEmpty()) Text(stringResource(R.string.evidence_source, sources), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            finding.fix?.takeIf { it != FixTarget.APP_DETAILS || packageName != null }?.let { fix ->
                TextButton(onClick = { openFix(context, fix, packageName) }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.open_settings))
                }
            }
        }
    }
}

/**
 * Settings deep links for a finding. LIAN only ever opens Settings; it never changes or removes
 * anything itself (Play's Device and Network Abuse policy, and the safety ordering in Phase 3.4).
 */
fun openFix(context: Context, fix: FixTarget, packageName: String? = null) {
    val candidates = when (fix) {
        FixTarget.SYSTEM_UPDATE -> listOf(Intent("android.settings.SYSTEM_UPDATE_SETTINGS"), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        FixTarget.SECURITY, FixTarget.DEVICE_ADMIN -> listOf(Intent(Settings.ACTION_SECURITY_SETTINGS))
        FixTarget.DEVELOPER -> listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        FixTarget.NETWORK -> listOf(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        FixTarget.VPN -> listOf(Intent(Settings.ACTION_VPN_SETTINGS))
        FixTarget.ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        FixTarget.NOTIFICATION_ACCESS -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        FixTarget.APP_DETAILS -> listOfNotNull(packageName?.let { Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$it".toUri()) })
    } + Intent(Settings.ACTION_SETTINGS)
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // try the next, more general screen
        }
    }
}
