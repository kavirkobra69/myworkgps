package ir.mmdkavir.smartattendance.ui

import ir.mmdkavir.smartattendance.i18n.t
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.osmdroid.util.GeoPoint

@Composable
fun MapScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val loc by vm.location.collectAsState()

    val workplace = if (s.hasLocation) GeoPoint(s.lat, s.lng) else null
    val zone = zoneOf(vm, s, loc)
    val distance = loc?.let { vm.distance(s, it) }

    var focus by remember { mutableStateOf(workplace) }
    var token by remember { mutableIntStateOf(0) }
    LaunchedEffect(loc) {
        val l = loc
        if (focus == null && l != null) {
            focus = GeoPoint(l.latitude, l.longitude)
            token++
        }
    }

    Box(modifier.fillMaxSize()) {
        OsmMap(
            modifier = Modifier.fillMaxSize(),
            workplace = workplace,
            radius = s.radius,
            workplaceName = s.workplace,
            user = loc,
            zone = zone,
            focus = focus,
            focusToken = token,
            zoom = 17.0
        )

        Card(
            Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    s.workplace.ifBlank { t("Workplace") },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Pill(zoneLabel(zone), zoneColor(zone), Icons.Default.Place)
                    Text(
                        when {
                            distance == null -> t("Waiting for GPS…")
                            distance >= 1000 -> t("%.1f km away", distance / 1000)
                            else -> t("%s m away", distance.toInt())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    t("Radius %s m · Blue: outside · Orange: approaching · Green: inside", s.radius),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            SmallFloatingActionButton(onClick = {
                if (workplace != null) {
                    focus = workplace
                    token++
                }
            }) {
                Icon(Icons.Default.Place, contentDescription = t("Center on workplace"))
            }
            FloatingActionButton(onClick = {
                val l = loc
                if (l != null) {
                    focus = GeoPoint(l.latitude, l.longitude)
                    token++
                } else {
                    vm.toast(t("Waiting for a GPS fix. Check location permission and GPS."))
                }
            }) {
                Icon(Icons.Default.MyLocation, contentDescription = t("Center on my location"))
            }
        }
    }
}
