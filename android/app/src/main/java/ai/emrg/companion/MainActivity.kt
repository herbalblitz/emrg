package ai.emrg.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompanionChatScreen()
                }
            }
        }
    }
}

@Composable
private fun CompanionChatScreen() {
    var chatState by remember { mutableStateOf(ChatUiState()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val transport = remember { DemoTransport() }

    fun dispatch(action: ChatAction) {
        val transition = reduceChatState(chatState, action)
        chatState = transition.state
        transition.promptToSend?.let { prompt ->
            scope.launch {
                try {
                    val response = sendSafely(transport, prompt)
                    chatState = reduceChatState(chatState, ChatAction.ResponseReceived(response)).state
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    chatState = reduceChatState(chatState, ChatAction.RequestFailed).state
                } finally {
                    if (chatState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(chatState.messages.lastIndex)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("EMRG", style = MaterialTheme.typography.headlineMedium)
                Text("Android companion", style = MaterialTheme.typography.titleSmall)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text("DEMO · Offline", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Mock mode only. No host is connected; pairing and remote execution are intentionally disabled.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chatState.messages) { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.role == "You")
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message.role, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = chatState.draft,
                onValueChange = { dispatch(ChatAction.DraftChanged(it)) },
                modifier = Modifier.weight(1f),
                label = { Text("Message EMRG") },
                enabled = !chatState.isSending,
                maxLines = 4
            )
            Button(
                enabled = chatState.draft.isNotBlank() && !chatState.isSending,
                onClick = { dispatch(ChatAction.Submit) }
            ) { Text(if (chatState.isSending) "…" else "Send") }
        }
    }
}
