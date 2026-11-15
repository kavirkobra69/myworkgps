package ir.mmdkavir.smartattendance.bg

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import ir.mmdkavir.smartattendance.R
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.Person
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.LocationHelper
import ir.mmdkavir.smartattendance.geo.Notifier
import ir.mmdkavir.smartattendance.i18n.t
import ir.mmdkavir.smartattendance.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Home-screen widget with fixed pages: 1 = ON/OFF, 2 = EXIT, 3… = one page per person. The current page is drawn
 * completely in one RemoteViews (no collection view), with ‹ › arrows to move between pages.
 */
class BgWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext
        Prefs.init(app)
        // goAsync keeps the process alive until the page is really drawn (otherwise the widget can stay blank).
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BackgroundMode.recover(app)
                redraw(app)
            } catch (e: Exception) {
                // The next refresh retries.
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Prefs.init(app)
        when (intent.action) {
            ACTION_ENABLE -> {
                if (!BackgroundMode.enable(app)) askPermissions(app)
            }
            ACTION_DISABLE -> BackgroundMode.disable(app)
            ACTION_NEXT -> movePage(app, 1)
            ACTION_PREV -> movePage(app, -1)
            ACTION_PAGE -> onPageTap(app, intent)
            else -> super.onReceive(context, intent)
        }
    }

    private fun movePage(app: Context, step: Int) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val count = 2 + AppDb.get(app).people().all().size
                val page = ((Prefs.now().widgetPage + step) % count + count) % count
                Prefs.edit { putInt(Keys.WIDGET_PAGE, page) }
                redraw(app)
            } finally {
                pending.finish()
            }
        }
    }

    /** A tap on the current page. */
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
        redraw(app)
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
        const val ACTION_NEXT = "ir.mmdkavir.smartattendance.WIDGET_NEXT"
        const val ACTION_PREV = "ir.mmdkavir.smartattendance.WIDGET_PREV"
        const val EXTRA_KIND = "page_kind"
        const val EXTRA_ID = "page_id"
        const val KIND_BG = "bg"
        const val KIND_EXIT = "exit"
        const val KIND_PERSON = "person"

        private val drawLock = Mutex()

        /** Redraws every placed widget from the real state. Calls are serialized so an old picture never wins. */
        suspend fun redraw(context: Context) {
            val app = context.applicationContext
            drawLock.withLock {
                Prefs.init(app)
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(ComponentName(app, BgWidget::class.java))
                if (ids.isEmpty()) return
                val views = render(app, AppDb.get(app).people().all())
                ids.forEach { mgr.updateAppWidget(it, views) }
            }
        }

        /** Fire-and-forget redraw for callers that stay alive on their own (service, app). */
        fun refresh(context: Context) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    redraw(app)
                } catch (e: Exception) {
                    // Keep the previous picture; the next refresh retries.
                }
            }
        }

        private fun broadcast(app: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getBroadcast(
                app, code, intent.setClass(app, BgWidget::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun render(app: Context, people: List<Person>): RemoteViews {
            val count = 2 + people.size
            val page = Prefs.now().widgetPage.coerceIn(0, count - 1)
            val v = RemoteViews(app.packageName, R.layout.widget_pages)
            v.setTextViewText(R.id.page_no, String.format(Locale.US, "%d/%d", page + 1, count))
            val action = Intent(ACTION_PAGE)
            when {
                page == 0 -> {
                    val state = BackgroundMode.uiState()
                    val (bg, dot, label) = when (state) {
                        BgUiState.ACTIVE -> Triple(R.drawable.widget_bg_on, R.drawable.widget_dot_on, t("ON"))
                        BgUiState.ATTENTION -> Triple(R.drawable.widget_bg_on, R.drawable.widget_dot_warn, "!")
                        BgUiState.OFF -> Triple(R.drawable.widget_bg_off, R.drawable.widget_dot_off, t("OFF"))
                    }
                    v.setInt(R.id.page_root, "setBackgroundResource", bg)
                    v.setInt(R.id.page_dot, "setBackgroundResource", dot)
                    v.setTextViewText(R.id.page_title, label)
                    v.setTextViewText(R.id.page_sub, t("Background mode"))
                    v.setContentDescription(
                        R.id.page_root,
                        t("Smart Attendance background mode") + ": " + when (state) {
                            BgUiState.ACTIVE -> t("active")
                            BgUiState.ATTENTION -> t("needs attention")
                            BgUiState.OFF -> t("inactive")
                        }
                    )
                    action.putExtra(EXTRA_KIND, KIND_BG)
                }
                page == 1 -> {
                    v.setInt(R.id.page_root, "setBackgroundResource", R.drawable.widget_bg_exit)
                    v.setInt(R.id.page_dot, "setBackgroundResource", R.drawable.widget_dot_off)
                    v.setTextViewText(R.id.page_title, t("EXIT"))
                    v.setTextViewText(R.id.page_sub, t("Tap to send exit"))
                    v.setContentDescription(R.id.page_root, t("Send a message that I left work"))
                    action.putExtra(EXTRA_KIND, KIND_EXIT)
                }
                else -> {
                    val p = people[page - 2]
                    v.setInt(R.id.page_root, "setBackgroundResource", if (p.enabled) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
                    v.setInt(R.id.page_dot, "setBackgroundResource", if (p.enabled) R.drawable.widget_dot_on else R.drawable.widget_dot_off)
                    v.setTextViewText(R.id.page_title, p.name)
                    v.setTextViewText(R.id.page_sub, if (p.enabled) t("Tracking on") else t("Tracking off"))
                    v.setContentDescription(R.id.page_root, p.name + ": " + if (p.enabled) t("Tracking on") else t("Tracking off"))
                    action.putExtra(EXTRA_KIND, KIND_PERSON).putExtra(EXTRA_ID, p.id)
                }
            }
            // A distinct request code per page keeps each page's action separate.
            v.setOnClickPendingIntent(R.id.page_tap, broadcast(app, 100 + page, action))
            v.setOnClickPendingIntent(R.id.nav_prev, broadcast(app, 14, Intent(ACTION_PREV)))
            v.setOnClickPendingIntent(R.id.nav_next, broadcast(app, 15, Intent(ACTION_NEXT)))
            v.setContentDescription(R.id.nav_prev, t("Previous page"))
            v.setContentDescription(R.id.nav_next, t("Next page"))
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
