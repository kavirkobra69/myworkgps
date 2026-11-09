package ir.mmdkavir.smartattendance.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.TextButton
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

/** Guided Telegram + Cloudflare Worker setup. Bot/Cloudflare tokens are never saved. */
@Composable
fun TelegramGuide(vm: MainViewModel) {
    val context = LocalContext.current
    val st by vm.setup.collectAsState()
    val s by vm.settings.collectAsState()
    var botToken by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf("") }
    var cfToken by remember { mutableStateOf("") }

    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            vm.toast("No browser found")
        }
    }

    AppCard {
        Text("Guided setup (your own bot and Worker)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "1. Create a bot in Telegram with @BotFather and paste its token below.\n" +
                "2. Create a free Cloudflare account, copy your Account ID, and create an API token from the \"Edit Cloudflare Workers\" template.\n" +
                "3. Press Deploy. The app uploads the relay to your own account. The tokens are used once and are not stored on this phone; the bot token lives only in your Worker.\n" +
                "4. Open your bot in Telegram, press Start, then press Detect my chat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open("https://t.me/BotFather") }, shape = RoundedCornerShape(14.dp)) { Text("Open BotFather") }
            OutlinedButton(onClick = { open("https://dash.cloudflare.com/profile/api-tokens") }, shape = RoundedCornerShape(14.dp)) { Text("Cloudflare tokens") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = botToken, onValueChange = { botToken = it.trim() },
            label = { Text("Bot token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = accountId, onValueChange = { accountId = it.trim() },
            label = { Text("Cloudflare Account ID") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cfToken, onValueChange = { cfToken = it.trim() },
            label = { Text("Cloudflare API token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                vm.deployWorker(accountId, cfToken, botToken)
            },
            enabled = !st.busy && botToken.isNotBlank() && accountId.isNotBlank() && cfToken.isNotBlank(),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Deploy my Worker") }
        if (s.workerUrl.isNotBlank() && s.hasAppKey) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { vm.detectChats() }, enabled = !st.busy, shape = RoundedCornerShape(14.dp)) {
                Text("Detect my chat")
            }
        }
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
        st.detectNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        st.chats.forEach { c ->
            TextButton(onClick = { vm.chooseChat(c.id) }) {
                Text("${c.name.ifBlank { "Chat" }} (${c.id})")
            }
        }
        Text(
            "Already have a Worker? Skip this card and enter its URL and access key manually.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
