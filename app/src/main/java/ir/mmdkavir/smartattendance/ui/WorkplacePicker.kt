package ir.mmdkavir.smartattendance.ui

import ir.mmdkavir.smartattendance.i18n.t
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ir.mmdkavir.smartattendance.net.Nominatim
import ir.mmdkavir.smartattendance.net.Place
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import kotlin.math.roundToInt

@Composable
fun WorkplacePicker(
    vm: MainViewModel,
    lat: Double?,
    lng: Double?,
    radius: Int,
    onChange: (Double?, Double?, Int) -> Unit,
    onNeedPermission: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val loc by vm.location.collectAsState()
    val device by vm.device.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var focus by remember {
        mutableStateOf<GeoPoint?>(if (lat != null && lng != null) GeoPoint(lat, lng) else null)
    }
    var token by remember { mutableIntStateOf(0) }
    var centeredOnUser by remember { mutableStateOf(lat != null) }

    LaunchedEffect(loc) {
        val l = loc
        if (!centeredOnUser && l != null) {
            focus = GeoPoint(l.latitude, l.longitude)
            token++
            centeredOnUser = true
        }
    }

    fun search() {
        val q = query.trim()
        if (q.length < 2) return
        scope.launch {
            searching = true
            results = Nominatim.search(q)
            searching = false
            if (results.isEmpty()) vm.toast(t("No results found. Check the name or your connection."))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(t("Search workplace or address")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { search() }) {
                        Icon(Icons.Default.Search, contentDescription = t("Search"))
                    }
                }
            }
        )

        if (results.isNotEmpty()) {
            Column {
                results.take(5).forEachIndexed { i, p ->
                    Text(
                        p.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onChange(p.lat, p.lon, radius)
                                focus = GeoPoint(p.lat, p.lon)
                                token++
                                results = emptyList()
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                    )
                    if (i < minOf(results.size, 5) - 1) HorizontalDivider()
                }
            }
        }

        OsmMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(16.dp)),
            workplace = if (lat != null && lng != null) GeoPoint(lat, lng) else null,
            radius = radius,
            workplaceName = t("Workplace"),
            user = loc,
            zone = Zone.OUTSIDE,
            focus = focus,
            focusToken = token,
            onTap = { a, b -> onChange(a, b, radius) },
            zoom = if (lat != null) 17.0 else 14.0
        )

        Text(
            if (lat != null && lng != null) {
                t("Selected: %s, %s", String.format(java.util.Locale.US, "%.6f", lat), String.format(java.util.Locale.US, "%.6f", lng))
            } else {
                t("Tap the map, search, or use your current location to set the workplace.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                if (!device.fine) {
                    onNeedPermission()
                } else {
                    scope.launch {
                        locating = true
                        val fix = vm.fetchCurrent()
                        locating = false
                        if (fix != null) {
                            onChange(fix.latitude, fix.longitude, radius)
                            focus = GeoPoint(fix.latitude, fix.longitude)
                            token++
                        } else {
                            vm.toast(t("Could not get a GPS fix. Enable GPS and try near a window or outdoors."))
                        }
                    }
                }
            },
            enabled = !locating,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (locating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.padding(start = 8.dp))
            Text(if (locating) t("Getting location…") else t("Use my current location"))
        }

        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t("Geofence radius"), style = MaterialTheme.typography.titleSmall)
                Text(t("%s m", radius), style = MaterialTheme.typography.titleSmall)
            }
            Slider(
                value = radius.toFloat(),
                onValueChange = { onChange(lat, lng, ((it / 10f).roundToInt() * 10).coerceIn(50, 1000)) },
                valueRange = 50f..1000f
            )
            Text(
                if (radius < 100) {
                    t("Small radii can be missed by Android. 100 m or more is recommended.")
                } else {
                    t("Default is 100 m. Allowed range: 50 to 1000 m.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
