package lian.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** User's appearance choice; SYSTEM follows the phone's dark-theme setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Pastel-to-saturated pairs for icon tiles and charts (the "colourful tiles" of the design references). */
enum class Accent(val light: Pair<Color, Color>, val dark: Pair<Color, Color>) {
    VIOLET(Color(0xFF9C8CFF) to Color(0xFF5B4BDB), Color(0xFFB9ADFF) to Color(0xFF6D5DF0)),
    SKY(Color(0xFF8FD0FF) to Color(0xFF3B82F6), Color(0xFF9DD7FF) to Color(0xFF4C8DF6)),
    MINT(Color(0xFF7BEFD2) to Color(0xFF0FA88A), Color(0xFF8AF3DA) to Color(0xFF14B892)),
    AMBER(Color(0xFFFFD68A) to Color(0xFFF59E0B), Color(0xFFFFDA94) to Color(0xFFF2A321)),
    CORAL(Color(0xFFFFAD9E) to Color(0xFFEF5B47), Color(0xFFFFB8AA) to Color(0xFFF26B57)),
    PINK(Color(0xFFF9B4D8) to Color(0xFFE84D9B), Color(0xFFFBC0DE) to Color(0xFFEC5CA6)),
}

/** Colours Material's scheme doesn't have: verdict colours, the brand gradient and the page background. */
@Immutable
data class LianColors(
    val dark: Boolean,
    val safe: Color,
    val caution: Color,
    val danger: Color,
    val info: Color,
    val brand: List<Color>,
    val glow: Color,
    val cardBorder: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val blobs: List<Color>,
) {
    val brandBrush: Brush get() = Brush.linearGradient(brand)
    fun accent(a: Accent): Pair<Color, Color> = if (dark) a.dark else a.light
}

val LocalLianColors = staticCompositionLocalOf { LightLian }

private val LightLian = LianColors(
    dark = false,
    safe = Color(0xFF0E9F78),
    caution = Color(0xFFD9870B),
    danger = Color(0xFFE0454B),
    info = Color(0xFF5B5BEA),
    brand = listOf(Color(0xFF7C6CF6), Color(0xFF5B4BDB), Color(0xFF3F7BF2)),
    glow = Color(0x335B4BDB),
    cardBorder = Color(0x1A5B4BDB),
    backgroundTop = Color(0xFFF7F5FF),
    backgroundBottom = Color(0xFFFDF4F7),
    blobs = listOf(Color(0x40B8AEFF), Color(0x307CC4FF), Color(0x26FFB3C7)),
)

/** "Cozy" dark: deep plum-night surfaces, warm accents and soft glows instead of hard black. */
private val DarkLian = LianColors(
    dark = true,
    safe = Color(0xFF5EE6C3),
    caution = Color(0xFFFFC66E),
    danger = Color(0xFFFF8A83),
    info = Color(0xFFA9B4FF),
    // Deep enough for white text on buttons and banners, still luminous against the plum night.
    brand = listOf(Color(0xFF8B7BFF), Color(0xFF6A5AE8), Color(0xFF3F86F0)),
    glow = Color(0x408C7CFF),
    cardBorder = Color(0x26B4A9FF),
    backgroundTop = Color(0xFF15121F),
    backgroundBottom = Color(0xFF100D18),
    blobs = listOf(Color(0x336C5CE7), Color(0x1F2DD4A7), Color(0x1AFF8A65)),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B4BDB), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E4FF), onPrimaryContainer = Color(0xFF1E1460),
    secondary = Color(0xFF0E9F82), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F7EC), onSecondaryContainer = Color(0xFF00382C),
    tertiary = Color(0xFFE2614B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3DC), onTertiaryContainer = Color(0xFF4A1006),
    error = Color(0xFFD93A3A), onError = Color.White,
    errorContainer = Color(0xFFFFE1DF), onErrorContainer = Color(0xFF5C0A0A),
    background = Color(0xFFF6F4FF), onBackground = Color(0xFF1A1630),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1A1630),
    surfaceVariant = Color(0xFFEDEAFB), onSurfaceVariant = Color(0xFF5B5675),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFBFAFF),
    surfaceContainer = Color(0xFFF4F2FD), surfaceContainerHigh = Color(0xFFEDEAFB), surfaceContainerHighest = Color(0xFFE6E2F7),
    outline = Color(0xFFC9C3E6), outlineVariant = Color(0xFFE3DFF5),
    inverseSurface = Color(0xFF2E2944), inverseOnSurface = Color(0xFFF2EFFF), inversePrimary = Color(0xFFB4A9FF),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB4A9FF), onPrimary = Color(0xFF23186B),
    primaryContainer = Color(0xFF3B308F), onPrimaryContainer = Color(0xFFE8E4FF),
    secondary = Color(0xFF63E6C4), onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF0F4D41), onSecondaryContainer = Color(0xFFC9FFF0),
    tertiary = Color(0xFFFFB59E), onTertiary = Color(0xFF5A1A0A),
    tertiaryContainer = Color(0xFF6A2E1F), onTertiaryContainer = Color(0xFFFFE3DC),
    error = Color(0xFFFF8A83), onError = Color(0xFF5C0A0A),
    errorContainer = Color(0xFF5A1A1A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF120F1B), onBackground = Color(0xFFEDE9FF),
    surface = Color(0xFF181524), onSurface = Color(0xFFEDE9FF),
    surfaceVariant = Color(0xFF2A2540), onSurfaceVariant = Color(0xFFBEB7DA),
    surfaceContainerLowest = Color(0xFF0D0B14), surfaceContainerLow = Color(0xFF17141F),
    surfaceContainer = Color(0xFF1E1A2C), surfaceContainerHigh = Color(0xFF262137), surfaceContainerHighest = Color(0xFF2F2943),
    outline = Color(0xFF4D4668), outlineVariant = Color(0xFF332D4A),
    inverseSurface = Color(0xFFEDE9FF), inverseOnSurface = Color(0xFF221E31), inversePrimary = Color(0xFF5B4BDB),
)

private val LianTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, lineHeight = 16.sp),
    )
}

private val LianShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** True when LIAN is currently drawing in dark mode (after applying the user's choice). */
@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * LIAN's brand theme. Wallpaper colours (Material You) are optional; the default keeps the brand
 * palette. Verdicts never rely on colour alone (icon + text).
 */
@Composable
fun LianTheme(mode: ThemeMode = ThemeMode.SYSTEM, wallpaperColors: Boolean = false, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        wallpaperColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalLianColors provides if (dark) DarkLian else LightLian) {
        MaterialTheme(colorScheme = scheme, typography = LianTypography, shapes = LianShapes) {
            // Screens draw on LIAN's transparent glass layout rather than Material surfaces, so text
            // without an explicit colour must still follow the theme (else it stays black in dark mode).
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
