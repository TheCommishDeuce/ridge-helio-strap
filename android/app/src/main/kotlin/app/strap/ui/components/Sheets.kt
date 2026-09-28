package app.strap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.launch

/** The copy behind an info action: a title, optional two-column rows, then paragraphs. */
data class Info(val title: String, val paras: List<String>, val rows: List<Pair<String, String>> = emptyList())

/** Opens an info sheet; provided by the app shell. */
val LocalInfo = staticCompositionLocalOf<(Info) -> Unit> { {} }

/** Shows a snackbar (message, optional action label and its handler); provided by the app shell. */
val LocalSnackbar = staticCompositionLocalOf<(String, String?, (() -> Unit)?) -> Unit> { { _, _, _ -> } }

/** The app's existing explanatory copy, moved off the screens into sheets (verbatim). */
object Infos {
    val today = Info("Today", listOf(
        "Readiness now is your recovery reduced by today's strain so far.",
        "Strain is today's load on your own scale: 21 is your hardest day of the last 90.",
        "Sleep is the strap's own 0–100 score. Ridge computes no composite sleep score of its own.",
    ))
    val stress = Info("Stress", listOf("The strap's own 0–100 index."))
    val health = Info(
        "Sleep health",
        listOf(
            "No single sleep score is validated, so each dimension stands on its own (no_validated_sleep_score).",
            "Stages come from the strap's own staging; wrist sleep staging agrees with lab sleep studies only moderately (wearable_sleep_stage_validity).",
        ),
        listOf("Duration 7–9 h" to "NSF 2015", "Efficiency ≥ 85%" to "AASM", "Midpoint 02:00–04:00" to "Buysse 2014", "Regularity SRI ≥ 70" to "Phillips 2017"),
    )
    fun debt(nights: Int) = Info("Sleep debt", listOf(
        "Over the last $nights nights: shortfall against your need, with extra sleep repaying it at half value.",
        "Your need comes from NSF 2015, by age.",
    ))
    val recovery = Info("Recovery", listOf(
        "The recovery score is an evidence-weighted estimate, not a validated formula, so it is always shown with its parts.",
        "Parts are compared with your own last 42 days. The weights reflect how strong the evidence is for each marker; no study fits them (recovery_readiness).",
    ))
    val active = Info("Active minutes", listOf("Moderate + 2 × vigorous minutes from step cadence (WHO counts a vigorous minute double); guideline 150 a week."))
    val load = Info("Load", listOf("Daily cardio load (Banister TRIMP over waking minutes) — the number strain is scaled from."))
    fun strain(hrmax: Int?, rhr: Int?) = Info(
        "Strain",
        listOf(
            "Today's load on your own scale: 21 is your hardest day of the last 90 (their 95th percentile).",
            "Zones are shares of your max heart rate.",
        ),
        listOfNotNull(hrmax?.let { "Max HR" to "$it bpm (Tanaka 208 − 0.7 × age)" }, rhr?.let { "Resting" to "$it bpm" }),
    )
    val vo2 = Info("VO₂max", listOf("Estimated from your profile, activity answer and resting heart rate (Jurca 2005)."))
    val detailSteps = Info("Reading the chart", listOf("Press and drag on the chart to read any bar. Day shows steps per hour; Week and Month show each day's total."))
    val detail = Info("Reading the chart", listOf(
        "Press and drag on the chart to read any point. Bars show each period's lowest to highest reading; the dot is its average.",
        "Shaded: asleep.",
    ))
    val journal = Info("Journal", listOf("Caffeine and alcohol times feed your personal cut-off analysis once there are enough nights. Weight updates calories and VO₂max from the day it was logged."))
    val alarms = Info("Alarms", listOf(
        "These alarms live on the strap and vibrate it. Changes are written to the strap straight away.",
        "The strap holds up to 10 alarms.",
    ))
    val strapSettings = Info("Strap settings", listOf("Read from the strap. Changing them here comes later."))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(info: Info, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = LocalRidgeColors.current.surface1,
        scrimColor = Color.Black.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(vertical = 16.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(muted.copy(alpha = 0.5f)))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(info.title, style = RidgeType.sheetTitle, modifier = Modifier.padding(bottom = 4.dp))
            info.rows.forEach { (a, b) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(a, style = RidgeType.paragraph.copy(fontWeight = RidgeType.label.fontWeight), modifier = Modifier.weight(1f))
                    Text(b, style = RidgeType.paragraph, color = muted, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
                }
            }
            info.paras.forEach { Text(it, style = RidgeType.paragraph, color = muted) }
            Button(
                onClick = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } },
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp).height(48.dp),
            ) { Text("Got it", style = RidgeType.label) }
        }
    }
}
