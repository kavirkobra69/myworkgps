package ir.mmdkavir.smartattendance.geo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ir.mmdkavir.smartattendance.R
import ir.mmdkavir.smartattendance.ui.MainActivity

object Notifier {
    const val CH_ATTENDANCE = "attendance"
    const val CH_MONITOR = "monitor"
    const val CH_ALERT = "alerts"

    const val ID_ARRIVAL = 1001
    const val ID_GPS = 1002
    const val ID_PERMISSION = 1003
    const val ID_TELEGRAM = 1004
    const val ID_VERIFY = 2001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ATTENDANCE, "Attendance", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Confirmation when your arrival is recorded"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Arrival verification", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown briefly while an arrival is being verified"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Monitoring alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "GPS disabled, permission removed or Telegram problems"
            }
        )
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @SuppressLint("MissingPermission")
    fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun alert(context: Context, id: Int, title: String, text: String) =
        post(context, CH_ALERT, id, title, text)

    fun clear(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    fun verifying(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Verifying arrival")
            .setContentText("Checking your location at the workplace")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
