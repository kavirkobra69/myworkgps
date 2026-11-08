package ir.mmdkavir.smartattendance.ui

import android.os.Build
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val STEP_TITLES = listOf("Your details", "Workplace location", "Telegram", "Permissions")

@Composable
fun SetupWizard(vm: MainViewModel, actions: PermissionActions, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val d by vm.device.collectAsState()
    val testing by vm.testing.collectAsState()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(s.name) }
    var emp by rememberSaveable { mutableStateOf(s.employeeId) }
    var place by rememberSaveable { mutableStateOf(s.workplace) }
    var lat by rememberSaveable { mutableStateOf<Double?>(if (s.hasLocation) s.lat else null) }
    var lng by rememberSaveable { mutableStateOf<Double?>(if (s.hasLocation) s.lng else null) }
    var radius by rememberSaveable { mutableIntStateOf(s.radius) }
    var chat by rememberSaveable { mutableStateOf(s.chatId) }
    var url by rememberSaveable { mutableStateOf(s.workerUrl) }
    var key by rememberSaveable { mutableStateOf("") }
    var autoSend by rememberSaveable { mutableStateOf(true) }

    val telegramReady = chat.isNotBlank() && url.trim().startsWith("https://") && (key.isNotBlank() || s.hasAppKey)

    val canNext = when (step) {
        0 -> name.isNotBlank() && place.isNotBlank()
        1 -> lat != null && lng != null
        2 -> !autoSend || telegramReady
        else -> true
    }

    fun saveTelegram() {
        vm.saveTelegram(url, key.ifBlank { null }, chat)
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Smart Attendance", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Step ${step + 1} of ${STEP_TITLES.size} · ${STEP_TITLES[step]}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { (step + 1) / STEP_TITLES.size.toFloat() }, modifier = Modifier.fillMaxWidth())

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (step) {
                0 -> {
                    Text(
                        "Attendance messages will use these details.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = emp, onValueChange = { emp = it },
                        label = { Text("Employee ID (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = place, onValueChange = { place = it },
                        label = { Text("Workplace name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }

                1 -> WorkplacePicker(
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

                2 -> {
                    Text(
                        "Messages are sent through your Cloudflare Worker to your Telegram bot. The bot token is stored only in the Worker, never in this app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = chat, onValueChange = { chat = it },
                        label = { Text("Telegram Chat ID") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("Worker URL") },
                        placeholder = { Text("https://your-worker.workers.dev") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text(if (s.hasAppKey) "Access key (saved, leave empty to keep)" else "Access key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Send attendance automatically", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Test the connection first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = autoSend, onCheckedChange = { autoSend = it })
                    }
                    Button(
                        onClick = {
                            if (!telegramReady) {
                                vm.toast("Enter Chat ID, an https Worker URL and the access key first")
                            } else {
                                saveTelegram()
                                vm.testTelegram()
                            }
                        },
                        enabled = !testing,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Send test message")
                    }
                    val statusText = when (s.tgStatus) {
                        1 -> "Connected ${s.tgBot}".trim()
                        2 -> "Error: ${s.tgError}"
                        else -> "Not tested yet"
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (s.tgStatus) {
                            1 -> StatusColors.ok
                            2 -> StatusColors.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    TextButton(onClick = {
                        autoSend = false
                        step = 3
                    }) { Text("Skip Telegram for now") }
                }

                else -> {
                    Text(
                        "Android needs these permissions to detect your arrival reliably.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AppCard {
                        PermRow(
                            "Precise location",
                            "Needed to know when you enter the workplace area.",
                            d.fine, "Grant", actions.requestBasic
                        )
                        PermRow(
                            "Allow all the time",
                            "Needed so arrival is detected when the app is closed or removed from recent apps. Android opens the app's settings: choose Location → Allow all the time.",
                            d.background, "Open", actions.requestBackground
                        )
                        PermRow(
                            "Location services (GPS)",
                            "Android does not let apps turn GPS on silently. Enable it in system settings.",
                            d.gpsOn, "Open settings", actions.openLocationSettings
                        )
                        if (Build.VERSION.SDK_INT >= 33) {
                            PermRow(
                                "Notifications",
                                "Shows a confirmation when your arrival is recorded and warns you if monitoring stops.",
                                d.notifications, "Grant", actions.requestBasic
                            )
                        }
                    }
                    AppCard {
                        Text("Also used by the app", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "• Foreground service (location): a short notification while an arrival is verified.\n" +
                                "• Receive boot completed: restores monitoring after the phone restarts.\n" +
                                "• Internet: sends the attendance message through your Worker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = actions.openBatterySettings, shape = RoundedCornerShape(14.dp)) {
                        Text("Battery optimization settings")
                    }
                    Text(
                        "Tip: set Smart Attendance to Unrestricted battery use, especially on Xiaomi, Huawei, Oppo, Vivo and Samsung phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (step > 0) {
                OutlinedButton(
                    onClick = { step -= 1 },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Back") }
            }
            Button(
                onClick = {
                    when (step) {
                        0 -> {
                            vm.saveProfile(name, emp, place)
                            step = 1
                        }

                        1 -> {
                            val a = lat
                            val b = lng
                            if (a != null && b != null) vm.saveWorkplace(a, b, radius, place)
                            step = 2
                        }

                        2 -> {
                            saveTelegram()
                            vm.setNotify(autoSend && telegramReady)
                            step = 3
                        }

                        else -> vm.finishSetup()
                    }
                },
                enabled = canNext,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) { Text(if (step == STEP_TITLES.size - 1) "Finish" else "Next") }
        }
    }
}
