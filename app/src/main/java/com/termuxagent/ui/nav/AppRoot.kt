package com.termuxagent.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.termuxagent.data.settings.AppSettings
import com.termuxagent.data.settings.SettingsStore
import com.termuxagent.ui.AppContext
import com.termuxagent.ui.chat.ChatScreen
import com.termuxagent.ui.settings.SettingsScreen
import com.termuxagent.ui.terminal.TerminalScreen
import com.termuxagent.ui.theme.TermuXagentTheme
import com.termuxagent.ui.workspace.WorkspaceScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember

object Routes {
    const val CHAT = "chat"
    const val TERMINAL = "terminal"
    const val WORKSPACE = "workspace"
    const val SETTINGS = "settings"
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val NAV_ITEMS = listOf(
    NavItem(Routes.CHAT, "Chat", Icons.Rounded.Chat),
    NavItem(Routes.TERMINAL, "Terminal", Icons.Rounded.Terminal),
    NavItem(Routes.WORKSPACE, "Files", Icons.Rounded.FolderOpen),
    NavItem(Routes.SETTINGS, "Settings", Icons.Rounded.Settings)
)

@Composable
fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    AppContext.bind(ctx)

    val settingsFlow = remember { SettingsStore.flow(ctx) }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())

    TermuXagentTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor
    ) {
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: Routes.CHAT
        val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime
            .getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            NavHost(
                navController = nav,
                startDestination = Routes.CHAT,
                modifier = Modifier.weight(1f)
            ) {
                composable(Routes.CHAT) {
                    ChatScreen(
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        onOpenWorkspace = { nav.navigate(Routes.WORKSPACE) }
                    )
                }
                composable(Routes.TERMINAL) {
                    TerminalScreen(onBack = { nav.popBackStack() })
                }
                composable(Routes.WORKSPACE) {
                    WorkspaceScreen(onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(onBack = { nav.popBackStack() })
                }
            }

            // Hide NavigationBar when keyboard is visible — prevents the gap
            // between composer and keyboard
            if (!imeVisible) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    tonalElevation = 0.dp
                ) {
                NAV_ITEMS.forEach { item ->
                    val selected = currentRoute == item.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = if (selected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        label = {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
                } // end NavigationBar
            } // end if (!imeVisible)
        }
    }
}

// Required import — androidx.compose.ui.unit.dp for tonalElevation

