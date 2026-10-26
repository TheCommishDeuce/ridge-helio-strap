package app.strap.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.TextAlign
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.strap.StrapApp
import app.strap.ui.shell.Tab
import java.time.format.TextStyle as DayStyle

/**
 * Steps as of the last sync (D39; live steps run only while Today is open, D34) against
 * [STEPS_GOAL]; the wide size adds distance and when they were counted, the large the week.
 * Opens Fitness.
 */
class StepsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = loadSnapshot(context.applicationContext as StrapApp)
        provideContent { Content(snapshot) }
    }

    @Composable
    private fun Content(s: WidgetSnapshot) {
        val context = LocalContext.current
        val size = LocalSize.current
        val wide = size.width >= WIDE.width
        val large = wide && size.height >= LARGE.height
        val steps = s.steps
        WidgetCard(openTab(context, Tab.ACTIVITY)) {
            Column(GlanceModifier.fillMaxSize()) {
                TopRow(context, "Steps", s.line, words = wide)
                Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(GlanceModifier.defaultWeight()) {
                        Figure(steps?.let { "%,d".format(it) } ?: "—", WidgetColors.steps, 28.sp)
                        Label(if (steps != null && steps >= STEPS_GOAL) "Goal met" else "of %,d".format(STEPS_GOAL))
                    }
                    if (wide) Column(GlanceModifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                        Figure(s.distanceM?.let { "%.1f km".format(it / 1000) } ?: "—", WidgetColors.text, 17.sp)
                        // The count is the strap's as of the last sync; say when that was unless the top row already does.
                        Label(if (s.line is StrapLine.Stale) "distance" else syncedWords(s.lastSync).replace("Synced", "as of"))
                    }
                }
                LinearProgressIndicator(
                    progress = ((steps ?: 0).toFloat() / STEPS_GOAL).coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp).padding(top = 0.dp),
                    color = WidgetColors.steps, backgroundColor = WidgetColors.track,
                )
                if (large) {
                    Spacer(GlanceModifier.height(12.dp))
                    val w = px(context, size.width - 32.dp)
                    val h = px(context, 56.dp)
                    Drawing(weekBitmap(s.week.map { it.second }, STEPS_GOAL.toDouble(), w, h, px(context, 8.dp).toFloat()),
                        "The last seven days' steps against ${"%,d".format(STEPS_GOAL)}", WidgetColors.steps, GlanceModifier.fillMaxWidth().defaultWeight())
                    Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
                        for ((day, _) in s.week) {
                            Text(day.dayOfWeek.getDisplayName(DayStyle.NARROW, context.resources.configuration.locales[0]), GlanceModifier.defaultWeight(),
                                style = TextStyle(color = WidgetColors.muted, fontSize = 11.sp, textAlign = TextAlign.Center))
                        }
                    }
                }
            }
        }
    }
}

class StepsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepsWidget()
}
