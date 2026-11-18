package ai.emrg.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Small dependency-free Markdown renderer for chat messages.
 * Supports fenced code blocks, inline code, bold, headings and unordered list markers.
 * It intentionally does not evaluate HTML or render remote images/links.
 */
@Composable
fun MarkdownMessage(text: String, modifier: Modifier = Modifier) {
    val lines = text.split("\n")
    var inCode = false
    val codeLines = mutableListOf<String>()
    val proseLines = mutableListOf<String>()

    Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    CodeBlock(codeLines.joinToString("\n"))
                    codeLines.clear()
                    inCode = false
                } else {
                    if (proseLines.isNotEmpty()) {
                        ProseBlock(proseLines.toList())
                        proseLines.clear()
                    }
                    inCode = true
                }
            } else if (inCode) {
                codeLines += line
            } else {
                proseLines += line
            }
        }
        if (inCode) CodeBlock(codeLines.joinToString("\n"))
        if (proseLines.isNotEmpty()) ProseBlock(proseLines)
    }
}

@Composable
private fun ProseBlock(lines: List<String>) {
    lines.forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Spacer(Modifier.height(3.dp))
            line.trimStart().startsWith("#") -> {
                val level = line.trimStart().takeWhile { it == '#' }.length.coerceIn(1, 3)
                val heading = line.trimStart().drop(level).trim()
                Text(
                    inlineMarkdown(heading),
                    style = when (level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                Text("•  " + inlineMarkdown(line.trimStart().drop(2)), style = MaterialTheme.typography.bodyMedium)
            line.trimStart().matches(Regex("^\\d+\\.\\s+.*")) ->
                Text(line.trimStart(), style = MaterialTheme.typography.bodyMedium)
            else -> Text(inlineMarkdown(line), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Text(
        code,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun inlineMarkdown(source: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val pattern = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
    var cursor = 0
    pattern.findAll(source).forEach { match ->
        if (match.range.first > cursor) builder.append(source.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("```") -> builder.append(token)
            token.startsWith("`") -> builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.LightGray.copy(alpha = 0.25f))).also {
                builder.append(token.removeSurrounding("`"))
                builder.pop()
            }
            token.startsWith("**") -> builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold)).also {
                builder.append(token.removeSurrounding("**"))
                builder.pop()
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < source.length) builder.append(source.substring(cursor))
    return builder.toAnnotatedString()
}
