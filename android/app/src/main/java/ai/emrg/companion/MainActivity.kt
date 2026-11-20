package ai.emrg.companion

import ai.emrg.companion.ui.navigation.CompanionNavHost
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * App shell. The single screen is gone: [CompanionNavHost] owns the 20-route
 * navigation spine, and one shared [CompanionConnection] carries the live
 * paired-host socket to every screen. The pair flow stays the entry point
 * while unpaired; a paired device lands on Hosts.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val context = LocalContext.current
                    val connection = remember(context) { CompanionConnection(context.applicationContext) }
                    CompanionNavHost(connection = connection)
                }
            }
        }
    }
}
