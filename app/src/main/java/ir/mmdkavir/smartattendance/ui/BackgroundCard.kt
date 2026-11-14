package ir.mmdkavir.smartattendance.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.mmdkavir.smartattendance.bg.BackgroundMode
import ir.mmdkavir.smartattendance.bg.BgWidget
import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.i18n.t
import java.util.Locale

/** Runs the whole enable flow: missing permissions are requested first, the service starts once they are granted. */
fun startBackgroundFlow(context: Context, vm: MainViewModel, actions: PermissionActions) {
    when {
        !ir.mmdkavir.smartattendance.geo.LocationHelper.hasFine(context) -> { vm.pendingBg = true; actions.requestBasic() }
        !ir.mmdkavir.smartattendance.geo.LocationHelper.hasBackground(context) -> { vm.pendingBg = true; actions.requestBackground() }
        !ir.mmdkavir.smartattendance.geo.LocationHelper.isLocationEnabled(context) -> { vm.pendingBg = true; actions.enableLocation() }
        else -> vm.setBackgroundMode(true)
    }
}

@Composable
fun BackgroundCard(vm: MainViewModel, s: AppSettings, actions: PermissionActions) {
    val context = LocalContext.current
    val alive by BackgroundMode.alive.collectAsState()
    val running = s.bgMode && alive

    AppCard {
        Text(t("Persistent background mode"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ToggleRow(
            t("Keep tracking in the background"),
            t("GPS and sync keep running when the app is closed, with a notification"),
            s.bgMode
        ) { on ->
            if (on) startBackgroundFlow(context, vm, actions) else vm.setBackgroundMode(false)
        }
        InfoRow(
            t("Status"),
            when {
                !s.bgMode -> t("Inactive")
                running && s.bgStatus == BackgroundMode.ST_OK -> t("Active")
                running -> t("Active, waiting for GPS")
                else -> t("Needs attention")
            },
            when {
                !s.bgMode -> MaterialTheme.colorScheme.onSurfaceVariant
                running && s.bgStatus == BackgroundMode.ST_OK -> StatusColors.ok
                else -> StatusColors.warn
            }
        )
        InfoRow(t("Last location"), lastLocation(s))
        InfoRow(t("Last sync"), if (s.bgSyncAt > 0) relative(s.bgSyncAt) else "—")
        if (s.bgMode && s.bgStatus != BackgroundMode.ST_OK) {
            val hint = when (s.bgStatus) {
                BackgroundMode.ST_NO_PERMISSION -> t("Location permission was removed. Allow \"All the time\" to continue.")
                BackgroundMode.ST_GPS_OFF -> t("Location services are off. Turn them on to continue.")
                BackgroundMode.ST_BLOCKED -> t("Android blocked starting the service. Open the app to restart it.")
                else -> t("Waiting for a valid GPS signal…")
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (BgWidget.canPin(context)) {
                OutlinedButton(
                    onClick = {
                        if (!BgWidget.requestPin(context)) vm.toast(t("Could not add the widget. Add it from your home screen widgets list."))
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(t("Add 1×1 widget")) }
                OutlinedButton(
                    onClick = {
                        if (!ir.mmdkavir.smartattendance.bg.ExitWidget.requestPin(context)) vm.toast(t("Could not add the widget. Add it from your home screen widgets list."))
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(t("Add exit widget")) }
            }
        }
        Text(
            t("Android may still stop apps to save power. The app restarts tracking after a reboot or when it is reopened."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun lastLocation(s: AppSettings): String =
    if (!s.hasBgLocation) "—"
    else String.format(Locale.US, "%.5f, %.5f · %s", s.bgLat, s.bgLng, relative(s.bgLocAt))

private fun relative(time: Long): String {
    val min = ((System.currentTimeMillis() - time) / 60_000L).coerceAtLeast(0L)
    return when {
        min < 1 -> t("just now")
        min < 60 -> t("%s min ago", min)
        min < 1440 -> t("%s h ago", min / 60)
        else -> t("%s d ago", min / 1440)
    }
}
