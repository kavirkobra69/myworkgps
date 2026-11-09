package ir.mmdkavir.smartattendance.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ir.mmdkavir.smartattendance.update.UpdateState
import ir.mmdkavir.smartattendance.update.Updater

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageFormatCard(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    AppCard {
        Text("Telegram message format", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Message language", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.msgLang == "en", onClick = { vm.setMsgLang("en") }, label = { Text("English") })
            FilterChip(selected = s.msgLang == "fa", onClick = { vm.setMsgLang("fa") }, label = { Text("فارسی") })
        }
        Spacer(Modifier.height(8.dp))
        Text("Date format", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.dateFormat == "gregorian", onClick = { vm.setDateFormat("gregorian") }, label = { Text("Gregorian") })
            FilterChip(selected = s.dateFormat == "jalali", onClick = { vm.setDateFormat("jalali") }, label = { Text("Jalali (شمسی)") })
        }
    }
}

@Composable
private fun SetupStatus(st: SetupUi) {
    if (st.busy) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(st.progress, style = MaterialTheme.typography.bodySmall)
        }
    }
    st.success?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.ok) }
    st.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.error) }
}

/** GitHub connection: token, repository URL and a single Connect button. */
@Composable
fun GitHubCard(vm: MainViewModel) {
    val context = LocalContext.current
    val st by vm.setup.collectAsState()
    val s by vm.settings.collectAsState()
    var token by remember { mutableStateOf("") }
    var url by remember(s.ghRepoUrl) { mutableStateOf(s.ghRepoUrl) }

    AppCard {
        Text("GitHub connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Attendance messages are sent by a GitHub Actions workflow in your repository. Create a fine-grained Personal Access Token " +
                "limited to that repository with these permissions: Actions (read/write), Contents (read/write), Workflows (read/write), " +
                "Secrets (read/write), Metadata (read). A classic token needs the repo and workflow scopes. The token is stored encrypted on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new")))
            } catch (e: Exception) {
                vm.toast("No browser found")
            }
        }) { Text("Create a token on GitHub") }
        OutlinedTextField(
            value = token, onValueChange = { token = it.trim() },
            label = { Text(if (s.hasGhToken) "Personal Access Token (saved, leave empty to keep)" else "Personal Access Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Repository URL") },
            placeholder = { Text("https://github.com/owner/repository") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.connectGitHub(token, url) },
            enabled = !st.busy && url.isNotBlank() && (token.isNotBlank() || s.hasGhToken),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Connect / Test connection") }
        val color = when (s.ghStatus) {
            1 -> StatusColors.ok
            2 -> StatusColors.error
            else -> StatusColors.warn
        }
        val text = when (s.ghStatus) {
            1 -> "Connected: ${s.ghOwner}/${s.ghRepo} (branch ${s.ghBranch}) as ${s.ghLogin}"
            2 -> "Error: ${s.ghError}"
            else -> "Not connected"
        }
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        SetupStatus(st)
    }
}

