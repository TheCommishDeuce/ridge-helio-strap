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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import app.strap.StrapApp
import app.strap.ui.shell.Tab
import app.strap.ui.today.zoneName

/**
 * Today at a glance (D39): Recovery and Energy on the small size; with last night and steps on
 * the wide; with Energy through the day on the large. Opens Today.
 */
class TodayWidget : GlanceAppWidget() {
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
        WidgetCard(openTab(context, Tab.TODAY)) {
            Column(GlanceModifier.fillMaxSize()) {
                TopRow(context, "Today", s.line, words = wide)
                // Large: the figures sit under the title and the chart takes the rest; smaller, they sit at the bottom.
                Spacer(if (large) GlanceModifier.height(14.dp) else GlanceModifier.defaultWeight())
                val recovery = @Composable { m: GlanceModifier, big: Boolean ->
                    Metric(s.recovery?.let { "$it%" } ?: "—", if (big) "Recovery" else s.recovery?.let { zoneName(it.toDouble()).substringBefore(' ') } ?: "Recovery",
                        s.recovery?.let(WidgetColors::recovery) ?: WidgetColors.muted, if (big) 28.sp else 22.sp, m)
                }
                val energy = @Composable { m: GlanceModifier, big: Boolean ->
                    Metric(s.energy?.toString() ?: "—", "Energy", WidgetColors.energy, if (big) 28.sp else 22.sp, m)
                }
                if (!wide) {
                    Row(GlanceModifier.fillMaxWidth()) {
                        recovery(GlanceModifier.defaultWeight(), true)
                        energy(GlanceModifier.defaultWeight(), true)
                    }
                } else {
                    Row(GlanceModifier.fillMaxWidth()) {
                        recovery(GlanceModifier.defaultWeight(), false)
                        energy(GlanceModifier.defaultWeight(), false)
                        Metric(s.asleepMin?.let(::hm) ?: "—", s.sleepTitle ?: "Last night", WidgetColors.sleep, 22.sp, GlanceModifier.defaultWeight())
                        Metric(s.steps?.let { "%,d".format(it) } ?: "—", "Steps", WidgetColors.steps, 22.sp, GlanceModifier.defaultWeight())
                    }
                }
                if (large) {
                    Label("Energy through the day", modifier = GlanceModifier.padding(top = 16.dp, bottom = 6.dp))
                    val w = px(context, size.width - 32.dp)
                    val h = px(context, 64.dp)
                    Drawing(energyBitmap(s.curve, s.dayStart, s.dayEnd, w, h, px(context, 2.dp).toFloat()),
                        s.energy?.let { "Energy through the day, now $it" } ?: "No Energy yet today", WidgetColors.energy,
                        GlanceModifier.fillMaxWidth().defaultWeight())
                }
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
