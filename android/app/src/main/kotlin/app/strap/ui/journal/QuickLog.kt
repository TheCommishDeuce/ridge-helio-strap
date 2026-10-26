package app.strap.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.CoffeeMaker
import androidx.compose.material.icons.rounded.EmojiFoodBeverage
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.WineBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.MetricTone
import app.strap.ui.theme.RidgeType
import app.strap.sync.DayStatus
import app.strap.ui.components.icon
import app.strap.ui.components.tone
import java.time.Instant
import java.time.LocalDate

/** One quick-log button: what it logs and what the snackbar says. */
internal data class Quick(val label: String, val amount: String, val kind: String, val value: Double, val name: String?, val logged: String)

internal val QUICK = listOf(
    Quick("Coffee", "95 mg", "caffeine", 95.0, "coffee", "Coffee logged"),
    Quick("Espresso", "60 mg", "caffeine", 60.0, "espresso", "Espresso logged"),
    Quick("Tea", "45 mg", "caffeine", 45.0, "tea", "Tea logged"),
    Quick("Drink", "1 standard", "alcohol", 1.0, null, "Drink logged"),
)

/** The journal's icon for an entry. */
internal fun journalIcon(kind: String, name: String?): ImageVector = when (kind) {
    "weight" -> Icons.Rounded.MonitorWeight
    "status" -> DayStatus.of(name).icon
    "alcohol" -> Icons.Rounded.WineBar
    else -> when (name) {
        "espresso" -> Icons.Rounded.CoffeeMaker
        "tea" -> Icons.Rounded.EmojiFoodBeverage
        else -> Icons.Rounded.Coffee
    }
}

/** The journal's colours: caffeine amber (Energy's), alcohol rose (heart's), weight blue (strain's), a status its own. */
@Composable
internal fun journalTone(kind: String, name: String? = null): MetricTone {
    val c = LocalMetricColors.current
    return when (kind) {
        "weight" -> c.strainTone
        "alcohol" -> c.heartTone
        "status" -> DayStatus.of(name).tone()
        else -> c.energyTone
    }
}

/**
 * Adds a journal entry from anywhere: [add] writes it (kind, amount, name, when, what the
 * snackbar says); [setStatus] marks a day's activity status. Built once in the shell, which owns
 * the journal and re-derives after a weight.
 */
class JournalLogger(
    val add: (kind: String, amount: Double, name: String?, at: Instant, done: String) -> Unit,
    val setStatus: (day: LocalDate, status: DayStatus) -> Unit,
)

/** The quick-log chips (coffee, espresso, tea, a drink, weight), each in the journal's colours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickLogRow(onPick: (Quick) -> Unit, onWeight: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QUICK.forEach { q -> Chip(journalIcon(q.kind, q.name), q.label, journalTone(q.kind)) { onPick(q) } }
        Chip(journalIcon("weight", null), "Weight", journalTone("weight"), onWeight)
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, tone: MetricTone, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(tone.container).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = tone.accent, modifier = Modifier.size(16.dp))
        Text(label, style = RidgeType.label, color = tone.onContainer)
    }
}

@Composable
internal fun WeightDialog(onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    val kg = text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalRidgeColors.current.surface3,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Weight") },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text("kg") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = { TextButton(enabled = kg != null, onClick = { kg?.let(onSave) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
