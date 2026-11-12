package ir.mmdkavir.smartattendance.ui

import ir.mmdkavir.smartattendance.i18n.t
import android.location.Location
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.mmdkavir.smartattendance.data.AppSettings

enum class Zone { OUTSIDE, APPROACHING, INSIDE }

fun zoneOf(vm: MainViewModel, s: AppSettings, loc: Location?): Zone {
    if (!s.hasLocation) return Zone.OUTSIDE
    if (loc == null) return if (s.inVisit) Zone.INSIDE else Zone.OUTSIDE
    val d = vm.distance(s, loc)
    return when {
        d <= s.radius -> Zone.INSIDE
        d <= maxOf(500.0, s.radius * 3.0) -> Zone.APPROACHING
        else -> Zone.OUTSIDE
    }
}

fun zoneColor(z: Zone): Color = when (z) {
    Zone.INSIDE -> StatusColors.ok
    Zone.APPROACHING -> StatusColors.warn
    Zone.OUTSIDE -> StatusColors.info
}

fun zoneLabel(z: Zone): String = when (z) {
    Zone.INSIDE -> t("Inside Workplace")
    Zone.APPROACHING -> t("Approaching Workplace")
    Zone.OUTSIDE -> t("Outside Workplace")
}

class PermissionActions(
    val requestBasic: () -> Unit,
    val requestBackground: () -> Unit,
    val openLocationSettings: () -> Unit,
    val enableLocation: () -> Unit,
    val openAppSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
    val openNotificationSettings: () -> Unit
)

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun Pill(text: String, color: Color, icon: ImageVector? = null) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f)
        )
    }
}

@Composable
fun PermRow(
    title: String,
    why: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (granted) StatusColors.ok else StatusColors.warn,
            modifier = Modifier
                .padding(top = 2.dp, end = 12.dp)
                .size(22.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!granted) {
                TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun PrimaryAction(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, shape = RoundedCornerShape(14.dp)) { Text(label) }
}
