package com.emrg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import com.emrg.app.ui.theme.EmrgTheme
import com.emrg.app.ui.theme.SurfaceContent

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EmrgApplication
        setContent {
            EmrgTheme {
                SurfaceContent {
                    EmrgNavigator(repository = app.repository)
                }
            }
        }
    }
}

@Composable
fun EmrgNavigator(repository: com.emrg.app.data.GatewayRepository) {
    val navController = rememberNavController()

    Scaffold {
        NavHost(navController = navController, startDestination = Route.Hosts) {
            composable(route = Route.Hosts) {
                com.emrg.app.ui.screens.HostsScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Pair) {
                com.emrg.app.ui.screens.PairScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Sessions) {
                com.emrg.app.ui.screens.SessionsScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Chat) {
                val entry = navController.currentBackStackEntry
                com.emrg.app.ui.screens.ChatScreen(
                    repository = repository,
                    navController = navController,
                    sessionId = entry?.arguments?.getString(Route.ArgSessionId) ?: "",
                    cwd = entry?.arguments?.getString(Route.ArgCwd) ?: repository.currentWorkspace(),
                )
            }
            composable(route = Route.ToolMonitor) {
                com.emrg.app.ui.screens.ToolMonitorScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Files) {
                val entry = navController.currentBackStackEntry
                com.emrg.app.ui.screens.FilesScreen(
                    repository = repository,
                    navController = navController,
                    path = entry?.arguments?.getString(Route.ArgPath) ?: "/",
                )
            }
            composable(route = Route.Editor) {
                val entry = navController.currentBackStackEntry
                com.emrg.app.ui.screens.EditorScreen(
                    repository = repository,
                    navController = navController,
                    path = entry?.arguments?.getString(Route.ArgPath) ?: "",
                    sessionId = entry?.arguments?.getString(Route.ArgSessionId) ?: "",
                )
            }
            composable(route = Route.Terminal) {
                com.emrg.app.ui.screens.TerminalScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Sandbox) {
                val entry = navController.currentBackStackEntry
                com.emrg.app.ui.screens.SandboxScreen(
                    repository = repository,
                    navController = navController,
                    sessionId = entry?.arguments?.getString(Route.ArgSessionId) ?: "",
                    cwd = entry?.arguments?.getString(Route.ArgCwd) ?: repository.currentWorkspace(),
                )
            }
            composable(route = Route.Memory) {
                com.emrg.app.ui.screens.MemoryScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Models) {
                com.emrg.app.ui.screens.ModelsScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Rant) {
                com.emrg.app.ui.screens.RantScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Evolution) {
                com.emrg.app.ui.screens.EvolutionScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Tasks) {
                com.emrg.app.ui.screens.TasksScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Skills) {
                com.emrg.app.ui.screens.SkillsScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Logs) {
                com.emrg.app.ui.screens.LogsScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Audit) {
                com.emrg.app.ui.screens.AuditScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Devices) {
                com.emrg.app.ui.screens.DevicesScreen(repository = repository, navController = navController)
            }
            composable(route = Route.Settings) {
                com.emrg.app.ui.screens.SettingsScreen(repository = repository, navController = navController)
            }
            composable(route = Route.About) {
                com.emrg.app.ui.screens.AboutScreen(navController = navController)
            }
        }
    }
}

object Route {
    const val Hosts = "hosts"
    const val Pair = "pair"
    const val Sessions = "sessions"
    const val Chat = "chat"
    const val ToolMonitor = "tool-monitor"
    const val Files = "files"
    const val Editor = "editor"
    const val Terminal = "terminal"
    const val Sandbox = "sandbox"
    const val Memory = "memory"
    const val Models = "models"
    const val Rant = "rant"
    const val Evolution = "evolution"
    const val Tasks = "tasks"
    const val Skills = "skills"
    const val Logs = "logs"
    const val Audit = "audit"
    const val Devices = "devices"
    const val Settings = "settings"
    const val About = "about"

    const val ArgSessionId = "session_id"
    const val ArgCwd = "cwd"
    const val ArgPath = "path"
}
