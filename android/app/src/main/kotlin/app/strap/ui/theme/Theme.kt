package app.strap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Metric colours — one per metric family, the same on every screen (Bevel-like for now). */
@Immutable
data class MetricColors(
    val recovery: Color = Color(0xFF34C77B),
    val strain: Color = Color(0xFF3D8BFF),
    val sleep: Color = Color(0xFF8E6CFF),
    val heart: Color = Color(0xFFFF5A5F),
    val stress: Color = Color(0xFFFFA940),
    val steps: Color = Color(0xFF30C3D6),
    val track: Color = Color(0x22808080),
)

val LocalMetricColors = staticCompositionLocalOf { MetricColors() }

private val Dark = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    onBackground = Color(0xFFF2F2F7),
    onSurface = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF9A9AA0),
    primary = Color(0xFF34C77B),
)

private val Light = lightColorScheme(
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9E9EE),
    onBackground = Color(0xFF111114),
    onSurface = Color(0xFF111114),
    onSurfaceVariant = Color(0xFF6E6E73),
    primary = Color(0xFF1FA463),
)

/** Follows the system light/dark setting (owner's choice). */
@Composable
fun StrapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
