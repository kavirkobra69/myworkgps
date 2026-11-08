package ir.mmdkavir.smartattendance.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.AttendanceEngine
import java.util.concurrent.TimeUnit

class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        Prefs.init(app)
        if (Prefs.now().notifyEnabled && AppDb.get(app).outbox().all().isNotEmpty()) {
            SendWorker.enqueue(app)
        }
        AttendanceEngine.periodicCheck(app)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "watchdog_now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WatchdogWorker>().build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork("watchdog")
        }
    }
}
