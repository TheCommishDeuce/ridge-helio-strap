package app.strap.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.components.MetricCard
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class Quick(val label: String, val kind: String, val amount: Double, val name: String?)

private val QUICK = listOf(
    Quick("Espresso · 60 mg", "caffeine", 60.0, "espresso"),
    Quick("Coffee · 95 mg", "caffeine", 95.0, "coffee"),
    Quick("Tea · 45 mg", "caffeine", 45.0, "tea"),
    Quick("Drink · 1 standard", "alcohol", 1.0, null),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(api: ApiClient) {
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var weightDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val c = LocalMetricColors.current
    LaunchedEffect(reload) {
        try {
            val arr = api.journal(today.minusDays(6), today)
            entries = List(arr.length()) { arr.getJSONObject(it) }
        } catch (e: ApiException) {
            message = e.message
        }
    }
    fun add(kind: String, amount: Double, name: String?) = scope.launch {
        try {
            val body = JSONObject().put("kind", kind).put("amount", amount).put("ts", OffsetDateTime.now().toString())
            name?.let { body.put("name", it) }
            val out = api.addJournal(body)
            message = if (kind == "weight") "Weight saved — ${out.getInt("rederived_days")} days recalculated." else "Logged."
            reload++
        } catch (e: ApiException) {
            message = e.message
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            MetricCard("Log", c.stress) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK.forEach { q -> AssistChip(onClick = { add(q.kind, q.amount, q.name) }, label = { Text(q.label) }) }
                    AssistChip(onClick = { weightDialog = true }, label = { Text("Weight…") })
                }
                message?.let { Subtle(it) }
                Subtle("Caffeine and alcohol times feed your personal cut-off analysis once there are enough nights. Weight updates calories and VO₂max from the day it was logged.")
            }
        }
        item { Text("Last 7 days", style = MaterialTheme.typography.titleMedium) }
        if (entries.isEmpty()) item { Subtle("Nothing logged yet.") }
        items(entries, key = { it.getString("id") }) { e ->
            val at = Instant.ofEpochMilli(e.getLong("ts")).atZone(ZoneId.systemDefault())
            val amount = e.getDouble("amount").let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }
            ListItem(
                headlineContent = { Text("${e.getString("kind").replaceFirstChar { it.uppercase() }} · $amount ${e.getString("unit")}" + (e.optString("name").takeIf { it.isNotEmpty() && it != "null" }?.let { " ($it)" } ?: "")) },
                supportingContent = { Text(at.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " " + clockOf(e.getLong("ts"))) },
                trailingContent = {
                    IconButton(onClick = {
                        scope.launch {
                            try { api.deleteJournal(e.getString("id")); reload++ } catch (x: ApiException) { message = x.message }
                        }
                    }) { Icon(Icons.Outlined.Delete, "Delete") }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)),
            )
        }
    }
    if (weightDialog) WeightDialog(onDismiss = { weightDialog = false }) { kg -> weightDialog = false; add("weight", kg, null) }
}

@Composable
private fun WeightDialog(onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    val kg = text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weight") },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text("kg") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = { TextButton(enabled = kg != null, onClick = { kg?.let(onSave) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
