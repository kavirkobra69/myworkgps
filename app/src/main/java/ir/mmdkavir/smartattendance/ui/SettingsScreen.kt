package ir.mmdkavir.smartattendance.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ir.mmdkavir.smartattendance.BuildConfig
import kotlin.math.roundToInt

private class Field(
    val label: String,
    val initial: String,
    val secret: Boolean = false,
    val keyboard: KeyboardType = KeyboardType.Text,
    val helper: String? = null
)

@Composable
private fun EditDialog(
    title: String,
    fields: List<Field>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val values = remember { mutableStateListOf(*fields.map { it.initial }.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fields.forEachIndexed { i, f ->
                    val helper = f.helper
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        label = { Text(f.label) },
                        singleLine = true,
                        visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (f.secret) KeyboardType.Password else f.keyboard),
                        supportingText = if (helper != null) {
                            { Text(helper) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(values.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private const val GUIDE = """Android can stop background monitoring in these situations. Here is how to recover from each one.

1. Battery optimization / manufacturer restrictions
Xiaomi, Huawei, Oppo, Vivo, Samsung and others may stop apps in the background.
• Open "Battery optimization" below and set Smart Attendance to "Not optimized" / "Unrestricted".
• In the system Settings → Apps → Smart Attendance → Battery, choose "Unrestricted" (Samsung: "Never sleeping apps").
• Xiaomi/Oppo/Vivo: enable "Autostart" and lock the app in the recent-apps screen.
• More details per brand: dontkillmyapp.com

2. Force stop
If you use "Force stop" on the app, Android disables all its background work until you open the app again. Open Smart Attendance once and monitoring is restored.

3. Revoked permissions
If location is changed to "While using the app" or removed, arrival cannot be detected. Open Settings → Background Location and choose "Allow all the time".
Android may also remove permissions from apps that are not used for months. Turn off "Remove permissions if app isn't used" for this app.

4. Location services off
Android cannot let an app turn GPS on silently. If GPS is off you will see a warning and a notification. Use "Open Location Settings" and enable Location. Monitoring resumes automatically within about 15 minutes, or immediately when you open the app.

5. Device shutdown or restart
Geofences are cleared when the phone shuts down. After boot the app restores monitoring by itself. If your phone blocks auto-start, open the app once after restarting.

6. Google Play services
Geofencing needs Google Play services. Without it, the app falls back to checking your location about every 15 minutes, which is less precise."""

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    actions: PermissionActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val s by vm.settings.collectAsState()
    val d by vm.device.collectAsState()
    val testing by vm.testing.collectAsState()

    var dialog by remember { mutableStateOf<String?>(null) }
    var radius by remember(s.radius) { mutableStateOf(s.radius.toFloat()) }
    var dwell by remember(s.dwellSec) { mutableStateOf(s.dwellSec.toFloat()) }

    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val batteryOk = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp)
        )

        AppCard {
            Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            InfoRow("Full name", s.name.ifBlank { "—" })
            InfoRow("Employee ID", s.employeeId.ifBlank { "—" })
            InfoRow("Workplace", s.workplace.ifBlank { "—" })
            OutlinedButton(onClick = { dialog = "profile" }, shape = RoundedCornerShape(14.dp)) { Text("Edit profile") }
        }

        AppCard {
            Text("Workplace & geofence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            InfoRow(
                "Location",
                if (s.hasLocation) "${"%.5f".format(s.lat)}, ${"%.5f".format(s.lng)}" else "Not set"
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Geofence radius", style = MaterialTheme.typography.bodyMedium)
                Text("${radius.roundToInt()} m", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Slider(
                value = radius,
                onValueChange = { radius = (it / 10f).roundToInt() * 10f },
                onValueChangeFinished = { vm.saveRadius(radius.roundToInt()) },
                valueRange = 50f..1000f
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Stay time before recording", style = MaterialTheme.typography.bodyMedium)
                Text("${dwell.roundToInt()} s", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Slider(
                value = dwell,
                onValueChange = { dwell = (it / 10f).roundToInt() * 10f },
                onValueChangeFinished = { vm.saveDwell(dwell.roundToInt()) },
                valueRange = 0f..300f
            )
            Text(
                "A short stay time prevents false arrivals from GPS drift. The recorded arrival time is still when you entered the area.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { dialog = "workplace" }, shape = RoundedCornerShape(14.dp)) {
                Text("Change workplace location")
            }
        }

        AppCard {
            Text("GPS & background monitoring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ToggleRow(
                "Automatic attendance",
                "Detect arrival in the background",
                s.monitoring
            ) { on ->
                vm.setMonitoring(on)
                if (on) {
                    if (!d.fine) actions.requestBasic()
                    else if (!d.background) actions.requestBackground()
                    else if (!batteryOk) actions.openBatterySettings()
                }
            }
            InfoRow("GPS (location services)", if (d.gpsOn) "On" else "Off", if (d.gpsOn) StatusColors.ok else StatusColors.error)
            InfoRow("Precise location", if (d.fine) "Granted" else "Missing", if (d.fine) StatusColors.ok else StatusColors.error)
            InfoRow(
                "Background location",
                if (d.background) "Allowed all the time" else "Not allowed",
                if (d.background) StatusColors.ok else StatusColors.error
            )
            InfoRow(
                "Geofence",
                if (s.geoOk) "Registered" else "Not registered",
                if (s.geoOk) StatusColors.ok else StatusColors.warn
            )
            if (!s.geoOk && s.geoError.isNotBlank()) {
                Text(s.geoError, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!d.gpsOn) {
                    Button(onClick = actions.openLocationSettings, shape = RoundedCornerShape(14.dp)) { Text("Location settings") }
                }
                if (!d.fine) {
                    Button(onClick = actions.requestBasic, shape = RoundedCornerShape(14.dp)) { Text("Grant access") }
                } else if (!d.background) {
                    Button(onClick = actions.requestBackground, shape = RoundedCornerShape(14.dp)) { Text("Allow all the time") }
                }
            }
        }

        GitHubCard(vm)

        TelegramCard(vm)

        MessageFormatCard(vm)

        AppCard {
            Text("Notifications & battery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            InfoRow(
                "App notifications",
                if (d.notifications) "Allowed" else "Blocked",
                if (d.notifications) StatusColors.ok else StatusColors.warn
            )
            InfoRow(
                "Battery optimization",
                if (batteryOk) "Not optimized (good)" else "Optimized (may stop monitoring)",
                if (batteryOk) StatusColors.ok else StatusColors.warn
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.openNotificationSettings, shape = RoundedCornerShape(14.dp)) { Text("Notifications") }
                OutlinedButton(onClick = actions.openBatterySettings, shape = RoundedCornerShape(14.dp)) { Text("Battery") }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { dialog = "guide" }) { Text("Battery optimization & recovery guide") }
        }

        UpdateCard(vm)

        AppCard {
            Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            InfoRow("Version", BuildConfig.VERSION_NAME)
            InfoRow("Maps", "© OpenStreetMap contributors")
            Text(
                "Attendance data stays on this phone. Only the attendance message is sent, by your own GitHub Actions workflow, to your own Telegram chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    when (dialog) {
        "profile" -> EditDialog(
            "Edit profile",
            listOf(
                Field("Full name", s.name),
                Field("Employee ID (optional)", s.employeeId),
                Field("Workplace name", s.workplace)
            ),
            onDismiss = { dialog = null }
        ) { v ->
            if (v[0].isBlank() || v[2].isBlank()) {
                vm.toast("Name and workplace name are required")
            } else {
                vm.saveProfile(v[0], v[1], v[2])
                dialog = null
            }
        }

        "guide" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Reliability guide") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(GUIDE, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = { actions.openBatterySettings() }) { Text("Battery settings") }
            }
        )

        "workplace" -> WorkplaceDialog(vm, actions, onClose = { dialog = null })
    }
}

@Composable
private fun WorkplaceDialog(vm: MainViewModel, actions: PermissionActions, onClose: () -> Unit) {
    val s by vm.settings.collectAsState()
    var lat by remember { mutableStateOf<Double?>(if (s.hasLocation) s.lat else null) }
    var lng by remember { mutableStateOf<Double?>(if (s.hasLocation) s.lng else null) }
    var radius by remember { mutableStateOf(s.radius) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    Text(
                        "Workplace location",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val a = lat
                            val b = lng
                            if (a != null && b != null) {
                                vm.saveWorkplace(a, b, radius)
                                onClose()
                            } else {
                                vm.toast("Select a location first")
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    WorkplacePicker(
                        vm = vm,
                        lat = lat,
                        lng = lng,
                        radius = radius,
                        onChange = { a, b, r ->
                            lat = a
                            lng = b
                            radius = r
                        },
                        onNeedPermission = actions.requestBasic
                    )
                }
            }
        }
    }
}
