package ir.mmdkavir.smartattendance.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import ir.mmdkavir.smartattendance.i18n.t
import ir.mmdkavir.smartattendance.net.Payload
import ir.mmdkavir.smartattendance.util.Export
import ir.mmdkavir.smartattendance.util.Fmt

private sealed interface Pending {
    data class Some(val ids: Set<Long>) : Pending
    data object All : Pending
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val records by vm.records.collectAsState()
    val cfg by vm.settings.collectAsState()
    val context = LocalContext.current

    var selected by remember { mutableStateOf(setOf<Long>()) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    val selecting = selected.isNotEmpty()
    // Forget selections for records that no longer exist.
    val liveSelected = selected.intersect(records.map { it.id }.toSet())

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (selecting) t("%s selected", liveSelected.size) else t("Attendance History"),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                )
                Text(
                    t("%s record(s)", records.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selecting) {
                IconButton(onClick = { selected = records.map { it.id }.toSet() }) {
                    Icon(Icons.Default.SelectAll, contentDescription = t("Select all"))
                }
                IconButton(onClick = { pending = Pending.Some(liveSelected) }, enabled = liveSelected.isNotEmpty()) {
                    Icon(Icons.Default.Delete, contentDescription = t("Delete selected"))
                }
                IconButton(onClick = { selected = emptySet() }) {
                    Icon(Icons.Default.Close, contentDescription = t("Cancel selection"))
                }
            } else {
                IconButton(
                    onClick = {
                        if (records.isEmpty()) vm.toast(t("Nothing to export yet")) else Export.share(context, records)
                    }
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = t("Export CSV"))
                }
                IconButton(onClick = { pending = Pending.All }, enabled = records.isNotEmpty()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = t("Delete all"))
                }
            }
        }

        AppCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                t("This list is the history stored on this phone. Deleting here does not delete Telegram messages or GitHub Actions runs."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(t("No attendance records yet"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        t("Your arrivals will appear here automatically."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(records, key = { it.id }) { r ->
                    val isSelected = r.id in selected
                    AppCard(
                        Modifier.combinedClickable(
                            onClick = { if (selecting) selected = if (isSelected) selected - r.id else selected + r.id },
                            onLongClick = { selected = selected + r.id }
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { selected = if (isSelected) selected - r.id else selected + r.id }
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    Payload.recordDate(r, cfg.dateFormat),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    t("Arrival %s", r.time),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Pill(t(r.status), StatusColors.ok, Icons.Default.CheckCircle)
                            if (!selecting) {
                                IconButton(onClick = { pending = Pending.Some(setOf(r.id)) }) {
                                    Icon(Icons.Default.Delete, contentDescription = t("Delete record"))
                                }
                            }
                        }
                        Text(
                            r.workplace,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (r.sent) {
                                Pill(t("Sent to Telegram"), StatusColors.info, Icons.Default.CheckCircle)
                            } else {
                                Pill(t("Not sent yet"), StatusColors.warn, Icons.Default.Schedule)
                            }
                            r.leftMillis?.let {
                                Text(
                                    t("Left %s", Fmt.time(it)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        val (title, body) = when (p) {
            is Pending.All -> t("Delete all records?") to t(
                t("All %s records on this phone and any messages still waiting to be sent will be removed. Messages already sent to Telegram and the GitHub Actions run history are not affected."),
                records.size
            )
            is Pending.Some -> (if (p.ids.size == 1) t("Delete this record?") else t("Delete %s records?", p.ids.size)) to t(
                t("They are removed only from the history on this phone, and any of them still waiting to be sent will not be sent. Messages already sent to Telegram and the GitHub Actions run history are not affected.")
            )
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(title) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    when (p) {
                        is Pending.All -> vm.deleteAllRecords()
                        is Pending.Some -> vm.deleteRecords(p.ids)
                    }
                    selected = emptySet()
                    pending = null
                }) { Text(t("Delete"), color = StatusColors.error) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(t("Cancel")) } }
        )
    }
}
