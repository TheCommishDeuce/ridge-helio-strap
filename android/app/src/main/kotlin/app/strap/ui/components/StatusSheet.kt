package app.strap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Sick
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.strap.sync.DayStatus
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.MetricTone
import app.strap.ui.theme.RidgeType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val DayStatus.icon: ImageVector
    get() = when (this) {
        DayStatus.ACTIVE -> Icons.AutoMirrored.Rounded.DirectionsRun
        DayStatus.SICK -> Icons.Rounded.Sick
        DayStatus.INJURED -> Icons.Rounded.Healing
        DayStatus.BREAK -> Icons.Rounded.BeachAccess
    }

/** The prototype's colours: active green, sick amber, injured red, a break blue. */
@Composable
fun DayStatus.color(): Color {
    val r = LocalRidgeColors.current
    return when (this) {
        DayStatus.ACTIVE -> r.good
        DayStatus.SICK -> r.warn
        DayStatus.INJURED -> r.bad
        DayStatus.BREAK -> LocalMetricColors.current.strain
    }
}

/** The status's colour as a tone (a soft container, the colour on it), for card headers and journal rows. */
@Composable
fun DayStatus.tone(): MetricTone = color().let { MetricTone(it, it.copy(alpha = 0.2f), it) }

/** The day's status as a pill (its icon in its colour, the name, a chevron); tapping opens [StatusSheet]. */
@Composable
fun StatusChip(status: DayStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.cardSurface(CircleShape).clickable(onClick = onClick).padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconCircle(status.icon, status.color(), MaterialTheme.colorScheme.surface, size = 28.dp, iconSize = 16.dp)
        Text(status.label, style = RidgeType.label)
        Icon(Icons.Rounded.KeyboardArrowDown, "Change the day's status", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Picks [day]'s status (Bevel's activity status): four options, saved with Update. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(day: LocalDate, current: DayStatus, onDismiss: () -> Unit, onSave: (DayStatus) -> Unit) {
    var picked by remember { mutableStateOf(current) }
    val r = LocalRidgeColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = r.surface1, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Activity status", style = RidgeType.sheetTitle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayStatus.entries.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(r.card)
                            .selectable(s == picked, role = Role.RadioButton) { picked = s }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconCircle(s.icon, s.color(), MaterialTheme.colorScheme.surface, size = 34.dp, iconSize = 18.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(s.label, style = RidgeType.rowTitle)
                            Subtle(s.sub, style = RidgeType.caption)
                        }
                        RadioButton(s == picked, onClick = null, modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
            Button(onClick = { onSave(picked) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Text("Update " + day.format(DateTimeFormatter.ofPattern("d MMM")), style = RidgeType.label)
            }
            Subtle("The status is kept with the day and shows on the calendar and in Fitness.", Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                RidgeType.caption.merge(textAlign = TextAlign.Center))
        }
    }
}
