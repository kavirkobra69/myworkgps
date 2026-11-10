package ir.mmdkavir.smartattendance.geo

import ir.mmdkavir.smartattendance.i18n.t
import android.content.Context
import android.location.Location
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.OutboxItem
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.net.Payload
import ir.mmdkavir.smartattendance.util.Fmt
import ir.mmdkavir.smartattendance.util.Geo
import ir.mmdkavir.smartattendance.work.SendWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

object AttendanceEngine {
    private val mutex = Mutex()
    private const val REENTRY_COOLDOWN_MS = 60_000L
    private const val GOOD_ACCURACY_M = 100f
    private const val MAX_USABLE_ACCURACY_M = 150f
    private const val SANE_WINDOW_MS = 15 * 60_000L

    private fun sane(candidate: Long, now: Long): Long =
        if (candidate in (now - SANE_WINDOW_MS)..(now + 60_000L)) candidate else now

    suspend fun onEntry(context: Context, arrivalHint: Long, hint: Location?) {
        val app = context.applicationContext
        Prefs.init(app)
        mutex.withLock {
            val s = Prefs.now()
            if (!s.setupDone || !s.hasLocation || !s.monitoring || s.inVisit) return
            val now = System.currentTimeMillis()
            if (s.lastExit > 0 && now - s.lastExit < REENTRY_COOLDOWN_MS) return

            val hintFresh = hint != null && now - hint.time < 60_000L && hint.accuracy <= GOOD_ACCURACY_M
            val fix = if (hintFresh) hint else (LocationHelper.currentFix(app) ?: hint)
            if (fix != null) {
                val d = Geo.distanceMeters(fix.latitude, fix.longitude, s.lat, s.lng)
                if (d - fix.accuracy > s.radius) return
            }

            val arrival = sane(arrivalHint, now)
            val zone = ZoneId.systemDefault()
            val record = AttendanceRecord(
                eventId = "v$arrival",
                arrivalMillis = arrival,
                zoneId = zone.id,
                date = Fmt.date(arrival, zone),
                time = Fmt.time(arrival, zone),
                workplace = s.workplace,
                lat = fix?.latitude ?: s.lat,
                lng = fix?.longitude ?: s.lng,
                accuracy = fix?.accuracy ?: -1f,
                status = "ARRIVED"
            )

            val db = AppDb.get(app)
            val rowId = db.attendance().insert(record)
            if (rowId == -1L) return

            Prefs.edit {
                putBoolean(Keys.IN_VISIT, true)
                putLong(Keys.LAST_ARRIVAL, arrival)
            }

            if (s.notifyEnabled && s.ghReady) {
                db.outbox().insert(
                    OutboxItem(
                        eventId = record.eventId,
                        text = Payload.toJson(Payload.arrival(s, record)),
                        createdAt = now
                    )
                )
                SendWorker.enqueue(app)
            }

            Notifier.post(
                app,
                Notifier.CH_ATTENDANCE,
                Notifier.ID_ARRIVAL,
                t("Arrival recorded"),
                t("%s at %s", s.workplace, record.time)
            )
        }
    }

    suspend fun onExit(context: Context, leftHint: Long) {
        val app = context.applicationContext
        Prefs.init(app)
        mutex.withLock {
            val s = Prefs.now()
            if (!s.inVisit) return
            val fix = LocationHelper.currentFix(app, 15_000L)
            if (fix != null && fix.accuracy <= GOOD_ACCURACY_M) {
                val d = Geo.distanceMeters(fix.latitude, fix.longitude, s.lat, s.lng)
                if (d + fix.accuracy < s.radius * 0.8) return
            }
            val now = System.currentTimeMillis()
            val left = sane(leftHint, now)
            val db = AppDb.get(app)
            val latest = db.attendance().latest()
            if (latest != null && latest.leftMillis == null) db.attendance().setLeft(latest.id, left)
            Prefs.edit {
                putBoolean(Keys.IN_VISIT, false)
                putLong(Keys.LAST_EXIT, left)
            }
        }
    }

    suspend fun periodicCheck(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        val s = Prefs.now()
        if (!s.setupDone || !s.monitoring || !s.hasLocation) return

        if (!LocationHelper.hasFine(app) || !LocationHelper.hasBackground(app)) {
            Notifier.alert(
                app, Notifier.ID_PERMISSION, t("Location permission required"),
                t("Allow location \"All the time\" so arrival can be detected in the background.")
            )
            return
        }
        Notifier.clear(app, Notifier.ID_PERMISSION)

        if (!LocationHelper.isLocationEnabled(app)) {
            Prefs.edit { putBoolean(Keys.GPS_OFF, true) }
            Notifier.alert(
                app, Notifier.ID_GPS, t("GPS is turned off"),
                t("Turn on location services so attendance can be detected.")
            )
            return
        }
        Notifier.clear(app, Notifier.ID_GPS)

        if (s.gpsOffSeen) {
            Prefs.edit { putBoolean(Keys.GPS_OFF, false) }
            GeofenceController.register(app)
        } else {
            GeofenceController.ensure(app)
        }

        val fix = LocationHelper.currentFix(app, 20_000L) ?: return
        if (fix.accuracy > MAX_USABLE_ACCURACY_M) return
        val d = Geo.distanceMeters(fix.latitude, fix.longitude, s.lat, s.lng)
        val current = Prefs.now()
        if (d <= s.radius && !current.inVisit) {
            if (!stayedInside(app, s, fix.time)) return
            onEntry(app, fix.time, fix)
        } else if (current.inVisit && d - fix.accuracy > s.radius * 1.2) {
            onExit(app, fix.time)
        }
    }

    private suspend fun stayedInside(app: Context, s: ir.mmdkavir.smartattendance.data.AppSettings, firstTime: Long): Boolean {
        if (s.dwellSec <= 0) return true
        delay(s.dwellSec * 1000L)
        if (!Prefs.now().monitoring) return false
        val second = LocationHelper.currentFix(app, 20_000L) ?: return false
        if (second.accuracy > MAX_USABLE_ACCURACY_M || second.time < firstTime) return false
        val d = Geo.distanceMeters(second.latitude, second.longitude, s.lat, s.lng)
        return d - second.accuracy <= s.radius
    }
}
