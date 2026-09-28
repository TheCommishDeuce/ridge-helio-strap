package app.strap.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType

/**
 * The connected button group (replaces SegmentedButton): 40 dp buttons 2 dp apart, outer
 * corners 20 and inner 8; the selected one rounds fully and fills with `primary`.
 */
@Composable
fun <T> ConnectedButtons(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val idle = LocalRidgeColors.current.surface3
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = o == selected
            val start by animateDpAsState(if (on || i == 0) 20.dp else 8.dp, tween(200))
            val end by animateDpAsState(if (on || i == options.lastIndex) 20.dp else 8.dp, tween(200))
            val bg by animateColorAsState(if (on) scheme.primary else idle, tween(200))
            val fg by animateColorAsState(if (on) scheme.onPrimary else scheme.onSurfaceVariant, tween(200))
            Box(
                Modifier.weight(1f).height(40.dp)
                    .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                    .background(bg)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(o) }),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = RidgeType.label, color = fg) }
        }
    }
}
