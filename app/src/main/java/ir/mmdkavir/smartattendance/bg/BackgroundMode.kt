package ir.mmdkavir.smartattendance.bg

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.LocationHelper
import ir.mmdkavir.smartattendance.work.WatchdogWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Widget/notification/UI-facing state, derived from the real service instance, not only from a stored flag. */
enum class BgUiState { OFF, ACTIVE, ATTENTION }

/**
 * Single source of truth for background mode. The persisted switch (`Prefs.bgMode`, the user's intent) lives in
 * Prefs; whether the service really runs is tracked in-process by [alive], which the service sets itself.
 */
object BackgroundMode {
    const val ST_OFF = "off"
    const val ST_OK = "ok"
    const val ST_SEARCHING = "searching"
    const val ST_GPS_OFF = "gps_off"
    const val ST_NO_PERMISSION = "no_permission"
    const val ST_BLOCKED = "blocked"

    private val _alive = MutableStateFlow(false)
    val alive: StateFlow<Boolean> = _alive

    internal fun setAlive(value: Boolean) {
        _alive.value = value
    }

    fun permissionsOk(context: Context): Boolean =
        LocationHelper.hasFine(context) && LocationHelper.hasBackground(context)

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun uiState(): BgUiState {
        val s = Prefs.now()
        return when {
            !s.bgMode -> BgUiState.OFF
            _alive.value && s.bgStatus == ST_OK -> BgUiState.ACTIVE
            else -> BgUiState.ATTENTION
        }
    }

    /** Returns false when a permission is missing; the caller must then ask through the app UI. */
    fun enable(context: Context): Boolean {
        val app = context.applicationContext
        Prefs.init(app)
        if (!permissionsOk(app)) return false
        Prefs.edit {
            putBoolean(Keys.BG_ON, true)
            putString(Keys.BG_STATUS, ST_SEARCHING)
            putBoolean(Keys.MONITOR, true)
        }
        ir.mmdkavir.smartattendance.geo.Monitor.start(app)
        WatchdogWorker.schedule(app)
        start(app)
        BgWidget.refresh(app)
        return true
    }

    fun disable(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        Prefs.edit {
            putBoolean(Keys.BG_ON, false)
            putString(Keys.BG_STATUS, ST_OFF)
            putBoolean(Keys.MONITOR, false)
        }
        ir.mmdkavir.smartattendance.geo.Monitor.stop(app)
        app.stopService(Intent(app, TrackingService::class.java))
        _alive.value = false
        val s = Prefs.now()
        if (!(s.setupDone && s.monitoring)) WatchdogWorker.cancel(app)
        BgWidget.refresh(app)
    }

    /** Starts the service when background mode is on and it is not running (boot, watchdog, widget refresh). */
    fun recover(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        if (!Prefs.now().bgMode || _alive.value) return
        if (!permissionsOk(app)) {
            Prefs.edit { putString(Keys.BG_STATUS, ST_NO_PERMISSION) }
            BgWidget.refresh(app)
            return
        }
        start(app)
    }

    private fun start(app: Context) {
        try {
            ContextCompat.startForegroundService(app, Intent(app, TrackingService::class.java))
        } catch (e: Exception) {
            // Android refused a background start; the persisted intent stays on and the watchdog retries.
            Prefs.edit { putString(Keys.BG_STATUS, ST_BLOCKED) }
            BgWidget.refresh(app)
        }
    }
}
