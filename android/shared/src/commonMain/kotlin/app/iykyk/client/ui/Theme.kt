package app.iykyk.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Brand colours of «Для своих», taken from the logo. */
object Brand {
    val Navy = Color(0xFF1B3E5E)
    val Orange = Color(0xFFED8532)
    val Mist = Color(0xFFEBEFF1)
}

@Immutable
data class IykykPalette(
    val Background: Color,
    val Surface: Color,
    val SurfaceHigh: Color,
    /** Orange: the connect button, active states, links. */
    val Accent: Color,
    val OnAccent: Color,
    val Text: Color,
    val Muted: Color,
    val Warning: Color,
    val Error: Color,
)

private val LightPalette = IykykPalette(
    Background = Brand.Mist,
    Surface = Color(0xFFFFFFFF),
    SurfaceHigh = Color(0xFFDCE4EA),
    Accent = Brand.Orange,
    OnAccent = Color(0xFFFFFFFF),
    Text = Brand.Navy,
    Muted = Color(0xFF63778B),
    Warning = Color(0xFFB7660F),
    Error = Color(0xFFC0392B),
)

private val DarkPalette = IykykPalette(
    Background = Color(0xFF0D1C2B),
    Surface = Color(0xFF14283D),
    SurfaceHigh = Color(0xFF1B3E5E),
    Accent = Brand.Orange,
    OnAccent = Color(0xFF2A1404),
    Text = Color(0xFFEAF0F5),
    Muted = Color(0xFF8FA4B8),
    Warning = Color(0xFFF4B46B),
    Error = Color(0xFFFF7A6B),
)

private val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Current palette; follows the system light/dark setting. */
object IykykColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.SurfaceHigh
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.OnAccent
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Muted
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Warning
    val Error: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.Error
}

@Composable
fun IykykTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.Accent,
            onPrimary = palette.OnAccent,
            secondary = Brand.Navy,
            background = palette.Background,
            onBackground = palette.Text,
            surface = palette.Surface,
            onSurface = palette.Text,
            surfaceVariant = palette.SurfaceHigh,
            onSurfaceVariant = palette.Muted,
        )
    } else {
        lightColorScheme(
            primary = palette.Accent,
            onPrimary = palette.OnAccent,
            secondary = Brand.Navy,
            background = palette.Background,
            onBackground = palette.Text,
            surface = palette.Surface,
            onSurface = palette.Text,
            surfaceVariant = palette.SurfaceHigh,
            onSurfaceVariant = palette.Muted,
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
