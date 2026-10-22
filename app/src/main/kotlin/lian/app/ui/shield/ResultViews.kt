package lian.app.ui.shield

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import lian.app.R
import lian.app.ui.FindingRow
import lian.app.ui.MonoText
import lian.app.ui.design.GlassCard
import lian.app.ui.theme.LocalLianColors
import lian.core.engine.link.DisplayText
import lian.core.engine.link.LinkBand
import lian.core.engine.link.LinkReport
import lian.core.engine.message.MessageReport
import lian.core.engine.message.TextCheckResult

private data class BandStyle(val icon: Int, val title: Int, val body: Int)

private fun styleOf(band: LinkBand) = when (band) {
    LinkBand.NO_WARNING_SIGNS -> BandStyle(R.drawable.ic_check_circle, R.string.band_none_title, R.string.band_none_body)
    LinkBand.CAUTION -> BandStyle(R.drawable.ic_warning, R.string.band_caution_title, R.string.band_caution_body)
    LinkBand.LIKELY_SCAM -> BandStyle(R.drawable.ic_error, R.string.band_likely_title, R.string.band_likely_body)
    LinkBand.DANGEROUS -> BandStyle(R.drawable.ic_error, R.string.band_danger_title, R.string.band_danger_body)
}

@Composable
fun bandColor(band: LinkBand): Color {
    val c = LocalLianColors.current
    return when (band) {
        LinkBand.NO_WARNING_SIGNS -> c.safe
        LinkBand.CAUTION -> c.caution
        LinkBand.LIKELY_SCAM, LinkBand.DANGEROUS -> c.danger
    }
}

@Composable
fun bandTitle(band: LinkBand): String = stringResource(styleOf(band).title)

/** The big verdict: a gradient banner in the verdict colour, icon + title + one line of advice. */
@Composable
fun VerdictBanner(band: LinkBand, modifier: Modifier = Modifier) {
    val style = styleOf(band)
    val color = bandColor(band)
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f))))) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(style.icon), null, tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(style.title), style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(stringResource(style.body), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.92f))
            }
        }
    }
}

/** Everything about one check: verdict, the wording findings and each link's report. */
@Composable
fun TextResult(result: TextCheckResult, onCopy: (String) -> Unit, onOpenRequest: (String, LinkBand) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (result.links.reports.isEmpty() && result.message == null) {
            Text(stringResource(R.string.link_none_found), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        VerdictBanner(result.band)
        result.message?.let { MessageCard(it) }
        if (result.links.reports.isNotEmpty()) {
            Text(stringResource(R.string.link_links_title), style = MaterialTheme.typography.titleMedium)
            result.links.reports.forEach { r -> ReportCard(r, 0, onCopy) { onOpenRequest(it, r.band) } }
        }
        Text(stringResource(R.string.link_offline_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.versions, result.links.ruleSetVersion, result.links.intelVersion), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun MessageCard(message: MessageReport) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_message), null, tint = bandColor(message.band), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.link_message_title), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.size(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (message.findings.isEmpty()) Text(stringResource(R.string.link_message_clean), style = MaterialTheme.typography.bodyMedium)
            message.findings.forEachIndexed { i, f -> if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); FindingRow(f) }
            if (!message.modelApplied) Text(stringResource(R.string.link_message_no_model), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun ReportCard(report: LinkReport, depth: Int, onCopy: (String) -> Unit, onOpenRequest: (String) -> Unit) {
    val color = bandColor(report.band)
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(styleOf(report.band).title), style = MaterialTheme.typography.titleSmall, color = color)
        }
        Spacer(Modifier.size(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoText(DisplayText.escape(report.input), MaterialTheme.colorScheme.onSurface)
            report.summary?.let { s ->
                s.registrable?.let { Labeled(stringResource(R.string.link_real_domain), it) }
                s.displayHost?.takeIf { it != s.registrable }?.let { Labeled(stringResource(R.string.link_host), it) }
            }
            if (report.findings.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            report.findings.forEach { FindingRow(it) }
            if (depth < 2) report.nested.forEach { n ->
                Text(stringResource(R.string.link_forwards_to), style = MaterialTheme.typography.labelLarge)
                ReportCard(n, depth + 1, onCopy, onOpenRequest)
            }
            if (depth == 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onCopy(report.input) }) { Text(stringResource(R.string.link_copy)) }
                if (report.summary?.isWebLink == true) TextButton(onClick = { onOpenRequest(report.input) }) { Text(stringResource(R.string.link_open)) }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        MonoText(value)
    }
}

/** Confirm before handing a link to the browser; LIAN itself never opens links. */
@Composable
fun OpenLinkDialog(url: String, band: LinkBand, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_open_confirm_title)) },
        text = { Text(stringResource(if (band == LinkBand.NO_WARNING_SIGNS) R.string.link_open_confirm_ok else R.string.link_open_confirm_warning)) },
        confirmButton = { TextButton(onClick = { onDismiss(); onOpen(url) }) { Text(stringResource(R.string.link_open_confirm_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.link_open_confirm_no)) } },
    )
}

/** Read only when the user taps (Android 12+ shows a system notice). */
fun readClipboard(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

/** Copies with the "sensitive" flag so keyboards and clipboard previews don't display it. */
fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("link", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
}

/** Only ever called after the user confirms; the link is handed to their browser, never fetched by LIAN. */
fun openInBrowser(context: Context, raw: String) {
    val url = if (raw.contains("://")) raw else "https://$raw"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.link_no_browser, Toast.LENGTH_SHORT).show()
    }
}
