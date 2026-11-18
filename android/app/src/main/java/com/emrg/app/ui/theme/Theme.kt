package com.emrg.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF141F2E),
    onPrimaryContainer = Color(0xFF5EEAD4),
    secondary = Color(0xFFBBE1FA),
    onSecondary = Color(0xFF0B1220),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE7ECF0),
    surface = Color(0xFF111824),
    onSurface = Color(0xFFE7ECF0),
    surfaceVariant = Color(0xFF1F2A37),
    onSurfaceVariant = Color(0xFF9AA5B1),
    outline = Color(0xFF334155),
    inverseSurface = Color(0xFFE7ECF0),
    inverseOnSurface = Color(0xFF0B1220),
    inversePrimary = Color(0xFF14B8A6),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0B1220),
    onPrimary = Color(0xFF5EEAD4),
    primaryContainer = Color(0xFFE2E8F0),
    onPrimaryContainer = Color(0xFF0B1220),
    secondary = Color(0xFF0B1220),
    onSecondary = Color(0xFF5EEAD4),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    inverseSurface = Color(0xFF0F172A),
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFF5EEAD4),
)

@Composable
fun EmrgTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = EmrgTypography,
        content = content,
    )
}

@Composable
fun SurfaceContent(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = EmrgTypography,
        content = content,
    )
}
