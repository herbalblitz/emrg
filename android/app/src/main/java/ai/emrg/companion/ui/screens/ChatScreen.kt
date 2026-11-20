package ai.emrg.companion.ui.screens

import ai.emrg.companion.ChatAction
import ai.emrg.companion.ChatTransport
import ai.emrg.companion.ChatUiState
import ai.emrg.companion.CompanionChatTransport
import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.ConnectionStatusPanel
import ai.emrg.companion.DemoTransport
import ai.emrg.companion.LocalSessionStore
import ai.emrg.companion.MAX_LOCAL_SESSIONS
import ai.emrg.companion.MarkdownMessage
import ai.emrg.companion.PairingScope
import ai.emrg.companion.RemoteSocketState
import ai.emrg.companion.ToolActivityView
import ai.emrg.companion.createLocalSession
import ai.emrg.companion.deleteLocalSession
import ai.emrg.companion.reduceChatState
import ai.emrg.companion.renameLocalSession
import ai.emrg.companion.selectLocalSession
import ai.emrg.companion.updateActiveLocalChat
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The `chat` route.
 *
 * Live mode: when the device is authenticated and holds chat.send, prompts go
 * through [CompanionChatTransport] over the pinned socket (the host runs the
 * turn in the approved workspace; the host cwd is never sent). Tool-approval
 * questions raised mid-turn appear inline with Approve/Deny buttons
 * (`chat.approve`); Stop sends `chat.cancel`. Otherwise the screen falls back
 * to the local [DemoTransport] and says so — chat history stays device-local
 * either way. A `session_id` route argument selects the remote session the
 * turn runs in; without one, the active local session id is used.
 */
@Composable
internal fun ChatScreen(
    connection: CompanionConnection,
    navController: NavHostController,
    sessionId: String = "",
) {
    val context = LocalContext.current
    val sessionStore = remember(context) { LocalSessionStore(context.applicationContext) }
    var sessionsState by remember { mutableStateOf(sessionStore.load()) }
    var chatState by remember { mutableStateOf(sessionsState.active.chat) }
    var renameDraft by remember { mutableStateOf(sessionsState.active.title) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var activeRequestJob by remember { mutableStateOf<Job?>(null) }

    val remoteState by connection.state.collectAsState()
    val pairedHost by connection.pairedHost.collectAsState()
    val hostPaired = pairedHost != null
    val isLive = remoteState == RemoteSocketState.AUTHENTICATED &&
        connection.hasScope(PairingScope.SEND_CHAT)

    val routeSessionId = sessionId.takeIf { it.isNotBlank() }
    val activeLocalSessionId by rememberUpdatedState(sessionsState.activeId)
    val liveTransport = remember(connection) {
        CompanionChatTransport(connection) { routeSessionId ?: activeLocalSessionId }
    }
    val demoTransport = remember { DemoTransport() }
    val transport: ChatTransport = if (isLive) liveTransport else demoTransport
    val liveToolCalls by liveTransport.toolCalls.collectAsState()
    val pendingApprovals by liveTransport.pendingApprovals.collectAsState()

    fun persistChat(state: ChatUiState) {
        chatState = state
        sessionsState = updateActiveLocalChat(sessionsState, state)
        sessionStore.save(sessionsState)
    }

    fun dispatch(action: ChatAction) {
        val transition = reduceChatState(chatState, action)
        persistChat(transition.state)
        transition.promptToSend?.let { prompt ->
            activeRequestJob = scope.launch {
                try {
                    val response = transport.stream(prompt) { delta ->
                        persistChat(reduceChatState(chatState, ChatAction.ResponseDelta(delta)).state)
                    }
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
                Text(
                    when {
                        isLive -> "HOST · Live chat"
                        hostPaired && remoteState == RemoteSocketState.AUTHENTICATED -> "HOST · Connected"
                        hostPaired -> "Paired · Offline"
                        else -> "DEMO · Offline"
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    isLive -> "Connected to the host. Chat runs on the host inside the approved workspace."
                    hostPaired && remoteState == RemoteSocketState.AUTHENTICATED ->
                        "Host authenticated, but chat.send was not granted at pair time. Re-pair with the chat.send scope to chat live."
                    else -> "Local demo mode. Sessions and chat are stored on this device; no live host chat is active."
                },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        ConnectionStatusPanel(hostPaired, remoteState)

        routeSessionId?.let {
            ScreenNote("Remote session: $it")
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Local sessions", style = MaterialTheme.typography.titleMedium)
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
                        MarkdownMessage(message.text)
                    }
                }
            }
        }

        ToolActivityView(if (isLive) liveToolCalls else chatState.toolCalls, modifier = Modifier.fillMaxWidth())

        // Inline tool-approval questions from the running turn. Each answer is
        // one explicit tap; nothing is auto-approved.
        pendingApprovals.forEach { approval ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Host asks for approval", style = MaterialTheme.typography.titleSmall)
                    Text(approval.question, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { liveTransport.answerApproval(approval, true) }) { Text("Approve") }
                        OutlinedButton(onClick = { liveTransport.answerApproval(approval, false) }) { Text("Deny") }
                    }
                }
            }
        }

        if (chatState.streamingText.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("EMRG · streaming", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    MarkdownMessage(chatState.streamingText)
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
                enabled = if (chatState.isSending) true else chatState.draft.isNotBlank(),
                onClick = {
                    if (chatState.isSending) {
                        dispatch(ChatAction.Cancel)
                        if (isLive) liveTransport.cancelActive()
                        activeRequestJob?.cancel()
                        activeRequestJob = null
                    } else dispatch(ChatAction.Submit)
                }
            ) { Text(if (chatState.isSending) "Stop" else "Send") }
        }
    }
}
