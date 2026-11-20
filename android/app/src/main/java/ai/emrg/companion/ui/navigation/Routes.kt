package ai.emrg.companion.ui.navigation

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.ui.screens.AboutScreen
import ai.emrg.companion.ui.screens.ChatScreen
import ai.emrg.companion.ui.screens.EditorScreen
import ai.emrg.companion.ui.screens.FilesScreen
import ai.emrg.companion.ui.screens.HostsScreen
import ai.emrg.companion.ui.screens.NotYetAvailableScreen
import ai.emrg.companion.ui.screens.PairScreen
import ai.emrg.companion.ui.screens.SandboxScreen
import ai.emrg.companion.ui.screens.SessionsScreen
import ai.emrg.companion.ui.screens.TerminalScreen
import ai.emrg.companion.ui.screens.ToolMonitorScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** Route names are verbatim from the reference app (cross-platform MainActivity). */
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

    /** Route pattern with optional args, for navigation-compose. */
    fun chat(sessionId: String? = null): String =
        if (sessionId.isNullOrBlank()) Chat else "$Chat?$ArgSessionId=${android.net.Uri.encode(sessionId)}"

    fun editor(path: String, sessionId: String? = null): String =
        "$Editor?$ArgPath=${android.net.Uri.encode(path)}" +
            if (sessionId.isNullOrBlank()) "" else "&$ArgSessionId=${android.net.Uri.encode(sessionId)}"

    fun files(path: String? = null): String =
        if (path.isNullOrBlank()) Files else "$Files?$ArgPath=${android.net.Uri.encode(path)}"
}

/** Bottom-bar destinations. Everything else is reached from the Hosts hub. */
private val bottomDestinations = listOf(
    Route.Hosts to "Hosts",
    Route.Sessions to "Sessions",
    Route.Chat to "Chat",
    Route.Files to "Files",
    Route.Terminal to "Terminal",
)

@Composable
internal fun CompanionNavHost(
    connection: CompanionConnection,
    navController: NavHostController = rememberNavController(),
) {
    val pairedHost by connection.pairedHost.collectAsState()
    val startDestination = if (pairedHost != null) Route.Hosts else Route.Pair
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route?.substringBefore("?")

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { (route, label) ->
                    NavigationBarItem(
                        selected = currentRoute == route,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(label.take(1)) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.Hosts) {
                HostsScreen(connection = connection, navController = navController)
            }
            composable(Route.Pair) {
                PairScreen(connection = connection, navController = navController)
            }
            composable(Route.Sessions) {
                SessionsScreen(connection = connection, navController = navController)
            }
            composable(
                route = "${Route.Chat}?${Route.ArgSessionId}={${Route.ArgSessionId}}",
                arguments = listOf(
                    navArgument(Route.ArgSessionId) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                ChatScreen(
                    connection = connection,
                    navController = navController,
                    sessionId = entry.arguments?.getString(Route.ArgSessionId).orEmpty(),
                )
            }
            composable(Route.ToolMonitor) {
                ToolMonitorScreen(connection = connection, navController = navController)
            }
            composable(
                route = "${Route.Files}?${Route.ArgPath}={${Route.ArgPath}}",
                arguments = listOf(
                    navArgument(Route.ArgPath) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                FilesScreen(
                    connection = connection,
                    navController = navController,
                    path = entry.arguments?.getString(Route.ArgPath).orEmpty(),
                )
            }
            composable(
                route = "${Route.Editor}?${Route.ArgPath}={${Route.ArgPath}}&${Route.ArgSessionId}={${Route.ArgSessionId}}",
                arguments = listOf(
                    navArgument(Route.ArgPath) { type = NavType.StringType; defaultValue = "" },
                    navArgument(Route.ArgSessionId) { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                EditorScreen(
                    connection = connection,
                    navController = navController,
                    path = entry.arguments?.getString(Route.ArgPath).orEmpty(),
                    sessionId = entry.arguments?.getString(Route.ArgSessionId).orEmpty(),
                )
            }
            composable(Route.Terminal) {
                TerminalScreen(connection = connection, navController = navController)
            }
            composable(
                route = "${Route.Sandbox}?${Route.ArgSessionId}={${Route.ArgSessionId}}",
                arguments = listOf(
                    navArgument(Route.ArgSessionId) { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                SandboxScreen(
                    connection = connection,
                    navController = navController,
                    sessionId = entry.arguments?.getString(Route.ArgSessionId).orEmpty(),
                )
            }
            // Batch B/C routes exist in the spine now; their screens land in later
            // batches. Until then each shows which scope it needs and that it is
            // not built yet — never a dead navigation target.
            composable(Route.Memory) { NotYetAvailableScreen("Memory", ai.emrg.companion.PairingScope.READ_MEMORY, connection, navController) }
            composable(Route.Models) { NotYetAvailableScreen("Models", ai.emrg.companion.PairingScope.MANAGE_MODELS, connection, navController) }
            composable(Route.Rant) { NotYetAvailableScreen("Rant", ai.emrg.companion.PairingScope.SUBMIT_RANT, connection, navController) }
            composable(Route.Evolution) { NotYetAvailableScreen("Evolution", ai.emrg.companion.PairingScope.READ_EVOLUTION, connection, navController) }
            composable(Route.Tasks) { NotYetAvailableScreen("Tasks", ai.emrg.companion.PairingScope.MANAGE_TASKS, connection, navController) }
            composable(Route.Skills) { NotYetAvailableScreen("Skills", ai.emrg.companion.PairingScope.MANAGE_SKILLS, connection, navController) }
            composable(Route.Logs) { NotYetAvailableScreen("Logs", ai.emrg.companion.PairingScope.READ_LOGS, connection, navController) }
            composable(Route.Audit) { NotYetAvailableScreen("Audit", ai.emrg.companion.PairingScope.READ_AUDIT, connection, navController) }
            composable(Route.Devices) { NotYetAvailableScreen("Devices", ai.emrg.companion.PairingScope.MANAGE_DEVICES, connection, navController) }
            composable(Route.Settings) { NotYetAvailableScreen("Settings", ai.emrg.companion.PairingScope.MANAGE_SETTINGS, connection, navController) }
            composable(Route.About) { AboutScreen(navController = navController) }
        }
    }
}
