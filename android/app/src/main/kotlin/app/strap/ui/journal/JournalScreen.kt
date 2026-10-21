package app.strap.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.strap.api.Reader
import app.strap.sync.JournalEntry
import app.strap.sync.Owner
import app.strap.api.ApiException
import app.strap.ui.components.Grouped
import app.strap.ui.components.IconCircle
import app.strap.ui.components.ListRow
import app.strap.ui.components.LocalSnackbar
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import app.strap.ui.today.journalHeadline
import app.strap.ui.today.journalIcon
import app.strap.ui.today.journalName
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class Quick(val label: String, val amount: String, val kind: String, val value: Double, val name: String?, val logged: String)

private val QUICK = listOf(
    Quick("Espresso", "60 mg", "caffeine", 60.0, "espresso", "Espresso logged"),
    Quick("Coffee", "95 mg", "caffeine", 95.0, "coffee", "Coffee logged"),
    Quick("Tea", "45 mg", "caffeine", 45.0, "tea", "Tea logged"),
    Quick("Drink", "1 standard", "alcohol", 1.0, null, "Drink logged"),
)

@Composable
/** The journal the phone owns (D34 S3): [owner] keeps it, [onChanged] re-derives after an entry. */
fun JournalScreen(api: Reader, owner: Owner, onChanged: suspend (Instant) -> Unit) {
    var entries by remember { mutableStateOf<List<JournalEntry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var weightDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snack = LocalSnackbar.current
    val today = LocalDate.now()
    LaunchedEffect(reload) {
        try {
            entries = api.journal(today.minusDays(6), today).sortedByDescending { it.ts }
        } catch (e: ApiException) {
            snack(e.message ?: "Could not load the journal.", null, null)
        }
    }
    fun add(kind: String, amount: Double, name: String?, at: Instant = Instant.now(), done: String) = scope.launch {
        owner.add(kind, amount, name, at)
        reload++
        snack(done, null, null)
        if (kind == "weight") onChanged(at)
    }
    fun delete(e: JournalEntry) = scope.launch {
        owner.delete(e.id)
        reload++
        snack("Entry deleted", "Undo") { add(e.kind, e.amount, e.name?.takeIf { it.isNotEmpty() }, e.ts, "Entry restored") }
        if (e.kind == "weight") onChanged(e.ts)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { DayLabel("Quick log") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { q -> QuickTile(q, Modifier.weight(1f)) { add(q.kind, q.value, q.name, done = q.logged) } }
                        }
                    }
                }
            }
            val list = entries
            if (list != null && list.isEmpty()) item { Subtle("Nothing logged in the last 7 days.", Modifier.padding(4.dp)) }
            list?.groupBy { it.ts.atZone(ZoneId.systemDefault()).toLocalDate() }?.forEach { (day, items) ->
                item(key = day.toString()) { DayLabel(dayTitle(day, today)) }
                item(key = "$day-items") {
                    Grouped(items) { e, shape ->
                        val sub = listOfNotNull(journalName(e), clockOf(e.ts.toEpochMilli())).joinToString(" · ")
                        ListRow(shape, journalHeadline(e), sub,
                            leading = { IconCircle(journalIcon(e.kind, e.name), LocalRidgeColors.current.surface3, MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailing = { IconButton(onClick = { delete(e) }) { Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) } })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { weightDialog = true },
            icon = { Icon(Icons.Rounded.MonitorWeight, null) },
            text = { Text("Weight", style = RidgeType.cardTitle) },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).height(64.dp),
        )
    }
    if (weightDialog) WeightDialog(onDismiss = { weightDialog = false }) { kg ->
        weightDialog = false
        add("weight", kg, null, done = "Weight saved")
    }
}

private fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}

@Composable
private fun DayLabel(text: String) {
    Text(text, style = RidgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
}

@Composable
private fun QuickTile(q: Quick, modifier: Modifier, onClick: () -> Unit) {
    val tone = LocalMetricColors.current.stressTone
    val icon: ImageVector = journalIcon(q.kind, q.name)
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).background(tone.container).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = tone.onContainer)
        Column {
            Text(q.label, style = RidgeType.cardTitle, color = tone.onContainer)
            Text(q.amount, style = RidgeType.body, color = tone.onContainer.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun WeightDialog(onDismiss: () -> Unit, onSave: (Double) -> Unit) {
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
