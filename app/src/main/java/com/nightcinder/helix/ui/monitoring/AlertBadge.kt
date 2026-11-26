package com.nightcinder.helix.ui.monitoring

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.RoseError

/**
 * Compact alert counter pill for spatial Orbiters, navigation rails, and toolbar icons.
 * Hides cleanly when [count] <= 0.
 */
@Composable
fun AlertCountBadge(
    count: Int,
    hasCritical: Boolean,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return

    val label = if (count > 99) "99+" else count.toString()
    val containerColor = if (hasCritical) RoseError else AmberAccent
    val contentColor = if (hasCritical) Color.White else Color(0xFF1A1200)
    val accessibilityText = if (hasCritical) {
        "$count active alerts, critical severity present"
    } else {
        "$count active alerts"
    }

    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 18.dp)
            .semantics { contentDescription = accessibilityText },
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            ),
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
