package ir.mmdkavir.smartattendance.bg

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import ir.mmdkavir.smartattendance.R
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.Notifier
import ir.mmdkavir.smartattendance.i18n.t
import ir.mmdkavir.smartattendance.ui.MainActivity

/** 1×1 home-screen switch for background mode. Rendering always reads the real service state. */
class BgWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Prefs.init(context.applicationContext)
        BackgroundMode.recover(context.applicationContext)
        ids.forEach { manager.updateAppWidget(it, render(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Prefs.init(app)
        when (intent.action) {
            ACTION_ENABLE -> {
                if (!BackgroundMode.enable(app)) askPermissions(app)
            }
            ACTION_DISABLE -> BackgroundMode.disable(app)
            else -> super.onReceive(context, intent)
        }
    }

    private fun askPermissions(app: Context) {
        Toast.makeText(app, t("Allow location \"All the time\" first, then tap the widget again."), Toast.LENGTH_LONG).show()
        Notifier.alert(
            app, Notifier.ID_TRACK_ALERT, t("Location permission required"),
            t("Open the app to allow location \"All the time\" and turn on background mode.")
        )
        refresh(app)
    }

    companion object {
        const val ACTION_ENABLE = "ir.mmdkavir.smartattendance.BG_ENABLE"
        const val ACTION_DISABLE = "ir.mmdkavir.smartattendance.BG_DISABLE"
        const val EXTRA_ENABLE_BG = "enable_background_mode"

        fun refresh(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, BgWidget::class.java))
            if (ids.isEmpty()) return
            val views = render(app)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun render(app: Context): RemoteViews {
            val state = BackgroundMode.uiState()
            val v = RemoteViews(app.packageName, R.layout.widget_bg)
            val (bg, dot, label) = when (state) {
                BgUiState.ACTIVE -> Triple(R.drawable.widget_bg_on, R.drawable.widget_dot_on, t("ON"))
                BgUiState.ATTENTION -> Triple(R.drawable.widget_bg_on, R.drawable.widget_dot_warn, "!")
                BgUiState.OFF -> Triple(R.drawable.widget_bg_off, R.drawable.widget_dot_off, t("OFF"))
            }
            v.setInt(R.id.widget_root, "setBackgroundResource", bg)
            v.setInt(R.id.widget_dot, "setBackgroundResource", dot)
            v.setTextViewText(R.id.widget_label, label)
            v.setContentDescription(
                R.id.widget_root,
                t("Smart Attendance background mode") + ": " + when (state) {
                    BgUiState.ACTIVE -> t("active")
                    BgUiState.ATTENTION -> t("needs attention")
                    BgUiState.OFF -> t("inactive")
                }
            )
            v.setOnClickPendingIntent(R.id.widget_root, tapIntent(app, state))
            return v
        }

        private fun tapIntent(app: Context, state: BgUiState): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val enabled = Prefs.now().bgMode
            if (!enabled && (!BackgroundMode.permissionsOk(app) || !ir.mmdkavir.smartattendance.geo.LocationHelper.isLocationEnabled(app))) {
                // Permissions and the location-services prompt are shown only through the app UI.
                return PendingIntent.getActivity(
                    app, 10,
                    Intent(app, MainActivity::class.java)
                        .putExtra(EXTRA_ENABLE_BG, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    flags
                )
            }
            val action = if (state == BgUiState.OFF) ACTION_ENABLE else ACTION_DISABLE
            return PendingIntent.getBroadcast(
                app, 11, Intent(app, BgWidget::class.java).setAction(action), flags
            )
        }

        fun canPin(context: Context): Boolean =
            AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

        fun requestPin(context: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(context)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            return mgr.requestPinAppWidget(ComponentName(context, BgWidget::class.java), null, null)
        }
    }
}
