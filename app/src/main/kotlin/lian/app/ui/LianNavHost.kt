package lian.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import lian.app.AppGraph
import lian.app.Inbound
import lian.app.R
import lian.app.ui.apps.AppsRoute
import lian.app.ui.design.LianBackground
import lian.app.ui.health.HealthRoute
import lian.app.ui.home.HomeRoute
import lian.app.ui.privacy.PrivacyProofRoute
import lian.app.ui.settings.SettingsRoute
import lian.app.ui.shield.ShieldRoute
import lian.app.ui.theme.LocalLianColors

/**
 * Four destinations, each a distinct job: Home (a short overview that opens everything else), Scam
 * Shield (messages and links), Apps (what's installed and what it does) and Settings (incl. privacy
 * proof). Phone health and privacy proof open on top of them; app details open as a sheet in Apps.
 */
enum class Tab(val route: String, val label: Int, val icon: Int) {
    HOME("home", R.string.tab_home, R.drawable.ic_home),
    SHIELD("shield", R.string.tab_shield, R.drawable.ic_scam_shield),
    APPS("apps", R.string.tab_apps, R.drawable.ic_apps),
    SETTINGS("settings", R.string.tab_settings, R.drawable.ic_settings),
}

private object Routes {
    const val PRIVACY = "privacy"
    const val HEALTH = "health"
}

fun NavHostController.openTab(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun LianNavHost(graph: AppGraph) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val pending by graph.inbound.collectAsStateWithLifecycle()

    // Content or shortcuts from outside go straight to the tab that handles them; that tab consumes it.
    LaunchedEffect(pending) {
        val tab = when (val p = pending) {
            is Inbound.Text -> Tab.SHIELD
            is Inbound.Apk -> Tab.APPS
            is Inbound.Open -> when (p.target) {
                Inbound.Shortcut.CHECK_MESSAGE, Inbound.Shortcut.SHIELD_ALERT -> Tab.SHIELD
                Inbound.Shortcut.CHECK_APK, Inbound.Shortcut.APP_ALERT -> Tab.APPS
            }
            else -> null
        }
        if (tab != null && route != tab.route) nav.openTab(tab)
    }

    LianBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (Tab.entries.any { it.route == route }) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f), tonalElevation = 0.dp) {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { nav.openTab(tab) },
                                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                                label = { Text(stringResource(tab.label), maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Tab.HOME.route,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                composable(Tab.HOME.route) {
                    HomeRoute(graph, openTab = nav::openTab, openHealth = { nav.navigate(Routes.HEALTH) }, openPrivacy = { nav.navigate(Routes.PRIVACY) })
                }
                composable(Tab.SHIELD.route) { ShieldRoute(graph) }
                composable(Tab.APPS.route) { AppsRoute(graph) }
                composable(Tab.SETTINGS.route) { SettingsRoute(graph, openPrivacy = { nav.navigate(Routes.PRIVACY) }) }
                composable(Routes.HEALTH) { HealthRoute(graph, onBack = { nav.popBackStack() }) }
                composable(Routes.PRIVACY) { PrivacyProofRoute(graph, onBack = { nav.popBackStack() }) }
            }
        }
        // Content scrolls under a soft scrim, so it never collides with the status bar icons.
        val c = LocalLianColors.current
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Brush.verticalGradient(listOf(c.backgroundTop, c.backgroundTop.copy(alpha = 0.92f)))))
    }
}
