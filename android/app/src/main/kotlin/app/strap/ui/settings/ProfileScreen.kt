package app.strap.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.strap.sync.Owner
import app.strap.ui.components.GroupHeader
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.Subtle
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import strap.science.Profile
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Jurca 2005's activity question, the NASA arm's own wording (Table 1), level 0 to 4. */
private val ACTIVITY = listOf(
    "Little activity other than walking for pleasure",
    "Some regular participation in modest physical activities, sports or recreation",
    "Aerobic exercise such as running or brisk walking for 20 to 60 minutes a week",
    "Aerobic exercise such as running or brisk walking for 1 to 3 hours a week",
    "Aerobic exercise such as running or brisk walking for more than 3 hours a week",
)

/**
 * The profile the phone owns (D34 S3): what the science needs that the strap cannot measure.
 * Saved on the phone, re-derived at once ([onSaved]), uploaded with the next sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(owner: Owner, onSaved: () -> Unit) {
    val saved = remember { owner.profile() }
    var dob by remember { mutableStateOf(saved?.dob) }
    var sex by remember { mutableStateOf(saved?.sex) }
    var height by remember { mutableStateOf(saved?.heightCm?.let { "%.0f".format(it) } ?: "") }
    var srpa by remember { mutableStateOf(saved?.srpa) }
    var picking by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val heightCm = height.replace(',', '.').toFloatOrNull()?.takeIf { it in 100f..250f }
    val changed = Profile(heightCm, sex, dob, srpa) != saved
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Subtle("Ridge measures everything else from the strap. These four it has to ask. They stay on this phone; the nightly backup copies them to your server.",
            Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        GroupHeader("Date of birth", "Sets your sleep need, maximum heart rate and the VO₂max estimate.")
        RidgeCard {
            OutlinedButton(onClick = { picking = true }) { Text(dob?.format(DateTimeFormatter.ofPattern("d MMMM yyyy")) ?: "Choose a date") }
        }
        GroupHeader("Sex", "The calorie, training-load and VO₂max formulas differ by sex.")
        RidgeCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("male" to "Male", "female" to "Female").forEach { (value, label) ->
                    FilterChip(selected = sex == value, onClick = { sex = value }, label = { Text(label) })
                }
            }
        }
        GroupHeader("Height", "With your weight: calories, distance from steps, and the VO₂max estimate.")
        RidgeCard {
            OutlinedTextField(height, { height = it }, label = { Text("cm") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = height.isNotEmpty() && heightCm == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        GroupHeader("Activity in a typical week", "Only the VO₂max estimate uses it. A step count cannot tell training from getting around, so it asks. Answer for your ordinary week, not your best one.")
        RidgeCard(spacing = 2.dp) {
            ACTIVITY.forEachIndexed { level, text ->
                Row(Modifier.fillMaxWidth().clickable { srpa = level }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = srpa == level, onClick = { srpa = level })
                    Text(text, style = RidgeType.body, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Button(enabled = changed && (height.isEmpty() || heightCm != null), modifier = Modifier.padding(top = 8.dp), onClick = {
            owner.setProfile(Profile(heightCm, sex, dob, srpa))
            done = true
            onSaved()
        }) { Text("Save") }
        if (done && !changed) Text("Saved. Your numbers are being worked out again.", style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (picking) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (dob ?: today.minusYears(35)).toEpochDay() * DAY_MS,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.minusYears(12).toEpochDay() * DAY_MS
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { dob = LocalDate.ofEpochDay(it / DAY_MS) }; picking = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            colors = DatePickerDefaults.colors(containerColor = LocalRidgeColors.current.surface3),
        ) { DatePicker(state, colors = DatePickerDefaults.colors(containerColor = LocalRidgeColors.current.surface3)) }
    }
}

private const val DAY_MS = 86_400_000L
