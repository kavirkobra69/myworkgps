package ir.mmdkavir.smartattendance.bg

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import android.widget.Toast
import ir.mmdkavir.smartattendance.R
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.Notifier
import ir.mmdkavir.smartattendance.i18n.t

/** Multi-page home-screen widget: ON/OFF, EXIT, then one page per person. State is read from the real service. */
class BgWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext
        Prefs.init(app)
        BackgroundMode.recover(app)
        ids.forEach { manager.updateAppWidget(it, build(app, it)) }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.pages)
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

        /** Re-renders every placed widget and reloads its pages (called when state or the people list changes). */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, BgWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { mgr.updateAppWidget(it, build(app, it)) }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.pages)
        }

        private fun build(app: Context, widgetId: Int): RemoteViews {
            val v = RemoteViews(app.packageName, R.layout.widget_pages)
            val adapter = Intent(app, WidgetPagesService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
            v.setRemoteAdapter(R.id.pages, adapter)
            // The template must be mutable so each page can add its own action as a fill-in intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            v.setPendingIntentTemplate(
                R.id.pages,
                PendingIntent.getActivity(app, 13, Intent(app, WidgetActionActivity::class.java), flags)
            )
            return v
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
