package ir.mmdkavir.smartattendance.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Priority
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.LocationHelper
import ir.mmdkavir.smartattendance.geo.Notifier
import ir.mmdkavir.smartattendance.i18n.t
import ir.mmdkavir.smartattendance.util.Fix
import ir.mmdkavir.smartattendance.util.FixFilter
import ir.mmdkavir.smartattendance.work.SendWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The only long-running component: a location-type foreground service that exists solely while the user has
 * background mode switched on. Location updates are balanced-power (≈30 s / 15 m) and independent from the UI.
 */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastFix: Fix? = null
    private var lastText = ""
    private var lastStatus = ""
    private val tick = MutableStateFlow(0)
    private var cm: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var providerReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(applicationContext)
        val n = Notifier.tracking(this, t("Starting…"))
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.ID_TRACK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(Notifier.ID_TRACK, n)
            }
        } catch (e: Exception) {
            Prefs.edit { putString(Keys.BG_STATUS, BackgroundMode.ST_NO_PERMISSION) }
            BackgroundMode.setAlive(false)
            BgWidget.refresh(applicationContext)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!Prefs.now().bgMode) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        BackgroundMode.setAlive(true)
        if (job == null) begin()
        BgWidget.refresh(applicationContext)
        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun begin() {
        registerNetwork()
        registerProviders()
        job = scope.launch {
            launch { supervise() }
            tick.collectLatest {
                val st = computeStatus()
                publish(st)
                if (st != BackgroundMode.ST_NO_PERMISSION && st != BackgroundMode.ST_GPS_OFF) collectUpdates()
            }
        }
    }

    private suspend fun collectUpdates() {
        try {
            LocationHelper.updates(
                applicationContext, 30_000L, Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15f
            ).collect { loc ->
                val fix = Fix(
                    loc.latitude, loc.longitude,
                    if (loc.hasAccuracy()) loc.accuracy else 0f,
                    loc.elapsedRealtimeNanos / 1_000_000L
                )
                val now = SystemClock.elapsedRealtime()
                if (!FixFilter.valid(fix, now) || !FixFilter.accept(lastFix, fix, now)) return@collect
                lastFix = fix
                Prefs.edit {
                    putLong(Keys.BG_LAT, fix.lat.toBits())
                    putLong(Keys.BG_LNG, fix.lng.toBits())
                    putLong(Keys.BG_LOC_AT, System.currentTimeMillis())
                }
                publish(BackgroundMode.ST_OK)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(BackgroundMode.ST_SEARCHING)
            delay(15_000L)
        }
    }

    /** Detects permission revocation, GPS toggles and long signal loss without restarting healthy updates. */
    private suspend fun supervise() {
        while (scope.isActive) {
            delay(30_000L)
            val st = computeStatus()
            if (st == BackgroundMode.ST_OK || st == BackgroundMode.ST_SEARCHING) {
                if (lastStatus == BackgroundMode.ST_NO_PERMISSION || lastStatus == BackgroundMode.ST_GPS_OFF) tick.value++
                else publish(st)
            } else if (st != lastStatus) tick.value++
        }
    }

    private fun computeStatus(): String {
        val app = applicationContext
        if (!BackgroundMode.permissionsOk(app)) return BackgroundMode.ST_NO_PERMISSION
        if (!LocationHelper.isLocationEnabled(app)) return BackgroundMode.ST_GPS_OFF
        val f = lastFix ?: return BackgroundMode.ST_SEARCHING
        val age = SystemClock.elapsedRealtime() - f.elapsedMs
        return if (age > 120_000L) BackgroundMode.ST_SEARCHING else BackgroundMode.ST_OK
    }

    @SuppressLint("MissingPermission")
    private fun publish(st: String) {
        val app = applicationContext
        if (st != lastStatus) {
            lastStatus = st
            Prefs.edit { putString(Keys.BG_STATUS, st) }
            BgWidget.refresh(app)
            when (st) {
                BackgroundMode.ST_NO_PERMISSION -> Notifier.alert(
                    app, Notifier.ID_TRACK_ALERT, t("Location permission required"),
                    t("Allow location \"All the time\" so background tracking can continue.")
                )
                BackgroundMode.ST_GPS_OFF -> Notifier.alert(
                    app, Notifier.ID_TRACK_ALERT, t("GPS is turned off"),
                    t("Turn on location services so background tracking can continue.")
                )
                else -> Notifier.clear(app, Notifier.ID_TRACK_ALERT)
            }
        }
        val text = statusText(st)
        if (text != lastText) {
            lastText = text
            if (BackgroundMode.notificationsGranted(app)) {
                NotificationManagerCompat.from(app).notify(Notifier.ID_TRACK, Notifier.tracking(app, text))
            }
        }
    }

    private fun statusText(st: String): String = when (st) {
        BackgroundMode.ST_OK -> t("GPS: tracking")
        BackgroundMode.ST_GPS_OFF -> t("GPS: location services are off")
        BackgroundMode.ST_NO_PERMISSION -> t("GPS: location permission missing")
        else -> t("GPS: searching for signal…")
    }

    private fun registerNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        cm = manager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Connectivity is back (or switched Wi-Fi/mobile): flush anything queued while offline.
                scope.launch {
                    val s = Prefs.now()
                    if (s.notifyEnabled && s.ghReady && AppDb.get(applicationContext).outbox().all().isNotEmpty()) {
                        SendWorker.enqueue(applicationContext)
                    }
                }
            }
        }
        netCallback = cb
        try {
            manager.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            netCallback = null
        }
    }

    private fun registerProviders() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                tick.value++
            }
        }
        providerReceiver = r
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, filter)
    }

    override fun onDestroy() {
        netCallback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        providerReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        scope.cancel()
        job = null
        BackgroundMode.setAlive(false)
        BgWidget.refresh(applicationContext)
        super.onDestroy()
    }
}
