package ir.mmdkavir.smartattendance.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.Prefs

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        val state = Prefs.now()
        if (!state.setupDone || !state.monitoring) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Prefs.edit {
                putBoolean(Keys.GEO_OK, false)
                putString(Keys.GEO_ERR, "Geofence error code ${event.errorCode}")
            }
            return
        }
        val loc = event.triggeringLocation
        val time = loc?.time ?: System.currentTimeMillis()
        VerifyService.start(context.applicationContext, event.geofenceTransition, time, loc)
    }
}
