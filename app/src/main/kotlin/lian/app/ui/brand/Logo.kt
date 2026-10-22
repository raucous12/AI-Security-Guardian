package lian.app.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import lian.app.ui.theme.LocalLianColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The LIAN monogram: "L" and "I" share one stroke. The upright is both the I and the L's stem, the
 * foot runs right along the baseline, and the I's dot sits above the stem. Coordinates are in a
 * [-1, 1] box with +Y up; the launcher vectors use the same shape (grid = 54 ± 30 × unit).
 */
internal object Monogram {
    /** Centre line of the L, drawn as one stroke: stem top → corner → foot end. */
    val STEM_TOP = Offset(-0.29f, 0.085f)
    val CORNER = Offset(-0.29f, -0.455f)
    val FOOT_END = Offset(0.29f, -0.455f)
    const val STROKE = 0.24f
    val DOT = Offset(-0.29f, 0.455f)
    const val DOT_R = 0.13f
}

/** Logo colours for the current mode: the brand tile, and four playful dots (violet, blue, coral, mint). */
private data class LogoPalette(val tile: List<Color>, val glyph: Color, val dot: Color, val dots: List<Color>) {
    companion object {
        val Light = LogoPalette(
            tile = listOf(Color(0xFF8C7CFF), Color(0xFF5B4BDB), Color(0xFF3F7BF2)), glyph = Color.White, dot = Color(0xFF2FD3A7),
            dots = listOf(Color(0xFF7C6CF6), Color(0xFF3F7BF2), Color(0xFFEF6B57), Color(0xFF2FD3A7)),
        )
        val Dark = LogoPalette(
            tile = listOf(Color(0xFF9C8CFF), Color(0xFF6A5AE8), Color(0xFF3F86F0)), glyph = Color.White, dot = Color(0xFF6EF0CF),
            dots = listOf(Color(0xFFA899FF), Color(0xFF6FA8FF), Color(0xFFFF8A7A), Color(0xFF6EF0CF)),
        )
    }
}

