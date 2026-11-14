package ir.mmdkavir.smartattendance.bg

import android.content.Context
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.OutboxItem
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.i18n.t
import ir.mmdkavir.smartattendance.net.Payload
import ir.mmdkavir.smartattendance.util.ExitGuard
import ir.mmdkavir.smartattendance.util.Fmt
import ir.mmdkavir.smartattendance.work.SendWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** "I left work": queues one message through the same outbox, GitHub workflow and Telegram bot as arrivals. */
object ExitReport {
    private val mutex = Mutex()

    /** Captures the exact tap time once; repeated taps are ignored. Returns the text to show the user. */
    suspend fun report(app: Context): String = mutex.withLock {
        Prefs.init(app)
        val s = Prefs.now()
        if (!s.ghReady || !s.notifyEnabled) return t("Set up GitHub and Telegram in the app first")
        val db = AppDb.get(app)
        val now = System.currentTimeMillis()
        val queued = db.outbox().all().any { it.eventId.startsWith("x") }
        if (ExitGuard.isDuplicate(now, s.exitTapAt, queued)) return t("Exit message already sent or queued")
        Prefs.edit { putLong(Keys.EXIT_TAP, now) }
        val active = db.people().enabledNames()
        db.outbox().insert(
            OutboxItem(eventId = "x$now", text = Payload.toJson(Payload.exit(s, now, active)), createdAt = now)
        )
        SendWorker.enqueue(app)
        t("Exit recorded at %s", Fmt.time(now))
    }
}
