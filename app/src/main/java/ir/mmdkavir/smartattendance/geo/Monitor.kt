package ir.mmdkavir.smartattendance.geo

import android.content.Context
import android.content.Intent
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.work.WatchdogWorker

object Monitor {
    fun start(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        Notifier.ensureChannels(app)
        val s = Prefs.now()
        if (!s.setupDone) return
        if (!s.monitoring) {
            stop(app)
            return
        }
        GeofenceController.register(app)
        WatchdogWorker.schedule(app)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        GeofenceController.unregister(app)
        if (!Prefs.now().bgMode) WatchdogWorker.cancel(app)
        androidx.work.WorkManager.getInstance(app).cancelUniqueWork("send_outbox")
        app.stopService(Intent(app, VerifyService::class.java))
        Notifier.clear(app, Notifier.ID_PERMISSION)
        Notifier.clear(app, Notifier.ID_GPS)
        Prefs.edit {
            putBoolean(Keys.IN_VISIT, false)
            putLong(Keys.ENTER_AT, 0L)
            putBoolean(Keys.GPS_OFF, false)
        }
    }

    fun afterSetup(context: Context) {
        val app = context.applicationContext
        start(app)
        if (Prefs.now().monitoring) WatchdogWorker.runNow(app)
    }
}
