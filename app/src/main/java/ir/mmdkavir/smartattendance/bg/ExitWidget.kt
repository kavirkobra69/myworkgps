package ir.mmdkavir.smartattendance.bg

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import ir.mmdkavir.smartattendance.R
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.OutboxItem
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.i18n.t
import ir.mmdkavir.smartattendance.net.Payload
import ir.mmdkavir.smartattendance.util.ExitGuard
import ir.mmdkavir.smartattendance.util.Fmt
import ir.mmdkavir.smartattendance.work.SendWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 1×1 button that sends "I left work" through the same outbox, GitHub workflow and Telegram bot as arrivals. */
class ExitWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Prefs.init(context.applicationContext)
        ids.forEach { manager.updateAppWidget(it, render(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXIT) {
            super.onReceive(context, intent)
            return
        }
        val app = context.applicationContext
        Prefs.init(app)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val message = try {
                report(app)
            } catch (e: Exception) {
                t("Could not send the exit message")
            } finally {
                pending.finish()
            }
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        }
    }

    companion object {
        const val ACTION_EXIT = "ir.mmdkavir.smartattendance.WORK_EXIT"
        private val mutex = Mutex()

        /** Captures the exact tap time once and queues one message; repeated taps are ignored. */
        private suspend fun report(app: Context): String = mutex.withLock {
            val s = Prefs.now()
            if (!s.ghReady || !s.notifyEnabled) return t("Set up GitHub and Telegram in the app first")
            val db = AppDb.get(app)
            val now = System.currentTimeMillis()
            val queued = db.outbox().all().any { it.eventId.startsWith("x") }
            if (ExitGuard.isDuplicate(now, s.exitTapAt, queued)) return t("Exit message already sent or queued")
            Prefs.edit { putLong(Keys.EXIT_TAP, now) }
            db.outbox().insert(
                OutboxItem(eventId = "x$now", text = Payload.toJson(Payload.exit(s, now)), createdAt = now)
            )
            SendWorker.enqueue(app)
            t("Exit recorded at %s", Fmt.time(now))
        }

        private fun render(app: Context): RemoteViews {
            val v = RemoteViews(app.packageName, R.layout.widget_exit)
            v.setTextViewText(R.id.exit_label, t("EXIT"))
            v.setContentDescription(R.id.exit_root, t("Send a message that I left work"))
            v.setOnClickPendingIntent(
                R.id.exit_root,
                PendingIntent.getBroadcast(
                    app, 12, Intent(app, ExitWidget::class.java).setAction(ACTION_EXIT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            return v
        }

        fun requestPin(context: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(context)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            return mgr.requestPinAppWidget(ComponentName(context, ExitWidget::class.java), null, null)
        }
    }
}
