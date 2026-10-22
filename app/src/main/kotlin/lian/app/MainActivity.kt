package lian.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import lian.app.shortcuts.Shortcuts
import lian.app.ui.LianNavHost
import lian.app.ui.intro.IntroScreen
import lian.app.ui.intro.OnboardingScreen
import lian.app.ui.theme.LianTheme
import lian.app.ui.theme.isDark

class MainActivity : ComponentActivity() {
    private enum class Stage { INTRO, ONBOARDING, APP }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph.warmUp()
        protectScreens()
        val coldStart = savedInstanceState == null
        if (coldStart) accept(intent)
        // Content shared in, or a shortcut, goes straight to work: no intro in the way.
        val skipIntro = !coldStart || graph.inbound.value != null
        // The user's animation speed (0 = animations off); the intro's timing follows it.
        val durationScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f).coerceIn(0f, 10f)
        setContent {
            val mode by graph.prefs.themeMode.collectAsStateWithLifecycle()
            val wallpaper by graph.prefs.wallpaperColors.collectAsStateWithLifecycle()
            val onboarded by graph.prefs.onboarded.collectAsStateWithLifecycle()
            var stage by rememberSaveable { mutableStateOf(if (skipIntro) afterIntro(onboarded) else Stage.INTRO) }
            val dark = isDark(mode)
            // System bar icons follow LIAN's theme, not only the system's, so they stay readable either way.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            LianTheme(mode, wallpaper) {
                Crossfade(stage, animationSpec = tween(450), label = "stage") { s ->
                    when (s) {
                        Stage.INTRO -> IntroScreen(durationScale) { stage = afterIntro(graph.prefs.onboarded.value) }
                        Stage.ONBOARDING -> OnboardingScreen(graph) { graph.prefs.setOnboarded(); stage = Stage.APP }
                        Stage.APP -> LianNavHost(graph)
                    }
                }
            }
        }
    }

    private fun afterIntro(onboarded: Boolean) = if (onboarded) Stage.APP else Stage.ONBOARDING

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        accept(intent)
    }

    /**
     * Phase 2, channels 9 and 11: results never show in the recent-apps thumbnail; screenshots and
     * screen sharing are blocked while the setting is on; and other apps' Accessibility services that
     * aren't declared accessibility tools can't read LIAN's screens (so stalkerware can't learn it was found).
     */
    private fun protectScreens() {
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        if (Build.VERSION.SDK_INT >= 34) window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                graph.prefs.hideScreens.collect { hide ->
                    if (hide) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    /** Only validated content is kept; the incoming intent itself is never forwarded or reused. */
    private fun accept(intent: Intent?) {
        val stream = intent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) }
        val text = if (intent?.action == Intent.ACTION_PROCESS_TEXT) intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) else intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)
        val item = intent?.getLongExtra(Inbound.EXTRA_ITEM, 0) ?: 0
        val inbound = Inbound.parse(intent?.action, intent?.type, text, stream?.toString(), packageName, item)
        if (inbound is Inbound.Open) Shortcuts.reportUsed(this, inbound.target)
        if (inbound != Inbound.Ignored) graph.inbound.value = inbound
    }
}
