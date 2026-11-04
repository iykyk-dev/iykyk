package app.iykyk.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object IykykColors {
    val Background = Color(0xFF0B0F0E)
    val Surface = Color(0xFF141A18)
    val SurfaceHigh = Color(0xFF1C2421)
    val Accent = Color(0xFF00E676)
    val OnAccent = Color(0xFF00210F)
    val Text = Color(0xFFE6EEEA)
    val Muted = Color(0xFF7D8C86)
    val Warning = Color(0xFFFFC107)
}

private val scheme = darkColorScheme(
    primary = IykykColors.Accent,
    onPrimary = IykykColors.OnAccent,
    background = IykykColors.Background,
    onBackground = IykykColors.Text,
    surface = IykykColors.Surface,
    onSurface = IykykColors.Text,
    surfaceVariant = IykykColors.SurfaceHigh,
    onSurfaceVariant = IykykColors.Muted,
)

/** The brand is dark-only on purpose. */
@Composable
fun IykykTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
