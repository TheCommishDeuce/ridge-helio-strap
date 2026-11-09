package app.strap.ui.strap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.GroupHeader
import app.strap.ui.components.Grouped
import app.strap.ui.components.Infos
import app.strap.ui.components.ListRow
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.Subtle
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import strap.protocol.parse.Config
import strap.protocol.parse.ConfigGroup
import strap.protocol.parse.ConfigValue

private val Title = RidgeType.body.copy(fontSize = 15.sp)

/**
 * The strap's settings (issue #4, public issue #5), grouped by what they are about. Each kind of
 * value has its own control: a switch for on/off, a row of buttons for a few values, a list behind
 * a row with an arrow for many, plain text for what cannot be changed. [onChange] gets the settings
 * group (0 health, 1 workout) and the values to write; the page writes them and reads the group back.
 */
@Composable
internal fun StrapSettingsSections(health: ConfigGroup?, workout: ConfigGroup?, busy: Boolean, onChange: (Int, Map<Int, ConfigValue>) -> Unit) {
    var picking by remember { mutableStateOf<StrapSetting?>(null) }
    val sections = strapSections(health, workout)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupHeader("Strap settings", "A change goes to the strap at once", Infos.strapSettings)
        if (sections.isEmpty()) RidgeCard { Subtle(if (health == null && workout == null) "The strap did not send its settings." else "The strap reported none of the settings we know.") }
        sections.forEach { section ->
            Subtle(section.title, Modifier.padding(start = 4.dp, top = 8.dp), RidgeType.label)
            Grouped(section.rows) { row, shape ->
                when (row.control) {
                    Control.SWITCH -> ListRow(shape, row.label, row.hint, headlineStyle = Title, trailing = {
                        Switch(checked = row.text == "On", enabled = !busy, onCheckedChange = { on ->
                            onChange(row.group, mapOf(row.key to when (val v = row.value) {
                                is ConfigValue.Choices -> Config.Workout.detection(v, on)
                                else -> ConfigValue.Flag(on)
                            }))
                        })
                    })
                    Control.BUTTONS -> ButtonsRow(shape, row, busy) { o -> onChange(row.group, mapOf(row.key to (row.value as ConfigValue.Choice).copy(value = o))) }
                    Control.LIST -> ListRow(shape, row.label, row.hint, onClick = { picking = row }, enabled = !busy, headlineStyle = Title, trailing = {
                        Text(row.text, style = RidgeType.body, color = MaterialTheme.colorScheme.primary)
                        Icon(Icons.Rounded.UnfoldMore, "Choose", Modifier.padding(start = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    })
                    Control.READ_ONLY -> ListRow(shape, row.label, row.hint ?: row.text.takeIf { it.length > 16 }, headlineStyle = Title, trailing = {
                        if (row.hint != null || row.text.length <= 16) Text(row.text, style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Rounded.Lock, "Read-only", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    })
                }
            }
        }
        if (listOfNotNull(health, workout).any { !it.complete }) Subtle("Some settings could not be read.", Modifier.padding(horizontal = 4.dp), RidgeType.caption)
    }
    picking?.let { row ->
        val choice = row.value as ConfigValue.Choice
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(row.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    row.hint?.let { Subtle(it, Modifier.padding(bottom = 8.dp)) }
                    choice.options.forEach { o ->
                        Row(Modifier.fillMaxWidth().clickable {
                            picking = null
                            if (o != choice.value) onChange(row.group, mapOf(row.key to choice.copy(value = o)))
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = o == choice.value, onClick = null)
                            Text(choiceName(row.group, row.key, o), style = RidgeType.rowTitle, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}

/** A setting with a few values: its label and hint over one button per value. */
@Composable
private fun ButtonsRow(shape: Shape, row: StrapSetting, busy: Boolean, onPick: (Int) -> Unit) {
    val choice = row.value as ConfigValue.Choice
    Column(
        Modifier.fillMaxWidth().clip(shape).background(LocalRidgeColors.current.card).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(row.label, style = Title)
            row.hint?.let { Subtle(it) }
        }
        ConnectedButtons(choice.options, choice.value, { buttonName(row.group, row.key, it) }, Modifier.alpha(if (busy) 0.5f else 1f)) { o ->
            if (!busy && o != choice.value) onPick(o)
        }
    }
}
