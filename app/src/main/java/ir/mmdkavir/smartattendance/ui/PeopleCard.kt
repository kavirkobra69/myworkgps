package ir.mmdkavir.smartattendance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.mmdkavir.smartattendance.i18n.t

/** Registered people: each can be switched on or off independently; only enabled people appear in arrival messages. */
@Composable
fun PeopleCard(vm: MainViewModel) {
    val people by vm.people.collectAsState()
    var name by remember { mutableStateOf("") }

    AppCard {
        Text(t("People"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            t("Arrival messages list every enabled person. A disabled person is never reported. With nobody added, your name from the settings is used."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (people.isEmpty()) {
            Text(t("No people added yet"), style = MaterialTheme.typography.bodySmall)
        }
        people.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ToggleRow(
                        p.name,
                        if (p.enabled) t("Tracking on") else t("Tracking off"),
                        p.enabled
                    ) { vm.setPersonEnabled(p.id, it) }
                }
                IconButton(onClick = { vm.deletePerson(p.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = t("Delete person"))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(t("Person name")) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { if (vm.addPerson(name)) name = "" }) { Text(t("Add")) }
        }
    }
}
