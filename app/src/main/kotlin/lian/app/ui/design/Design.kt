package lian.app.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lian.app.ui.theme.Accent
import lian.app.ui.theme.LocalLianColors
import kotlin.math.cos
import kotlin.math.sin

/** Page background: a soft vertical wash with three blurred colour blobs (glows in dark mode). */
@Composable
fun LianBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = LocalLianColors.current
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.backgroundTop, c.backgroundBottom)))
            .drawBehind {
                val w = size.width
                val h = size.height
                drawCircle(Brush.radialGradient(listOf(c.blobs[0], Color.Transparent), Offset(w * 0.1f, h * 0.05f), w * 0.75f), w * 0.75f, Offset(w * 0.1f, h * 0.05f))
                drawCircle(Brush.radialGradient(listOf(c.blobs[1], Color.Transparent), Offset(w * 1.0f, h * 0.35f), w * 0.7f), w * 0.7f, Offset(w * 1.0f, h * 0.35f))
                drawCircle(Brush.radialGradient(listOf(c.blobs[2], Color.Transparent), Offset(w * 0.2f, h * 0.95f), w * 0.8f), w * 0.8f, Offset(w * 0.2f, h * 0.95f))
            },
        content = content,
    )
}

/** Frosted card: translucent surface, hairline border, tinted soft shadow (a glow in dark mode). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(24.dp),
    padding: PaddingValues = PaddingValues(18.dp),
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalLianColors.current
    val surface = tint ?: if (c.dark) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.9f)
    Column(
        modifier
            .shadow(if (c.dark) 0.dp else 14.dp, shape, ambientColor = c.glow, spotColor = c.glow)
            .clip(shape)
            .background(surface)
            .border(BorderStroke(1.dp, c.cardBorder), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Colourful glossy tile with a white glyph: the "3D icon" look of the references, drawn as vectors. */
@Composable
fun IconTile(icon: Int, accent: Accent, size: Dp = 48.dp, contentDescription: String? = null) {
    val (top, bottom) = LocalLianColors.current.accent(accent)
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        Modifier
            .size(size)
            .shadow(6.dp, shape, ambientColor = bottom.copy(alpha = 0.5f), spotColor = bottom.copy(alpha = 0.5f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(top, bottom)))
            .drawBehind {
                // Gloss: a soft white highlight across the top half.
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), endY = this.size.height * 0.55f))
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Big title used at the top of each tab. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Small rounded label with a tinted background. */
@Composable
fun Pill(text: String, color: Color, icon: Int? = null) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(painterResource(it), null, tint = color, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A live indicator: a solid dot with an expanding ring while [active]. */
@Composable
fun PulseDot(color: Color, active: Boolean, size: Dp = 10.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)), label = "p")
    Canvas(Modifier.size(size * 2.2f)) {
        val r = size.toPx() / 2
        if (active) drawCircle(color.copy(alpha = (1f - p) * 0.45f), radius = r + r * 1.2f * p)
        drawCircle(if (active) color else color.copy(alpha = 0.4f), radius = r)
    }
}

/** Pill-shaped segmented control; the selected option fills with the brand gradient. */
@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalLianColors.current
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val textColor by animateColorAsState(if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .then(if (on) Modifier.background(c.brandBrush) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = textColor, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The dial from the references: a 270° arc whose gradient runs danger → caution → safe, filled up to
 * [score] with an animated sweep, a knob at the tip and the number counting up in the middle.
 */
@Composable
fun ScoreGauge(score: Int?, label: String, modifier: Modifier = Modifier, size: Dp = 200.dp, caption: String? = null) {
    val c = LocalLianColors.current
    val target = (score ?: 0) / 100f
    val progress by animateFloatAsState(target, tween(1400, easing = FastOutSlowInEasing), label = "gauge")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.085f
            val inset = stroke / 2 + this.size.minDimension * 0.04f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (progress > 0.005f) {
                // The arc covers 0–0.75 of the sweep; the unused quarter is split so each round cap
                // (which pokes a few degrees past its end) keeps its own end's colour.
                val brush = Brush.sweepGradient(0f to c.danger, 0.35f to c.caution, 0.75f to c.safe, 0.85f to c.safe, 0.86f to c.danger, 1f to c.danger, center = center)
                // Rotated so the gradient (which starts at 3 o'clock) starts where the arc does.
                rotate(135f, center) {
                    drawArc(brush, 0f, 270f * progress, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                val angle = Math.toRadians(135.0 + 270.0 * progress)
                val r = arcSize.width / 2
                val knob = Offset(center.x + (r * cos(angle)).toFloat(), center.y + (r * sin(angle)).toFloat())
                drawCircle(Color.White, stroke * 0.62f, knob)
                drawCircle(c.glow, stroke * 0.95f, knob, style = Stroke(stroke * 0.18f))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (score == null) "…" else (progress * 100).toInt().toString(),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = (size.value * 0.24f).sp, fontWeight = FontWeight.Bold),
            )
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        }
    }
}

/** Compact score ring for cards: a full circle filled to [score] with the verdict gradient, number inside. */
@Composable
fun ScoreRing(score: Int?, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val c = LocalLianColors.current
    val progress by animateFloatAsState((score ?: 0) / 100f, tween(1200, easing = FastOutSlowInEasing), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.12f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (progress > 0.005f) {
                val color = when {
                    progress >= 0.75f -> c.safe
                    progress >= 0.5f -> c.caution
                    else -> c.danger
                }
                drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(if (score == null) "…" else (progress * 100).toInt().toString(), style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.3f).sp))
    }
}