/** Telegram bot token and chat ID. Both are stored only as encrypted GitHub Actions secrets. */
@Composable
fun TelegramCard(vm: MainViewModel) {
    val context = LocalContext.current
    val st by vm.setup.collectAsState()
    val s by vm.settings.collectAsState()
    val testing by vm.testing.collectAsState()
    var bot by remember { mutableStateOf("") }
    var chat by remember(s.chatId) { mutableStateOf(s.chatId) }

    fun open(u: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (e: Exception) {
            vm.toast("No browser found")
        }
    }

    AppCard {
        Text("Telegram", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "1. Create your own bot with @BotFather and copy its token.\n" +
                "2. Open your bot and press Start.\n" +
                "3. Get your numeric Chat ID from @userinfobot (for a group, add the bot to the group and use the group's negative ID).\n" +
                "The token is sent straight to GitHub Secrets (encrypted) and is not kept in this app or in the repository.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { open("https://t.me/BotFather") }) { Text("Open BotFather") }
            TextButton(onClick = { open("https://t.me/userinfobot") }) { Text("Get Chat ID") }
        }
        OutlinedTextField(
            value = bot, onValueChange = { bot = it.trim() },
            label = { Text("Bot token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = chat, onValueChange = { chat = it.trim() },
            label = { Text("Chat ID") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    vm.saveTelegramSecrets(bot, chat)
                    bot = ""
                },
                enabled = !st.busy && s.ghStatus == 1 && bot.isNotBlank() && chat.isNotBlank(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Save Telegram settings") }
            OutlinedButton(
                onClick = { vm.sendTest() },
                enabled = !testing && !st.busy && s.ghStatus == 1 && s.secretsOk,
                shape = RoundedCornerShape(14.dp)
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Send test message")
            }
        }
        Spacer(Modifier.height(6.dp))
        InfoRow("Chat ID", s.chatId.ifBlank { "Not set" })
        InfoRow(
            "GitHub secrets",
            if (s.secretsOk) "Saved" else "Not saved",
            if (s.secretsOk) StatusColors.ok else StatusColors.warn
        )
        InfoRow(
            "Last delivery",
            when (s.tgStatus) {
                1 -> "Delivered"
                2 -> s.tgError.ifBlank { "Error" }
                else -> "No delivery yet"
            },
            when (s.tgStatus) {
                1 -> StatusColors.ok
                2 -> StatusColors.error
                else -> Color.Unspecified
            }
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Send attendance through GitHub", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Turn off to keep records only on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = s.notifyEnabled, onCheckedChange = { on ->
                if (on && !(s.ghReady && s.secretsOk)) vm.toast("Connect GitHub and save the Telegram settings first") else vm.setNotify(on)
            })
        }
        SetupStatus(st)
    }
}

@Composable
fun DiagnosticsDialog(vm: MainViewModel) {
    val text by vm.diagnostics.collectAsState()
    val t = text ?: return
    AlertDialog(
        onDismissRequest = { vm.consumeDiagnostics() },
        title = { Text("Connection test") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(t, style = MaterialTheme.typography.bodyMedium) } },
        confirmButton = { TextButton(onClick = { vm.consumeDiagnostics() }) { Text("Close") } }
    )
}

@Composable
fun UpdateCard(vm: MainViewModel) {
    val context = LocalContext.current
    val st by vm.update.collectAsState()
    AppCard {
        Text("App update", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        InfoRow("Installed version", Updater.currentVersion(context))
        when (val u = st) {
            is UpdateState.Idle -> Unit
            is UpdateState.Checking -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking GitHub releases…", style = MaterialTheme.typography.bodySmall)
            }
            is UpdateState.UpToDate -> Text("You have the latest version (${u.current}).", style = MaterialTheme.typography.bodyMedium, color = StatusColors.ok)
            is UpdateState.Available -> {
                Text("Version ${u.info.version} is available" + if (u.info.prerelease) " (pre-release)" else "", fontWeight = FontWeight.SemiBold)
                if (u.info.notes.isNotBlank()) {
                    Text("What's new:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    Text(u.info.notes.take(1500), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.downloadAndInstall(u.info) }, shape = RoundedCornerShape(14.dp)) { Text("Download and install") }
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${u.info.version}… ${u.percent}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> {
                Text("Version ${u.info.version} downloaded and verified.", color = StatusColors.ok)
                if (u.note.isNotBlank()) Text(u.note, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.installDownloaded(u.file) }, shape = RoundedCornerShape(14.dp)) { Text("Install") }
            }
            is UpdateState.Error -> Text(u.message, style = MaterialTheme.typography.bodySmall, color = StatusColors.error)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { vm.checkForUpdate() },
            enabled = st !is UpdateState.Checking && st !is UpdateState.Downloading,
            shape = RoundedCornerShape(14.dp)
        ) { Text("Update App") }
        Text(
            "Source: github.com/kavirkobra69/myworkgps releases",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