/** The squircle tile (|x|⁴ + |y|⁴ = 1); [roundness] 1 = circle, 0.5 = squircle. */
private fun squircle(center: Offset, size: Float, roundness: Float = 0.5f) = Path().apply {
    val n = 64
    for (i in 0 until n) {
        val a = i * 2.0 * PI / n
        val x = (sign(cos(a)) * abs(cos(a)).pow(roundness.toDouble())).toFloat()
        val y = (sign(sin(a)) * abs(sin(a)).pow(roundness.toDouble())).toFloat()
        val p = Offset(center.x + size / 2 * x, center.y + size / 2 * y)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun lPath(map: (Offset) -> Offset) = Path().apply {
    map(Monogram.STEM_TOP).let { moveTo(it.x, it.y) }
    map(Monogram.CORNER).let { lineTo(it.x, it.y) }
    map(Monogram.FOOT_END).let { lineTo(it.x, it.y) }
}

/** Draws the L, trimmed to [progress] of its length (0..1), as one round-capped, square-cornered stroke. */
private fun DrawScope.drawL(map: (Offset) -> Offset, unit: Float, color: Color, progress: Float = 1f) {
    if (progress <= 0f) return
    val full = lPath(map)
    val path = if (progress >= 1f) full else Path().also { dst ->
        val m = PathMeasure().apply { setPath(full, false) }
        m.getSegment(0f, m.length * progress, dst, true)
    }
    drawPath(path, color, style = Stroke(width = Monogram.STROKE * unit, cap = StrokeCap.Round, join = StrokeJoin.Miter))
}

/** A ball with squash and stretch: [squash] > 0 flattens it (impact), < 0 stretches it (falling fast). */
private fun DrawScope.drawBall(center: Offset, radius: Float, color: Color, squash: Float = 0f) {
    if (radius <= 0f) return
    scale(1f + 0.35f * squash, 1f - 0.3f * squash, pivot = Offset(center.x, center.y + radius)) { drawCircle(color, radius, center) }
}

private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
private fun span(t: Float, from: Float, to: Float) = clamp01((t - from) / (to - from))
private fun easeOutCubic(x: Float) = 1f - (1f - x).pow(3)
private fun easeInOutCubic(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1
    return 1 + c3 * (x - 1).pow(3) + c1 * (x - 1).pow(2)
}

/** Bounce easing that also reports how hard the ball is pressing into the ground (0..1), for squash. */
private fun bounce(x: Float): Pair<Float, Float> {
    val n = 7.5625f
    val d = 2.75f
    var t = x
    val (y, k) = when {
        t < 1 / d -> n * t * t to 1f
        t < 2 / d -> { t -= 1.5f / d; (n * t * t + 0.75f) to 0.45f }
        t < 2.5 / d -> { t -= 2.25f / d; (n * t * t + 0.9375f) to 0.2f }
        else -> { t -= 2.625f / d; (n * t * t + 0.984375f) to 0.08f }
    }
    val impact = (1f - (1f - y) / 0.06f).coerceAtLeast(0f) * k
    return y to impact
}

/**
 * The launch animation, in the playful spirit of the Pixel boot dots: four brand-coloured dots bounce,
 * three of them merge into the LIAN tile, the L draws itself, and the mint dot (the I) drops onto the L
 * with a squash-and-bounce and a ripple. Flat, no 3D. Plays once; [startAtEnd] shows the final frame.
 * Colours follow light/dark mode. Time is read only while drawing, so it never recomposes.
 */
@Composable
fun LianLogoAnimated(modifier: Modifier = Modifier, startAtEnd: Boolean = false) {
    val p = if (LocalLianColors.current.dark) LogoPalette.Dark else LogoPalette.Light
    val time = remember { Animatable(if (startAtEnd) LOGO_MS else 0f) }
    LaunchedEffect(startAtEnd) {
        if (!startAtEnd) time.animateTo(LOGO_MS, tween(LOGO_MS.toInt(), easing = LinearEasing))
    }
    Canvas(modifier.semantics { contentDescription = "LIAN" }) {
        val t = time.value
        val s = size.minDimension
        val tileSize = s * 0.58f
        val tileCenter = Offset(size.width / 2, size.height * 0.6f)
        val unit = tileSize * 0.42f
        fun map(o: Offset) = Offset(tileCenter.x + o.x * unit, tileCenter.y - o.y * unit)
        val dotRest = map(Monogram.DOT)
        val dotR = Monogram.DOT_R * unit

        // 1. Four dots bounce in a row (0–1000 ms), each a little after the previous one.
        val ballR = s * 0.045f
        val gap = s * 0.14f
        val ground = tileCenter.y + s * 0.04f
        val merge = easeInOutCubic(span(t, 950f, 1350f))
        p.dots.forEachIndexed { i, color ->
            val start = i * 110f
            val local = t - start
            if (local < 0f) return@forEachIndexed
            val appear = easeOutBack(span(local, 0f, 220f))
            val hop = abs(sin(PI.toFloat() * local / 420f)) * (1f - span(t, 800f, 1000f))
            val home = Offset(tileCenter.x + (i - 1.5f) * gap, ground - ballR - hop * s * 0.16f)
            val squash = if (hop < 0.12f && t < 1000f) (0.12f - hop) / 0.12f else 0f
            if (i < 3) {
                // Merge into the tile's centre and vanish as the tile grows.
                val pos = Offset(home.x + (tileCenter.x - home.x) * merge, home.y + (tileCenter.y - home.y) * merge)
                drawBall(pos, ballR * appear * (1f - merge), color, squash)
            }
        }

        // 2. The tile grows from a circle into the squircle (950–1450 ms).
        val grow = span(t, 950f, 1450f)
        if (grow > 0f) {
            val sizeNow = tileSize * easeOutBack(grow)
            val roundness = 1f - 0.5f * easeOutCubic(grow)
            drawPath(squircle(tileCenter, sizeNow, roundness), Brush.linearGradient(p.tile, Offset(tileCenter.x - sizeNow / 2, tileCenter.y - sizeNow / 2), Offset(tileCenter.x + sizeNow / 2, tileCenter.y + sizeNow / 2)))
        }

        // 3. The L draws itself (1350–1800 ms).
        drawL(::map, unit, p.glyph, easeInOutCubic(span(t, 1350f, 1800f)))

        // 4. The mint dot: hops with the others, leaps up during the merge, then drops onto the L (1800–2500 ms).
        val mintStart = 3 * 110f
        if (t >= mintStart) {
            val local = t - mintStart
            val appear = easeOutBack(span(local, 0f, 220f))
            val hop = abs(sin(PI.toFloat() * local / 420f)) * (1f - span(t, 800f, 1000f))
            val home = Offset(tileCenter.x + 1.5f * gap, ground - ballR - hop * s * 0.16f)
            val high = Offset(dotRest.x, size.height * 0.07f + dotR)
            val radius = ballR + (dotR - ballR) * span(t, 950f, 1500f)
            when {
                t < 950f -> drawBall(home, ballR * appear, p.dots[3], if (hop < 0.12f) (0.12f - hop) / 0.12f else 0f)
                t < 1800f -> {
                    val leap = easeOutCubic(span(t, 950f, 1400f))
                    val pos = Offset(home.x + (high.x - home.x) * leap, home.y + (high.y - home.y) * leap - sin(PI.toFloat() * leap) * s * 0.05f)
                    drawBall(pos, radius, p.dot, -0.25f * sin(PI.toFloat() * leap))
                }
                else -> {
                    val (y, impact) = bounce(span(t, 1800f, 2500f))
                    val pos = Offset(dotRest.x, high.y + (dotRest.y - high.y) * y)
                    // Ripple from the first impact.
                    val ripple = span(t, 1800f + 0.364f * 700f, 2250f + 0.364f * 700f)
                    if (ripple in 0.001f..0.999f) {
                        drawCircle(p.dot.copy(alpha = 0.45f * (1f - ripple)), dotR * (1.2f + 2.6f * ripple), dotRest, style = Stroke(width = dotR * 0.35f * (1f - ripple)))
                    }
                    drawBall(pos, dotR, p.dot, if (y < 1f && impact == 0f) -0.15f else impact)
                }
            }
        }
    }
}

/** Length of [LianLogoAnimated] in milliseconds. */
const val LOGO_MS = 2600f

/**
 * Flat LIAN mark for headers: brand squircle, white L, mint dot. When [animated], the dot drops onto
 * the L with a little bounce the first time it appears. Colours follow light/dark mode.
 */
@Composable
fun LianMark(modifier: Modifier = Modifier, animated: Boolean = true) {
    val p = if (LocalLianColors.current.dark) LogoPalette.Dark else LogoPalette.Light
    val drop = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(Unit) { if (animated) drop.animateTo(1f, tween(750, delayMillis = 250, easing = LinearEasing)) }
    Canvas(modifier.semantics { contentDescription = "LIAN" }) {
        val s = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        drawPath(squircle(center, s), Brush.linearGradient(p.tile, Offset.Zero, Offset(size.width, size.height)))
        val unit = s * 0.42f
        fun map(o: Offset) = Offset(center.x + o.x * unit, center.y - o.y * unit)
        drawL(::map, unit, p.glyph)
        val rest = map(Monogram.DOT)
        val (y, impact) = bounce(drop.value)
        val from = rest.y - s * 0.32f
        drawBall(Offset(rest.x, from + (rest.y - from) * y), Monogram.DOT_R * unit, p.dot, impact)
    }
}

/** "LIAN" in the brand gradient; a lighter version in dark mode so it reads as light on the night background. */
@Composable
fun LianWordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 28.sp, letterSpacing: TextUnit = 6.sp) {
    val c = LocalLianColors.current
    val brush = if (c.dark) Brush.linearGradient(listOf(Color(0xFFD2C9FF), Color(0xFFA899FF), Color(0xFF86C6FF))) else c.brandBrush
    Text(
        "LIAN",
        modifier = modifier,
        style = MaterialTheme.typography.headlineLarge.merge(
            TextStyle(brush = brush, fontSize = fontSize, fontWeight = FontWeight.Black, letterSpacing = letterSpacing),
        ),
    )
}
