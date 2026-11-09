package ir.mmdkavir.smartattendance.geo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.android.gms.location.Geofence
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.work.WatchdogWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class VerifyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifier.verifying(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.ID_VERIFY, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(Notifier.ID_VERIFY, notification)
            }
        } catch (e: Exception) {
            WatchdogWorker.runNow(applicationContext)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val type = intent?.getIntExtra(EXTRA_TYPE, -1) ?: -1
        val time = intent?.getLongExtra(EXTRA_TIME, System.currentTimeMillis()) ?: System.currentTimeMillis()
        val loc = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_LOC, Location::class.java) }

        scope.launch {
            try {
                withTimeout(70_000L) {
                    Prefs.init(applicationContext)
                    handle(type, time, loc)
                }
            } catch (e: Exception) {
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun handle(type: Int, time: Long, loc: Location?) {
        val s = Prefs.now()
        if (!s.setupDone || !s.monitoring) return
        when (type) {
            Geofence.GEOFENCE_TRANSITION_ENTER ->
                if (s.dwellSec == 0) {
                    AttendanceEngine.onEntry(applicationContext, time, loc)
                } else if (!s.inVisit) {
                    Prefs.edit { putLong(Keys.ENTER_AT, time) }
                }

            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                val entered = if (s.enterAt > 0 && time - s.enterAt in 0L..SANE_ENTER_WINDOW_MS) {
                    s.enterAt
                } else {
                    time - s.dwellSec * 1000L
                }
                AttendanceEngine.onEntry(applicationContext, entered, loc)
                Prefs.edit { putLong(Keys.ENTER_AT, 0L) }
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                Prefs.edit { putLong(Keys.ENTER_AT, 0L) }
                AttendanceEngine.onExit(applicationContext, time)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_TIME = "time"
        private const val EXTRA_LOC = "loc"
        private const val SANE_ENTER_WINDOW_MS = 15 * 60_000L

        fun start(context: Context, type: Int, time: Long, loc: Location?) {
            val intent = Intent(context, VerifyService::class.java)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_TIME, time)
            if (loc != null) intent.putExtra(EXTRA_LOC, loc)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                WatchdogWorker.runNow(context.applicationContext)
            }
        }
    }
}
