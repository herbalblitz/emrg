package com.emrg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navArgument
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

/**
 * Trạng thái điều hướng đơn giản, dùng thay cho route arguments của Navigation Compose.
 * Mọi màn hình cùng nhận đối tượng này, đọc/ghi `route` để chuyển màn hình và lưu
 * tham số tạm (session_id, cwd, path…) trước khi chuyển.
 */
class AppNavigation(private val navigateTo: (String) -> Unit) {
    var route by mutableStateOf<String>(Route.Hosts)
    var chatSessionId by mutableStateOf("")
    var chatCwd by mutableStateOf("")
    var filePath by mutableStateOf("/")
    var editorPath by mutableStateOf("")
    var editorSessionId by mutableStateOf("")
    var sandboxSessionId by mutableStateOf("")
    var sandboxCwd by mutableStateOf("")

    fun goTo(route: String) {
        this.route = route
        navigateTo(route)
    }
}

@Composable
fun EmrgNavigator(repository: com.emrg.app.data.GatewayRepository) {
    val navController = rememberNavController()
    val nav = remember { AppNavigation { navController.navigate(it) } }

    Scaffold {
        NavHost(navController = navController, startDestination = Route.Hosts) {
            composable(route = Route.Hosts) {
                com.emrg.app.ui.screens.HostsScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Pair) {
                com.emrg.app.ui.screens.PairScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Sessions) {
                com.emrg.app.ui.screens.SessionsScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Chat) {
                com.emrg.app.ui.screens.ChatScreen(
                    repository = repository,
                    nav = nav,
                    sessionId = nav.chatSessionId,
                    cwd = nav.chatCwd,
                )
            }
            composable(route = Route.ToolMonitor) {
                com.emrg.app.ui.screens.ToolMonitorScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Files) {
                com.emrg.app.ui.screens.FilesScreen(
                    repository = repository,
                    nav = nav,
                    path = nav.filePath,
                )
            }
            composable(route = Route.Editor) {
                com.emrg.app.ui.screens.EditorScreen(
                    repository = repository,
                    nav = nav,
                    path = nav.editorPath,
                    sessionId = nav.editorSessionId,
                )
            }
            composable(route = Route.Terminal) {
                com.emrg.app.ui.screens.TerminalScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Sandbox) {
                com.emrg.app.ui.screens.SandboxScreen(
                    repository = repository,
                    nav = nav,
                    sessionId = nav.sandboxSessionId,
                    cwd = nav.sandboxCwd,
                )
            }
            composable(route = Route.Memory) {
                com.emrg.app.ui.screens.MemoryScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Models) {
                com.emrg.app.ui.screens.ModelsScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Rant) {
                com.emrg.app.ui.screens.RantScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Evolution) {
                com.emrg.app.ui.screens.EvolutionScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Tasks) {
                com.emrg.app.ui.screens.TasksScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Skills) {
                com.emrg.app.ui.screens.SkillsScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Logs) {
                com.emrg.app.ui.screens.LogsScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Audit) {
                com.emrg.app.ui.screens.AuditScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Devices) {
                com.emrg.app.ui.screens.DevicesScreen(repository = repository, nav = nav)
            }
            composable(route = Route.Settings) {
                com.emrg.app.ui.screens.SettingsScreen(repository = repository, nav = nav)
            }
            composable(route = Route.About) {
                com.emrg.app.ui.screens.AboutScreen(nav = nav)
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
}
