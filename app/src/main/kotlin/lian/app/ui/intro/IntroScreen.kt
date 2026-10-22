package lian.app.ui.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lian.app.R
import lian.app.ui.brand.LOGO_MS
import lian.app.ui.brand.LianLogoAnimated
import lian.app.ui.brand.LianWordmark
import lian.app.ui.design.LianBackground

/**
 * Launch sequence: the playful dot animation builds the LIAN mark (the I's dot drops onto the L), the
 * wordmark rises in, the promise fades in, then the app cross-fades in. Tapping skips. With "remove
 * animations" on, the final frame is shown briefly instead; other animation speeds stretch the timing to match.
 * Colours follow light/dark mode.
 */
@Composable
fun IntroScreen(durationScale: Float, onFinished: () -> Unit) {
    val reducedMotion = durationScale == 0f
    val finish by rememberUpdatedState(onFinished)
    val word = remember { Animatable(0f) }
    val tagline = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (reducedMotion) {
            word.snapTo(1f); tagline.snapTo(1f)
            delay(650)
            finish()
            return@LaunchedEffect
        }
        launch { delay((2050 * durationScale).toLong()); word.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        launch { delay((2300 * durationScale).toLong()); tagline.animateTo(1f, tween(400)) }
        delay(((LOGO_MS + 500) * durationScale).toLong())
        finish()
    }

    val skip = stringResource(R.string.intro_skip_cd)
    LianBackground(
        Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }
            .semantics { contentDescription = skip },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val logoSize = min(maxWidth, maxHeight).times(0.62f).coerceIn(200.dp, 360.dp)
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LianLogoAnimated(Modifier.size(logoSize), startAtEnd = reducedMotion)
                LianWordmark(
                    Modifier.graphicsLayer {
                        alpha = word.value
                        translationY = (1f - word.value) * 20.dp.toPx()
                    },
                    fontSize = 44.sp,
                    letterSpacing = 10.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.intro_tagline),
                    modifier = Modifier.graphicsLayer { alpha = tagline.value * 0.85f },
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.intro_sub),
                    modifier = Modifier.graphicsLayer { alpha = tagline.value * 0.7f },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
