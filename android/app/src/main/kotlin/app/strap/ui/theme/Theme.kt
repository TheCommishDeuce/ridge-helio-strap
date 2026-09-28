package app.strap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One metric family's colours: the accent (arcs, lines, labels) and its tonal container pair. */
@Immutable
data class MetricTone(val accent: Color, val container: Color, val onContainer: Color)

/**
 * Metric colours — one per metric family, the same on every screen ("Performance" design,
 * docs/design handoff). The plain fields are the accents most drawing code wants.
 */
@Immutable
data class MetricColors(
    val recoveryTone: MetricTone,
    val strainTone: MetricTone,
    val sleepTone: MetricTone,
    val heartTone: MetricTone,
    val stressTone: MetricTone,
    val stepsTone: MetricTone,
    /** Gauge tracks, chart gridlines, empty bar tracks (`surface3`). */
    val track: Color,
) {
    val recovery get() = recoveryTone.accent
    val strain get() = strainTone.accent
    val sleep get() = sleepTone.accent
    val heart get() = heartTone.accent
    val stress get() = stressTone.accent
    val steps get() = stepsTone.accent
}

/** The neutrals and zone colours Material's scheme has no slot for. */
@Immutable
data class RidgeColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val card: Color,
    val surface3: Color,
    val surface4: Color,
    val zoneGreen: Color,
    val zoneYellow: Color,
    val zoneRed: Color,
) {
    /** Recovery zone: ≥ 67 green, 34–66 yellow, below red. */
    fun zone(recovery: Double): Color = when {
        recovery >= 67 -> zoneGreen
        recovery >= 34 -> zoneYellow
        else -> zoneRed
    }
}

private val DarkRidge = RidgeColors(
    bg = Color(0xFF020203), surface1 = Color(0xFF060709), surface2 = Color(0xFF0A0B0D), card = Color(0xFF0C0F11),
    surface3 = Color(0xFF1D2022), surface4 = Color(0xFF2C2E31),
    zoneGreen = Color(0xFF5EDB81), zoneYellow = Color(0xFFF4CD4B), zoneRed = Color(0xFFFC5855),
)

private val LightRidge = RidgeColors(
    bg = Color(0xFFF6F7F8), surface1 = Color(0xFFEFF0F2), surface2 = Color(0xFFEAEBED), card = Color(0xFFFDFDFE),
    surface3 = Color(0xFFE1E3E5), surface4 = Color(0xFFD7D9DC),
    zoneGreen = Color(0xFF008D3C), zoneYellow = Color(0xFFB88000), zoneRed = Color(0xFFD02B31),
)

private val DarkMetrics = MetricColors(
    recoveryTone = MetricTone(Color(0xFF7AD59C), Color(0xFF183E27), Color(0xFFCBEFD6)),
    strainTone = MetricTone(Color(0xFF88C1FF), Color(0xFF1F3654), Color(0xFFCFE7FF)),
    sleepTone = MetricTone(Color(0xFFC3AEFF), Color(0xFF382F51), Color(0xFFE6DFFF)),
    heartTone = MetricTone(Color(0xFFFF9E96), Color(0xFF502825), Color(0xFFFFD8D4)),
    stressTone = MetricTone(Color(0xFFF5AC69), Color(0xFF4B2E11), Color(0xFFFEDEC3)),
    stepsTone = MetricTone(Color(0xFF44D4E2), Color(0xFF003E44), Color(0xFFBFEFF4)),
    track = DarkRidge.surface3,
)

private val LightMetrics = MetricColors(
    recoveryTone = MetricTone(Color(0xFF007F43), Color(0xFFCBEFD6), Color(0xFF003319)),
    strainTone = MetricTone(Color(0xFF2769B7), Color(0xFFCFE7FF), Color(0xFF0E294A)),
    sleepTone = MetricTone(Color(0xFF7055B0), Color(0xFFE6DFFF), Color(0xFF2C2047)),
    heartTone = MetricTone(Color(0xFFAB413E), Color(0xFFFFD8D4), Color(0xFF451816)),
    stressTone = MetricTone(Color(0xFFA05100), Color(0xFFFEDEC3), Color(0xFF401F00)),
    stepsTone = MetricTone(Color(0xFF007E8F), Color(0xFFBFEFF4), Color(0xFF003239)),
    track = LightRidge.surface3,
)

