package ir.mmdkavir.smartattendance.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.Notifier
import ir.mmdkavir.smartattendance.net.TelegramClient
import ir.mmdkavir.smartattendance.net.TgResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        Prefs.init(app)
        val s = Prefs.now()
        if (!s.notifyEnabled || s.workerUrl.isBlank() || s.chatId.isBlank()) return Result.success()

        val db = AppDb.get(app)
        for (item in db.outbox().all()) {
            val result = withContext(Dispatchers.IO) {
                TelegramClient.send(s.workerUrl, Prefs.appKey(), s.chatId, item.text, item.eventId)
            }
            when (result) {
                is TgResult.Ok -> {
                    db.outbox().delete(item.id)
                    db.attendance().markSent(item.eventId)
                    Prefs.setTg(1, "")
                    Notifier.clear(app, Notifier.ID_TELEGRAM)
                }

                is TgResult.Fail -> {
                    db.outbox().bump(item.id)
                    Prefs.setTg(2, result.message)
                    return if (result.retryable) {
                        Result.retry()
                    } else {
                        Notifier.alert(
                            app, Notifier.ID_TELEGRAM, "Telegram message not delivered",
                            "${result.message}. Check Telegram settings; the message stays queued."
                        )
                        Result.success()
                    }
                }
            }
        }
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("send_outbox", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
