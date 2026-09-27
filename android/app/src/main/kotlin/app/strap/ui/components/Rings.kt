package app.strap.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalMetricColors

/**
 * One metric ring. [fraction] null means withheld: the track is drawn empty and the centre
 * says "—", never a zero that looks like a measurement.
 */
@Composable
fun MetricRing(label: String, value: String, fraction: Float?, color: Color, size: Dp = 104.dp, caption: String? = null, onClick: (() -> Unit)? = null) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(fraction) { progress.animateTo((fraction ?: 0f).coerceIn(0f, 1f), tween(900)) }
    val track = LocalMetricColors.current.track
    Column(
        Modifier.let { if (onClick != null) it.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(6.dp) else it },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = Stroke(width = this.size.minDimension * 0.1f, cap = StrokeCap.Round)
                val inset = stroke.width / 2
                val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke.width, this.size.height - stroke.width)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(track, -90f, 360f, false, topLeft, arcSize, style = stroke)
                if (fraction != null) drawArc(color, -90f, 360f * progress.value, false, topLeft, arcSize, style = stroke)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
