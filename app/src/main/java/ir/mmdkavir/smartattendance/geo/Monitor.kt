package ir.mmdkavir.smartattendance.geo

import android.content.Context
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.work.WatchdogWorker

object Monitor {
    fun start(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        Notifier.ensureChannels(app)
        if (!Prefs.now().setupDone) return
        GeofenceController.register(app)
        WatchdogWorker.schedule(app)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        GeofenceController.unregister(app)
        WatchdogWorker.cancel(app)
    }

    fun afterSetup(context: Context) {
        start(context)
        WatchdogWorker.runNow(context.applicationContext)
    }
}
