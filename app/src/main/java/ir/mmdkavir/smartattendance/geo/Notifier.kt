package ir.mmdkavir.smartattendance.geo

import ir.mmdkavir.smartattendance.i18n.t
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
    const val CH_TRACK = "tracking"

    const val ID_ARRIVAL = 1001
    const val ID_GPS = 1002
    const val ID_PERMISSION = 1003
    const val ID_TELEGRAM = 1004
    const val ID_VERIFY = 2001
    const val ID_TRACK = 2002
    const val ID_TRACK_ALERT = 1005

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ATTENDANCE, t("Attendance"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = t("Confirmation when your arrival is recorded")
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, t("Arrival verification"), NotificationManager.IMPORTANCE_LOW).apply {
                description = t("Shown briefly while an arrival is being verified")
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACK, t("Background mode"), NotificationManager.IMPORTANCE_LOW).apply {
                description = t("Persistent notification while background tracking is on")
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, t("Monitoring alerts"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = t("GPS disabled, permission removed or Telegram problems")
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
            .setContentTitle(t("Verifying arrival"))
            .setContentText(t("Checking your location at the workplace"))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun tracking(context: Context, status: String): Notification {
        ensureChannels(context)
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, ir.mmdkavir.smartattendance.bg.BgWidget::class.java)
                .setAction(ir.mmdkavir.smartattendance.bg.BgWidget.ACTION_DISABLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = t("Background mode: active") + "\n" + status
        return NotificationCompat.Builder(context, CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t("Smart Attendance"))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(t("Background mode: active"))
            .setContentIntent(contentIntent(context))
            .addAction(0, t("Disable background mode"), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
