package ir.mmdkavir.smartattendance.bg

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.geo.LocationHelper
import ir.mmdkavir.smartattendance.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            ACTION_PAGE -> onPageTap(app, intent)
            else -> super.onReceive(context, intent)
        }
    }

    /** A tap on a page. Handled in the receiver (no activity is launched), so the stack is not disturbed. */
    private fun onPageTap(app: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_BG -> toggleBackground(app)
            KIND_EXIT -> background(app) { ExitReport.report(app) }
            KIND_PERSON -> background(app) { togglePerson(app, id) }
        }
    }

    private fun toggleBackground(app: Context) {
        if (Prefs.now().bgMode) {
            BackgroundMode.disable(app)
            return
        }
        val ready = BackgroundMode.permissionsOk(app) &&
            (LocationHelper.isLocationEnabled(app) || LocationHelper.canWriteSecure(app))
        if (ready && BackgroundMode.enable(app)) return
        // Permissions and the location prompt are shown only through the app UI.
        try {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .putExtra(EXTRA_ENABLE_BG, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
            askPermissions(app)
        }
    }

    private suspend fun togglePerson(app: Context, id: Long): String {
        val dao = AppDb.get(app).people()
        val person = dao.all().firstOrNull { it.id == id } ?: return t("This person no longer exists")
        dao.setEnabled(id, !person.enabled)
        refresh(app)
        return if (person.enabled) t("%s: tracking off", person.name) else t("%s: tracking on", person.name)
    }

    private fun BroadcastReceiver.background(app: Context, block: suspend () -> String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val message = try {
                block()
            } catch (e: Exception) {
                t("Could not complete the widget action")
            } finally {
                pending.finish()
            }
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
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
        const val ACTION_PAGE = "ir.mmdkavir.smartattendance.WIDGET_PAGE"
        const val EXTRA_KIND = "page_kind"
        const val EXTRA_ID = "page_id"
        const val KIND_BG = "bg"
        const val KIND_EXIT = "exit"
        const val KIND_PERSON = "person"

        /** Re-renders every placed widget and reloads its pages (called when state or the people list changes). */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, BgWidget::class.java))
            if (ids.isEmpty()) return
            // Only the page data is reloaded: replacing the whole widget makes the stack blank until it is swiped.
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
                PendingIntent.getBroadcast(app, 13, Intent(app, BgWidget::class.java).setAction(ACTION_PAGE), flags)
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
