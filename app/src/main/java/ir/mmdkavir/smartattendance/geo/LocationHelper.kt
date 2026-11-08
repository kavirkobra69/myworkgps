package ir.mmdkavir.smartattendance.geo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private class SimpleListener(val onLoc: (Location) -> Unit) : LocationListener {
    override fun onLocationChanged(location: Location) = onLoc(location)

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {
    }

    override fun onProviderDisabled(provider: String) {
    }
}

object LocationHelper {

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasFine(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasCoarse(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else hasFine(context)

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun playServicesOk(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    suspend fun currentFix(context: Context, timeoutMs: Long = 25_000L): Location? {
        if (!hasFine(context) || !isLocationEnabled(context)) return null
        return if (playServicesOk(context)) fused(context, timeoutMs) else platform(context, timeoutMs)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fused(context: Context, timeoutMs: Long): Location? {
        val cts = CancellationTokenSource()
        return try {
            withTimeoutOrNull(timeoutMs) {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .await()
            }
        } catch (e: Exception) {
            null
        } finally {
            cts.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun platform(context: Context, timeoutMs: Long): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location> { cont ->
                val listener = SimpleListener { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { lm.isProviderEnabled(it) }
                providers.forEach {
                    lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper())
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                if (providers.isEmpty()) cont.cancel()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun updates(context: Context, intervalMs: Long = 5_000L): Flow<Location> = callbackFlow {
        if (!hasFine(context) && !hasCoarse(context)) {
            close()
            return@callbackFlow
        }
        if (playServicesOk(context)) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { trySend(it) }
                }
            }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            awaitClose { client.removeLocationUpdates(callback) }
        } else {
            val lm = context.getSystemService(LocationManager::class.java)
            val listener = SimpleListener { trySend(it) }
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }
                .forEach { lm.requestLocationUpdates(it, intervalMs, 0f, listener, Looper.getMainLooper()) }
            awaitClose { lm.removeUpdates(listener) }
        }
    }
}
