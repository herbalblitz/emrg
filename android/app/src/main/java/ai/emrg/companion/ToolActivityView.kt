package ai.emrg.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

/** Render protocol tool lifecycle state. This is UI only; it does not execute tools. */
@Composable
internal fun ToolActivityView(calls: List<ToolCallState>, modifier: Modifier = Modifier) {
    if (calls.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        calls.forEachIndexed { index, call ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(call.label ?: call.name ?: "Tool call ${index + 1}", style = MaterialTheme.typography.titleSmall)
                    Text(toolActivityStatus(call), style = MaterialTheme.typography.labelMedium)
                    call.arguments?.takeIf { it.isNotBlank() }?.let {
                        Text("Arguments", style = MaterialTheme.typography.labelSmall)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    call.result?.let {
                        Text(if (call.failed) "Error" else "Result", style = MaterialTheme.typography.labelSmall)
                        MarkdownMessage(it)
                    }
                }
            }
        }
    }
}

internal fun toolActivityStatus(call: ToolCallState): String = when {
    call.failed -> "Failed"
    call.completed -> "Completed"
    else -> "Running"
}
