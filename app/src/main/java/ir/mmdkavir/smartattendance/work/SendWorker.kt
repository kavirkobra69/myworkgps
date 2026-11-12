package ir.mmdkavir.smartattendance.work

import ir.mmdkavir.smartattendance.i18n.t
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
import ir.mmdkavir.smartattendance.data.OutboxItem
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.Notifier
import ir.mmdkavir.smartattendance.net.GhException
import ir.mmdkavir.smartattendance.net.GitHub
import ir.mmdkavir.smartattendance.net.Payload
import ir.mmdkavir.smartattendance.net.RepoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Delivers queued attendance events by triggering the GitHub Actions workflow, then follows the run
 * until it finishes. A dispatch that GitHub accepted (HTTP 204) is never repeated unless the run
 * itself is confirmed as failed, so a visit cannot produce duplicate Telegram messages.
 */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private class Waiting : Exception()

    override suspend fun doWork(): Result {
        val app = applicationContext
        Prefs.init(app)
        val s = Prefs.now()
        if (!s.monitoring || !s.notifyEnabled || !s.ghReady) return Result.success()

        val db = AppDb.get(app)
        val token = Prefs.ghToken()
        val repo = RepoRef(s.ghOwner, s.ghRepo)
        var retry = false

        val items = db.outbox().all()
        for (item in items) {
            try {
                withContext(Dispatchers.IO) { deliver(item, token, repo, s.ghBranch) }
            } catch (e: Waiting) {
                retry = true
            } catch (e: GhException) {
                if (e.retryable) {
                    retry = true
                    Prefs.setTg(2, t("%s Will retry.", e.message ?: t("GitHub error")))
                } else {
                    Prefs.setTg(2, e.message ?: t("GitHub error"))
                    Notifier.alert(
                        app, Notifier.ID_TELEGRAM, t("Attendance not delivered"),
                        t("%s The message stays queued; fix the GitHub settings and press Connect.", e.message)
                    )
                    return Result.success()
                }
            }
        }
        if (!retry && items.isNotEmpty()) {
            Prefs.edit { putLong(ir.mmdkavir.smartattendance.data.Keys.BG_SYNC_AT, System.currentTimeMillis()) }
        }
        return if (retry) Result.retry() else Result.success()
    }

    private fun dispatchId(item: OutboxItem): String =
        if (item.dispatches == 0) item.eventId else "${item.eventId}-r${item.dispatches}"

    private suspend fun deliver(item: OutboxItem, token: String, repo: RepoRef, branch: String) {
        val app = applicationContext
        val db = AppDb.get(app)
        var current = item

        if (current.dispatchedAt == 0L) {
            val id = dispatchId(current)
            // A previous dispatch call may have reached GitHub although the answer was lost.
            val already = if (current.attempts > 0) GitHub.findRun(token, repo, id) else null
            if (already == null) {
                val inputs = Payload.fromJson(current.text) + ("event_id" to id)
                try {
                    GitHub.dispatch(token, repo, branch, inputs)
                } catch (e: GhException) {
                    if (e.retryable) db.outbox().bump(current.id)
                    throw e
                }
            }
            db.outbox().markDispatched(current.id, System.currentTimeMillis())
            current = db.outbox().all().firstOrNull { it.id == current.id } ?: return
        }

        val id = "${current.eventId}" + if (current.dispatches <= 1) "" else "-r${current.dispatches - 1}"
        val waitUntil = System.currentTimeMillis() + 120_000
        while (true) {
            val run = GitHub.findRun(token, repo, id)
            if (run != null && run.status == "completed") {
                if (run.conclusion == "success") {
                    db.outbox().delete(current.id)
                    db.attendance().markSent(current.eventId)
                    Prefs.setTg(1, "")
                    Notifier.clear(app, Notifier.ID_TELEGRAM)
                    return
                }
                val why = GitHub.failureReason(token, repo, run.id)
                if (current.dispatches < 3) {
                    db.outbox().resetDispatch(current.id)
                    throw GhException(t("Workflow run %s%s.", run.conclusion, why?.let { " ($it)" } ?: ""), retryable = true)
                }
                db.outbox().delete(current.id)
                val msg = t("Workflow run %s%s. See %s", run.conclusion, why?.let { " ($it)" } ?: "", run.url)
                Prefs.setTg(2, msg)
                Notifier.alert(app, Notifier.ID_TELEGRAM, t("Attendance message failed"), msg)
                return
            }
            val age = System.currentTimeMillis() - current.dispatchedAt
            if (run == null && age > TimeUnit.MINUTES.toMillis(10)) {
                db.outbox().delete(current.id)
                val msg = t("GitHub accepted the request but no run appeared. Check the Actions tab.")
                Prefs.setTg(2, msg)
                Notifier.alert(app, Notifier.ID_TELEGRAM, t("Attendance message status unknown"), msg)
                return
            }
            if (age > TimeUnit.MINUTES.toMillis(20)) {
                db.outbox().delete(current.id)
                Prefs.setTg(2, t("The GitHub run did not finish in time. Check the Actions tab."))
                return
            }
            if (System.currentTimeMillis() > waitUntil) throw Waiting()
            delay(6_000)
        }
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
