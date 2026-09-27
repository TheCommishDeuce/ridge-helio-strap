package app.strap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The card every screen uses: accent dot, title, optional chevron, optional headline. */
@Composable
fun MetricCard(title: String, accent: Color, headline: String? = null, onClick: (() -> Unit)? = null, body: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("●", color = accent)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onClick != null) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            headline?.let { Text(it, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
            body()
        }
    }
}

@Composable
fun Subtle(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A labelled row: left label, right value (used for breakdowns). */
@Composable
fun StatRow(label: String, value: String, mark: String? = null, markColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        mark?.let { Text(it, color = markColor, modifier = Modifier.padding(end = 8.dp)) }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(selected = selected == o, onClick = { onSelect(o) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label(o)) }
        }
    }
}

fun hm(minutes: Double): String = "%dh %02dm".format((minutes / 60).toInt(), (minutes % 60).roundToInt())

/** A plain proportion bar: no end dot, no gap — just the share, in the metric's colour. */
@Composable
fun Bar(fraction: Float, color: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(app.strap.ui.theme.LocalMetricColors.current.track),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}
