package app.strap.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import app.strap.api.ApiException
import app.strap.api.Reader
import app.strap.sync.JournalEntry
import app.strap.sync.Owner
import app.strap.ui.components.CardHeader
import app.strap.ui.components.CardValue
import app.strap.ui.components.IconCircle
import app.strap.ui.components.LocalSnackbar
import app.strap.ui.components.Note
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SectionTitle
import app.strap.ui.components.Sparkline
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.RidgeType
import app.strap.ui.today.journalHeadline
import app.strap.ui.today.journalName
import kotlinx.coroutines.launch
import strap.science.WeightEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The journal the phone owns (D34 S3): quick log (weight included), the last seven days'
 * entries by day, each in its own colours with delete and undo, and weight over time.
 * [owner] keeps it; [onChanged] re-derives after a weight.
 */
@Composable
fun JournalScreen(api: Reader, owner: Owner, onChanged: suspend (Instant) -> Unit) {
    var entries by remember { mutableStateOf<List<JournalEntry>?>(null) }
    var weights by remember { mutableStateOf<List<WeightEntry>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var weightDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snack = LocalSnackbar.current
    val today = LocalDate.now()
    LaunchedEffect(reload) {
        try {
            entries = api.journal(today.minusDays(6), today).sortedByDescending { it.ts }
            weights = owner.weights().sortedBy { it.ts }
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
    val c = LocalMetricColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val zone = ZoneId.systemDefault()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            RidgeCard(spacing = 12.dp) {
                CardHeader("Quick log", "logged now", icon = Icons.Rounded.Coffee, tone = c.chargeTone)
                QuickLogRow(onPick = { q -> add(q.kind, q.value, q.name, done = q.logged) }, onWeight = { weightDialog = true })
            }
        }
        val list = entries
        if (list != null && list.isEmpty()) item { RidgeCard { Subtle("Nothing logged in the last 7 days.") } }
        list?.groupBy { it.ts.atZone(zone).toLocalDate() }?.forEach { (day, items) ->
            item(key = day.toString()) { SectionTitle(dayTitle(day, today)) }
            item(key = "$day-items") {
                RidgeCard(spacing = 0.dp, padding = 8.dp) {
                    items.forEach { e ->
                        val tone = journalTone(e.kind, e.name)
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconCircle(journalIcon(e.kind, e.name), tone.container, tone.accent, size = 36.dp, iconSize = 18.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(journalHeadline(e), style = RidgeType.rowTitle)
                                Subtle(if (e.kind == "status") "for the day" else listOfNotNull(journalName(e), clockOf(e.ts.toEpochMilli())).joinToString(" · "), style = RidgeType.body)
                            }
                            IconButton(onClick = { delete(e) }) { Icon(Icons.Outlined.Delete, "Delete", tint = muted) }
                        }
                    }
                }
            }
        }
        if (weights.isNotEmpty()) {
            item { SectionTitle("Weight") }
            item {
                val last = weights.last()
                val first = weights.first()
                val change = last.kg - first.kg
                RidgeCard(spacing = 12.dp) {
                    CardHeader("Weight", "${weights.size} weigh-ins", icon = Icons.Rounded.MonitorWeight, tone = c.strainTone)
                    CardValue("%.1f".format(last.kg), "kg",
                        Note("%s%.1f kg since %s".format(if (change >= 0) "+" else "−", kotlin.math.abs(change), first.ts.atZone(zone).format(DateTimeFormatter.ofPattern("d MMM"))), muted))
                    Sparkline(weights.map { it.kg.toDouble() }, c.strain)
                    weights.takeLast(4).reversed().forEach { w ->
                        Row(Modifier.fillMaxWidth()) {
                            Subtle(w.ts.atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM")), Modifier.weight(1f), RidgeType.body)
                            Text("%.1f kg".format(w.kg), style = RidgeType.label)
                        }
                    }
                }
            }
        }
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
