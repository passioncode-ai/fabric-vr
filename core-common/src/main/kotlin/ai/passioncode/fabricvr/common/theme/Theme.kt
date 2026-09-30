package ai.passioncode.fabricvr.common.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

private val darkScheme = darkColorScheme(
    primary = Tokens.Palette.accent,
    onPrimary = Tokens.Palette.accentInk,
    background = Tokens.Palette.ink,
    onBackground = Tokens.Palette.text,
    surface = Tokens.Palette.surface,
    onSurface = Tokens.Palette.text,
    surfaceVariant = Tokens.Palette.surfaceRaised,
    onSurfaceVariant = Tokens.Palette.textMuted,
    outline = Tokens.Palette.line,
    error = Tokens.Palette.danger,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = Tokens.Type.titleSize, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = Tokens.Type.headingSize, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = Tokens.Type.bodySize),
    labelLarge = TextStyle(fontSize = Tokens.Type.labelSize, fontWeight = FontWeight.Medium),
)

/**
 * Dark only, and that is a decision rather than a missing branch: the panel floats over passthrough,
 * where a bright surface behaves like a lamp pointed at the wearer.
 */
@Composable
fun FabricTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkScheme,
        typography = typography,
        content = content,
    )
}
