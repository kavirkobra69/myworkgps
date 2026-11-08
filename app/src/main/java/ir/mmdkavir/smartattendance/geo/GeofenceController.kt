package ir.mmdkavir.smartattendance.geo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.Prefs

object GeofenceController {
    private const val ACTION = "ir.mmdkavir.smartattendance.GEOFENCE_EVENT"
    private const val REQUEST_ID = "workplace"

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, GeofenceReceiver::class.java).setAction(ACTION),
            flags
        )
    }

    private fun signature(s: AppSettings) = "${s.lat},${s.lng},${s.radius},${s.dwellSec}"

    fun canRegister(context: Context, s: AppSettings): Boolean =
        s.setupDone && s.monitoring && s.hasLocation &&
            LocationHelper.hasFine(context) && LocationHelper.hasBackground(context)

    fun ensure(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        val s = Prefs.now()
        if (canRegister(app, s) && (!s.geoOk || s.geoSig != signature(s))) register(app)
    }

    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        val s = Prefs.now()
        if (!s.monitoring) {
            unregister(app)
            return
        }
        if (!canRegister(app, s)) {
            Prefs.edit {
                putBoolean(Keys.GEO_OK, false)
                putString(Keys.GEO_ERR, "Location permission (Allow all the time) or workplace is not set")
            }
            return
        }
        if (!LocationHelper.playServicesOk(app)) {
            Prefs.edit {
                putBoolean(Keys.GEO_OK, false)
                putString(Keys.GEO_ERR, "Google Play services unavailable: using 15-minute fallback checks")
            }
            return
        }

        val transitions = if (s.dwellSec > 0) {
            Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL or
                Geofence.GEOFENCE_TRANSITION_EXIT
        } else {
            Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
        }

        val builder = Geofence.Builder()
            .setRequestId(REQUEST_ID)
            .setCircularRegion(s.lat, s.lng, s.radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitions)
            .setNotificationResponsiveness(30_000)
        if (s.dwellSec > 0) builder.setLoiteringDelay(s.dwellSec * 1000)

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(builder.build())
            .build()

        val client = LocationServices.getGeofencingClient(app)
        val pi = pendingIntent(app)
        val sig = signature(s)
        client.removeGeofences(pi).addOnCompleteListener {
            client.addGeofences(request, pi)
                .addOnSuccessListener {
                    Prefs.edit {
                        putBoolean(Keys.GEO_OK, true)
                        putString(Keys.GEO_ERR, "")
                        putString(Keys.GEO_SIG, sig)
                    }
                }
                .addOnFailureListener { e ->
                    Prefs.edit {
                        putBoolean(Keys.GEO_OK, false)
                        putString(Keys.GEO_ERR, e.message ?: "Geofence registration failed")
                    }
                }
        }
    }

    fun unregister(context: Context) {
        val app = context.applicationContext
        Prefs.init(app)
        if (LocationHelper.playServicesOk(app)) {
            LocationServices.getGeofencingClient(app).removeGeofences(pendingIntent(app))
        }
        Prefs.edit {
            putBoolean(Keys.GEO_OK, false)
            putString(Keys.GEO_SIG, "")
        }
    }
}