/**
 * Donut chart of counts (e.g. apps that look fine / worth a look / risky): segments sweep in, with
 * small gaps and round ends. [center] is drawn in the middle.
 */
@Composable
fun DonutRing(parts: List<Pair<Int, Color>>, modifier: Modifier = Modifier, size: Dp = 128.dp, center: @Composable () -> Unit = {}) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.13f
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            val total = parts.sumOf { it.first }.toFloat()
            if (total <= 0f) return@Canvas
            val shown = parts.filter { it.first > 0 }
            // Gap wide enough that the round ends don't touch; tiny segments still show as a dot.
            val capDeg = Math.toDegrees((stroke / 2 / (arc.width / 2)).toDouble()).toFloat()
            val gap = if (shown.size > 1) 2 * capDeg + 4f else 0f
            var start = -90f
            shown.forEach { (n, color) ->
                val sweep = (360f * n / total - gap).coerceAtLeast(0.5f)
                drawArc(color, start + gap / 2, sweep * reveal.value, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                start += 360f * n / total
            }
        }
        center()
    }
}

/** A compact stat: coloured tile, big value, label. */
@Composable
fun StatTile(icon: Int, accent: Accent, value: String, label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    GlassCard(modifier, onClick = onClick, padding = PaddingValues(14.dp)) {
        IconTile(icon, accent, 38.dp)
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, minLines = 2)
    }
}

/** 24 hourly bars (minutes per hour), drawn with the accent gradient. */
@Composable
fun HourBars(minutes: List<Int>, accent: Accent, modifier: Modifier = Modifier) {
    val (top, bottom) = LocalLianColors.current.accent(accent)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        val n = minutes.size.coerceAtLeast(1)
        val gap = size.width / n * 0.28f
        val w = size.width / n - gap
        minutes.forEachIndexed { i, m ->
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(w / 2))
            val h = size.height * (m.coerceIn(0, 60) / 60f)
            if (h > 0f) {
                drawRoundRect(Brush.verticalGradient(listOf(top, bottom)), Offset(x, size.height - h), Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2))
            }
        }
    }
}

/** Horizontal stacked bar of counts (e.g. low / medium / high / critical apps). */
@Composable
fun StackBar(parts: List<Pair<Int, Color>>, modifier: Modifier = Modifier) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        parts.filter { it.first > 0 }.forEach { (n, color) ->
            Box(Modifier.weight(n.toFloat() / total).fillMaxSize().background(color))
        }
        val used = parts.sumOf { it.first }
        if (used < total) Spacer(Modifier.weight((total - used).toFloat() / total))
    }
}

/** Fades and lifts content in once, staggered by [index], when it first appears. */
fun Modifier.appear(index: Int = 0): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(450, delayMillis = (index * 60).coerceAtMost(420), easing = FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 24.dp.toPx()
    }
}

/** Primary call to action filled with the brand gradient. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = null, enabled: Boolean = true) {
    val c = LocalLianColors.current
    Row(
        modifier
            .clip(CircleShape)
            .background(if (enabled) c.brandBrush else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.outline)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(painterResource(it), null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
