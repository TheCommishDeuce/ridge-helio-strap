package app.strap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import java.time.LocalDate

/** A daily metric large: 7, 14 or 30 days ending on [day] as bars, the day's value and the period's average. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyChartSheet(title: String, unit: String, series: Map<LocalDate, Double>, day: LocalDate, color: Color, format: (Double) -> String, onDismiss: () -> Unit) {
    var days by rememberSaveable { mutableIntStateOf(30) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalRidgeColors.current.surface1, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = RidgeType.sheetTitle)
            val window = series.filterKeys { it <= day && it > day.minusDays(days.toLong()) }
            CardValue(series[day]?.let(format) ?: "—", unit,
                window.values.takeIf { it.isNotEmpty() }?.average()?.let { Note("$days-day average ${format(it)}", MaterialTheme.colorScheme.onSurfaceVariant) })
            ConnectedButtons(listOf(7, 14, 30), days, { "${it}D" }) { days = it }
            DailyBars(series, day.minusDays(days - 1L), day, color, { "${format(it)} $unit" }, highlightLast = true, height = 150.dp)
        }
    }
}
