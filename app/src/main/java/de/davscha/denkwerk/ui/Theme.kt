package de.davscha.denkwerk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dunkles Design mit Mint als Akzentfarbe */
val Mint = Color(0xFF5EE6B4)

private val Colors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF06261B),
    secondary = Color(0xFF9B7BFF),
    background = Color(0xFF131315),
    onBackground = Color(0xFFECECEE),
    surface = Color(0xFF1B1B1E),
    onSurface = Color(0xFFECECEE),
    surfaceVariant = Color(0xFF2A2A2F),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF34343A),
    error = Color(0xFFFF6B6B),
)

@Composable
fun DenkwerkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