val LocalMetricColors = staticCompositionLocalOf { DarkMetrics }
val LocalRidgeColors = staticCompositionLocalOf { DarkRidge }

// Material slots: surface = bg (screens, bars), surfaceContainer = card, High = surface3
// (dialogs, date picker), Highest = surface4 (inactive switch track).
private val Dark = darkColorScheme(
    background = DarkRidge.bg, onBackground = Color(0xFFEDEFF0),
    surface = DarkRidge.bg, onSurface = Color(0xFFEDEFF0), onSurfaceVariant = Color(0xFFA2A5A8),
    surfaceVariant = DarkRidge.surface3, surfaceTint = Color.Transparent,
    surfaceContainerLowest = DarkRidge.bg, surfaceContainerLow = DarkRidge.surface1, surfaceContainer = DarkRidge.card,
    surfaceContainerHigh = DarkRidge.surface3, surfaceContainerHighest = DarkRidge.surface4,
    outline = Color(0xFF6F7275), outlineVariant = Color(0xFF191B1D),
    primary = Color(0xFFE6E8EA), onPrimary = Color(0xFF050607),
    primaryContainer = Color(0xFF242729), onPrimaryContainer = Color(0xFFEDEFF0),
    secondary = Color(0xFFE6E8EA), onSecondary = Color(0xFF050607),
    secondaryContainer = Color(0xFF1F2224), onSecondaryContainer = Color(0xFFEDEFF0),
    inverseSurface = Color(0xFFEDEFF0), inverseOnSurface = Color(0xFF101213), inversePrimary = Color(0xFF151618),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

private val Light = lightColorScheme(
    background = LightRidge.bg, onBackground = Color(0xFF101213),
    surface = LightRidge.bg, onSurface = Color(0xFF101213), onSurfaceVariant = Color(0xFF4D5053),
    surfaceVariant = LightRidge.surface3, surfaceTint = Color.Transparent,
    surfaceContainerLowest = LightRidge.bg, surfaceContainerLow = LightRidge.surface1, surfaceContainer = LightRidge.card,
    surfaceContainerHigh = LightRidge.surface3, surfaceContainerHighest = LightRidge.surface4,
    outline = Color(0xFF7E8084), outlineVariant = Color(0xFFDCDEE0),
    primary = Color(0xFF151618), onPrimary = Color(0xFFF7F8FA),
    primaryContainer = Color(0xFFDCDEE0), onPrimaryContainer = Color(0xFF101213),
    secondary = Color(0xFF151618), onSecondary = Color(0xFFF7F8FA),
    secondaryContainer = Color(0xFFDFE1E4), onSecondaryContainer = Color(0xFF101213),
    inverseSurface = Color(0xFF101213), inverseOnSurface = Color(0xFFEDEFF0), inversePrimary = Color(0xFFE6E8EA),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
)

/** The design's type: Roboto (system), 400 for numbers and body, 500 for titles; nothing bolder. */
object RidgeType {
    val topTitle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp)
    val topSubtitle = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val headline = TextStyle(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Normal, letterSpacing = (-2).sp)
    val setupTitle = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal)
    val bigNumber = TextStyle(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Normal)
    val cardNumber = TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal)
    val rowValue = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal)
    val sideValue = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal)
    val sheetTitle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
    val cardTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val rowTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val paragraph = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val section = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val change = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    val unit = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
}

private val Type = Typography().let { t ->
    t.copy(
        titleLarge = RidgeType.topTitle,
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    )
}

/** Follows the system light/dark setting (owner's choice). */
@Composable
fun StrapTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalMetricColors provides if (dark) DarkMetrics else LightMetrics,
        LocalRidgeColors provides if (dark) DarkRidge else LightRidge,
    ) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
    }
}
