package com.geoenv.browser

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.NavType
import com.geoenv.browser.R
import com.geoenv.browser.ui.pages.AccountPage
import com.geoenv.browser.ui.pages.AuditPage
import com.geoenv.browser.ui.pages.EnvDetailPage
import com.geoenv.browser.ui.pages.EnvListPage
import com.geoenv.browser.ui.pages.GroupsPage
import com.geoenv.browser.ui.pages.HarPage
import com.geoenv.browser.ui.pages.HostsPage
import com.geoenv.browser.ui.pages.MarionettePage
import com.geoenv.browser.ui.pages.PopupPanelPage
import com.geoenv.browser.ui.pages.ProxyPage
import com.geoenv.browser.ui.pages.ReportPage
import com.geoenv.browser.ui.pages.SettingsPage
import com.geoenv.browser.ui.pages.SnapshotPage
import com.geoenv.browser.ui.viewmodel.PopupViewModel

/** 路由表（全部为本机、无外部深链，导出面最小） */
object Routes {
    const val ENV_LIST = "envs"
    const val ACCOUNTS = "accounts"
    const val PROXIES = "proxies"
    const val SETTINGS = "settings"

    const val ENV_DETAIL = "env/{envId}"
    fun envDetail(id: Long) = "env/$id"

    const val FINGERPRINT = "env/{envId}/fingerprint"
    fun fingerprint(id: Long) = "env/$id/fingerprint"

    const val SNAPSHOTS = "env/{envId}/snapshots"
    fun snapshots(id: Long) = "env/$id/snapshots"

    const val HOSTS = "env/{envId}/hosts"
    fun hosts(id: Long) = "env/$id/hosts"

    const val REPORT = "env/{envId}/report"
    fun report(id: Long) = "env/$id/report"

    const val MARIONETTE = "marionette"
    const val POPUPS = "popups"
    const val HAR = "har"
    const val GROUPS = "groups"
}

private data class Tab(val route: String, val icon: ImageVector, val labelRes: Int)

private val TABS = listOf(
    Tab(Routes.ENV_LIST, Icons.Filled.List, R.string.nav_environments),
    Tab(Routes.ACCOUNTS, Icons.Filled.AccountBox, R.string.nav_accounts),
    Tab(Routes.PROXIES, Icons.Filled.Wifi, R.string.nav_proxies),
    Tab("audit", Icons.Filled.Shield, R.string.nav_audit),
    Tab(Routes.SETTINGS, Icons.Filled.Settings, R.string.nav_settings),
)

/**
 * 应用导航骨架：底部 5 项 + 二级页面。
 *
 * 深色/密度/主色由 [MainActivity] 的 [GeoEnvTheme] 已统一包裹，本处只负责路由。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 未读角标
    val popupVm: PopupViewModel = hiltViewModel()
    val newPopups by popupVm.newCount.collectAsStateWithLifecycle()

    val showTabs = TABS.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.route == Routes.POPUPS && newPopups > 0) {
                                    BadgedBox(badge = { Badge { Text("$newPopups") } }) {
                                        Icon(tab.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.ENV_LIST,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.ENV_LIST) {
                EnvListPage(
                    onOpenDetail = { id -> navController.navigate(Routes.envDetail(id)) },
                    onOpenGroups = { navController.navigate(Routes.GROUPS) },
                    onOpenPopups = { navController.navigate(Routes.POPUPS) },
                    onOpenHar = { navController.navigate(Routes.HAR) },
                    onOpenMarionette = { navController.navigate(Routes.MARIONETTE) },
                )
            }

            composable(Routes.ACCOUNTS) {
                AccountPage(
                    onOpenDetail = { id -> navController.navigate(Routes.envDetail(id)) },
                )
            }

            composable(Routes.PROXIES) { ProxyPage() }

            composable(Routes.SETTINGS) {
                SettingsPage(
                    onOpenMarionette = { navController.navigate(Routes.MARIONETTE) },
                    onOpenPopups = { navController.navigate(Routes.POPUPS) },
                    onOpenHar = { navController.navigate(Routes.HAR) },
                    onOpenGroups = { navController.navigate(Routes.GROUPS) },
                    onOpenAudit = { navController.navigate("audit") },
                )
            }

            composable(
                route = Routes.ENV_DETAIL,
                arguments = listOf(navArgument("envId") { type = NavType.LongType }),
            ) { entry ->
                EnvDetailPage(
                    envId = entry.arguments?.getLong("envId") ?: 0L,
                    onBack = { navController.popBackStack() },
                    onOpenFingerprint = { navController.navigate(Routes.fingerprint(it)) },
                    onOpenSnapshots = { navController.navigate(Routes.snapshots(it)) },
                    onOpenHosts = { navController.navigate(Routes.hosts(it)) },
                    onOpenReport = { navController.navigate(Routes.report(it)) },
                )
            }

            composable(
                route = Routes.FINGERPRINT,
                arguments = listOf(navArgument("envId") { type = NavType.LongType }),
            ) { entry ->
                com.geoenv.browser.ui.pages.FingerprintPage(
                    envId = entry.arguments?.getLong("envId") ?: 0L,
                    onBack = { navController.popBackStack() },
                    onOpenSnapshots = { navController.navigate(Routes.snapshots(it)) },
                )
            }

            composable(
                route = Routes.SNAPSHOTS,
                arguments = listOf(navArgument("envId") { type = NavType.LongType }),
            ) { entry ->
                SnapshotPage(
                    envId = entry.arguments?.getLong("envId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.HOSTS,
                arguments = listOf(navArgument("envId") { type = NavType.LongType }),
            ) { entry ->
                HostsPage(
                    envId = entry.arguments?.getLong("envId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.REPORT,
                arguments = listOf(navArgument("envId") { type = NavType.LongType }),
            ) { entry ->
                ReportPage(
                    envId = entry.arguments?.getLong("envId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.MARIONETTE) { MarionettePage(onBack = { navController.popBackStack() }) }

            composable(Routes.POPUPS) { PopupPanelPage(onBack = { navController.popBackStack() }) }

            composable(Routes.HAR) { HarPage(onBack = { navController.popBackStack() }) }

            composable(Routes.GROUPS) { GroupsPage(onBack = { navController.popBackStack() }) }

            composable("audit") { AuditPage(onBack = { navController.popBackStack() }) }
        }
    }
}
