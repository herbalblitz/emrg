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
import androidx.compose.foundation.lazy.LazyRow
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
import java.util.UUID

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
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionStore = remember(context) { LocalSessionStore(context.applicationContext) }
    var sessionsState by remember { mutableStateOf(sessionStore.load()) }
    var chatState by remember { mutableStateOf(sessionsState.active.chat) }
    var renameDraft by remember { mutableStateOf(sessionsState.active.title) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val transport = remember { DemoTransport() }

    fun persistChat(state: ChatUiState) {
        chatState = state
        sessionsState = updateActiveLocalChat(sessionsState, state)
        sessionStore.save(sessionsState)
    }

    fun dispatch(action: ChatAction) {
        val transition = reduceChatState(chatState, action)
        persistChat(transition.state)
        transition.promptToSend?.let { prompt ->
            scope.launch {
                try {
                    val response = sendSafely(transport, prompt)
                    persistChat(reduceChatState(chatState, ChatAction.ResponseReceived(response)).state)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    persistChat(reduceChatState(chatState, ChatAction.RequestFailed).state)
                } finally {
                    if (chatState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(chatState.messages.lastIndex)
                    }
                }
            }
        }
    }

    fun selectSession(id: String) {
        if (chatState.isSending) return
        sessionsState = selectLocalSession(sessionsState, id)
        chatState = sessionsState.active.chat
        renameDraft = sessionsState.active.title
        sessionStore.save(sessionsState)
    }

    fun createSession() {
        if (chatState.isSending || sessionsState.sessions.size >= MAX_LOCAL_SESSIONS) return
        sessionsState = createLocalSession(sessionsState, UUID.randomUUID().toString())
        chatState = sessionsState.active.chat
        renameDraft = sessionsState.active.title
        sessionStore.save(sessionsState)
    }

    fun renameCurrent() {
        sessionsState = renameLocalSession(sessionsState, sessionsState.activeId, renameDraft)
        renameDraft = sessionsState.active.title
        sessionStore.save(sessionsState)
    }

    fun deleteCurrent() {
        if (chatState.isSending) return
        sessionsState = deleteLocalSession(sessionsState, sessionsState.activeId, UUID.randomUUID().toString())
        chatState = sessionsState.active.chat
        renameDraft = sessionsState.active.title
        sessionStore.save(sessionsState)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                "Offline preview. Sessions are stored on this device; no host is connected or synchronized.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sessions", style = MaterialTheme.typography.titleMedium)
            Button(enabled = !chatState.isSending && sessionsState.sessions.size < MAX_LOCAL_SESSIONS, onClick = { createSession() }) {
                Text("New")
            }
            Button(enabled = !chatState.isSending, onClick = { deleteCurrent() }) {
                Text("Delete")
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessionsState.sessions, key = { it.id }) { session ->
                Button(
                    enabled = !chatState.isSending,
                    onClick = { selectSession(session.id) }
                ) {
                    Text(if (session.id == sessionsState.activeId) "• " + session.title else session.title)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = renameDraft,
                onValueChange = { renameDraft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Session name") },
                enabled = !chatState.isSending,
                singleLine = true
            )
            Button(enabled = !chatState.isSending && renameDraft.isNotBlank(), onClick = { renameCurrent() }) {
                Text("Rename")
            }
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
