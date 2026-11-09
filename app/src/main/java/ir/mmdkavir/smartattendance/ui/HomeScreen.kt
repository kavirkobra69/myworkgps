package ir.mmdkavir.smartattendance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.mmdkavir.smartattendance.util.Fmt
import org.osmdroid.util.GeoPoint

private data class StatusUi(
    val title: String,
    val subtitle: String,
    val color: Color,
    val icon: ImageVector,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

@Composable
fun HomeScreen(
    vm: MainViewModel,
    actions: PermissionActions,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s by vm.settings.collectAsState()
    val d by vm.device.collectAsState()
    val loc by vm.location.collectAsState()
    val records by vm.records.collectAsState()
    val pending by vm.pending.collectAsState()
    val testing by vm.testing.collectAsState()

    val status = vm.status(s, d, loc)
    val zone = zoneOf(vm, s, loc)
    val distance = loc?.let { vm.distance(s, it) }

    val ui: StatusUi = when {
        !s.monitoring -> StatusUi(
            "Monitoring Paused", "Automatic attendance detection is turned off.",
            StatusColors.warn, Icons.Default.PauseCircle, "Resume monitoring"
        ) { vm.setMonitoring(true) }

        status == GeoStatus.PERMISSION && !d.fine -> StatusUi(
            "Location Permission Required",
            "Smart Attendance needs location access to detect when you arrive at work.",
            StatusColors.error, Icons.Default.LocationOff, "Grant permission", actions.requestBasic
        )

        status == GeoStatus.PERMISSION -> StatusUi(
            "Location Permission Required",
            "Choose \"Allow all the time\" so arrival is detected even when the app is closed.",
            StatusColors.error, Icons.Default.LocationOff, "Allow all the time", actions.requestBackground
        )

        status == GeoStatus.GPS_OFF -> StatusUi(
            "GPS Disabled",
            "Android location services are off. Turn them on to resume monitoring.",
            StatusColors.error, Icons.Default.GpsOff, "Open Location Settings", actions.openLocationSettings
        )

        status == GeoStatus.TELEGRAM_ERROR -> StatusUi(
            "Telegram Connection Error",
            s.tgError.ifBlank { "Messages could not be delivered." } + " Unsent messages stay queued.",
            StatusColors.error, Icons.Default.CloudOff, "Test connection"
        ) { vm.testTelegram() }

        status == GeoStatus.INSIDE -> StatusUi(
            "Inside Workplace", "Monitoring Active. Your arrival is recorded for this visit.",
            StatusColors.ok, Icons.Default.CheckCircle
        )

        status == GeoStatus.OUTSIDE -> StatusUi(
            if (zone == Zone.APPROACHING) "Approaching Workplace" else "Outside Workplace",
            "Monitoring Active. Attendance is sent automatically when you arrive.",
            if (zone == Zone.APPROACHING) StatusColors.warn else StatusColors.info,
            Icons.Default.Place
        )

        else -> StatusUi(
            "Monitoring Active", "Waiting for a location fix. Arrival detection runs in the background.",
            StatusColors.ok, Icons.Default.Sensors
        )
    }

    val today = Fmt.date(System.currentTimeMillis())
    val todayRecord = records.firstOrNull { it.date == today }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("Welcome,", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.name.ifBlank { "User" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Workplace: ${s.workplace.ifBlank { "Not set" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AppCard(containerColor = ui.color.copy(alpha = 0.13f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ui.color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(ui.icon, contentDescription = null, tint = ui.color)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ui.color)
                    Text(ui.subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ui.actionLabel != null && ui.action != null) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = ui.action,
                    shape = RoundedCornerShape(14.dp),
                    enabled = !(testing && status == GeoStatus.TELEGRAM_ERROR)
                ) {
                    if (testing && status == GeoStatus.TELEGRAM_ERROR) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(ui.actionLabel)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Pill(zoneLabel(zone).substringBefore(" "), zoneColor(zone), Icons.Default.Place)
            Pill(
                if (d.gpsOn && d.fine) "GPS On" else "GPS Off",
                if (d.gpsOn && d.fine) StatusColors.ok else StatusColors.error,
                Icons.Default.GpsFixed
            )
            val tgText = when {
                !s.notifyEnabled -> "Telegram Off"
                s.tgStatus == 1 -> "Telegram OK"
                s.tgStatus == 2 -> "Telegram Error"
                else -> "Telegram Untested"
            }
            val tgColor = when {
                !s.notifyEnabled -> MaterialTheme.colorScheme.outline
                s.tgStatus == 1 -> StatusColors.ok
                s.tgStatus == 2 -> StatusColors.error
                else -> StatusColors.warn
            }
            Pill(tgText, tgColor, Icons.Default.Notifications)
        }

        AppCard {
            Text("Attendance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            InfoRow(
                "Today",
                if (todayRecord != null) "Arrived at ${todayRecord.time}" else "No arrival yet",
                if (todayRecord != null) StatusColors.ok else Color.Unspecified
            )
            InfoRow("Last arrival", if (s.lastArrival > 0) Fmt.dateTimeStyled(s.lastArrival, s.dateFormat) else "—")
            InfoRow("Geofence", "${s.radius} m radius")
            if (distance != null) {
                InfoRow("Distance to workplace", if (distance >= 1000) "%.1f km".format(distance / 1000) else "${distance.toInt()} m")
            }
            if (pending > 0) {
                InfoRow("Waiting to send", "$pending message(s)", StatusColors.warn)
            }
            if (!s.geoOk && s.geoError.isNotBlank() && s.monitoring) {
                Spacer(Modifier.height(4.dp))
                Text(s.geoError, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
            }
        }

        AppCard {
            Text("Map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            val workplace = if (s.hasLocation) GeoPoint(s.lat, s.lng) else null
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenMap() }
            ) {
                OsmMap(
                    modifier = Modifier.fillMaxSize(),
                    workplace = workplace,
                    radius = s.radius,
                    workplaceName = s.workplace,
                    user = loc,
                    zone = zone,
                    focus = workplace,
                    focusToken = 0,
                    interactive = false,
                    zoom = 16.0
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
