package app.strap.ui.strap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.strap.ui.components.StatRow
import app.strap.ui.components.Subtle
import strap.protocol.parse.Config
import strap.protocol.parse.Config.Health
import strap.protocol.parse.Config.Workout
import strap.protocol.parse.ConfigGroup
import strap.protocol.parse.ConfigValue

/** A strap settings group, read-only: one row per setting the strap reported that we can name. */
@Composable
internal fun SettingsCard(title: String, group: ConfigGroup?, rows: List<Pair<String, String>>) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when {
                group == null -> Subtle("The strap did not send these settings.")
                rows.isEmpty() -> Subtle("The strap reported none of the settings we know.")
                else -> rows.forEach { (label, value) -> StatRow(label, value) }
            }
            if (group != null && !group.complete) Subtle("Some settings could not be read.")
            if (group != null) Subtle("Read from the strap. Changing them here comes later.")
        }
    }
}

internal fun healthRows(g: ConfigGroup?): List<Pair<String, String>> = rows(g, listOf(
    Health.HR_INTERVAL to "Heart-rate readings",
    Health.HR_HIGH_ALERT to "High heart-rate alert",
    Health.HR_LOW_ALERT to "Low heart-rate alert",
    Health.HR_DURING_ACTIVITY to "Heart rate during activity",
    Health.STRESS to "Stress monitoring",
    Health.STRESS_RELAX_REMINDER to "Relaxation reminder",
    Health.SLEEP_HIGH_ACCURACY to "High-accuracy sleep",
    Health.SLEEP_BREATHING to "Sleep breathing quality",
    Health.SPO2_ALL_DAY to "All-day SpO₂",
    Health.SPO2_LOW_ALERT to "Low SpO₂ alert",
    Health.HR_BROADCAST to "Share heart rate with other apps",
)) { key, v ->
    when {
        key == Health.HR_INTERVAL && v is ConfigValue.Choice -> when (val i = Health.hrInterval(v.value)) {
            Config.HrInterval.Off -> "Off"
            Config.HrInterval.Smart -> "Smart"
            Config.HrInterval.Continuous -> "Continuous"
            is Config.HrInterval.Every -> "Every ${i.minutes} min"
        }
        (key == Health.HR_HIGH_ALERT || key == Health.HR_LOW_ALERT) && v is ConfigValue.Choice ->
            if (v.value == 0) "Off" else "${if (key == Health.HR_HIGH_ALERT) "Above" else "Below"} ${v.value} bpm"
        key == Health.SPO2_LOW_ALERT && v is ConfigValue.Choice -> if (v.value == 0) "Off" else "Below ${v.value} %"
        else -> null
    }
}

internal fun workoutRows(g: ConfigGroup?): List<Pair<String, String>> = rows(g, listOf(
    Workout.DETECTION_ALERT to "Alert when a workout is detected",
    Workout.DETECTION_SENSITIVITY to "Detection sensitivity",
    Workout.HR_ZONES to "Heart-rate zones",
)) { key, v ->
    when {
        key == Workout.DETECTION_SENSITIVITY && v is ConfigValue.Choice ->
            listOf("High", "Standard", "Low").getOrElse(v.value) { "Unknown (${v.value})" }
        key == Workout.HR_ZONES && v is ConfigValue.Numbers -> v.values.joinToString(" · ") + " bpm"
        else -> null
    }
}

/** Known keys in [order]; [special] names a value, else flags read On/Off. Unknown keys are left out. */
private fun rows(g: ConfigGroup?, order: List<Pair<Int, String>>, special: (Int, ConfigValue) -> String?): List<Pair<String, String>> =
    order.mapNotNull { (key, label) ->
        val v = g?.values?.get(key) ?: return@mapNotNull null
        val text = special(key, v) ?: when (v) {
            is ConfigValue.Flag -> if (v.on) "On" else "Off"
            else -> return@mapNotNull null
        }
        label to text
    }
