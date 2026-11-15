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
import android.widget.RemoteViewsService
import ir.mmdkavir.smartattendance.R
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.Person
import ir.mmdkavir.smartattendance.data.Prefs
import kotlinx.coroutines.runBlocking

/**
 * Same pages as [BgWidget] (ON/OFF, EXIT, people) but as a stack: drag up or down on the widget to change page.
 * Taps are handled by [BgWidget] through the same broadcast action; page data is reloaded without rebuilding the widget.
 */
class SwipeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext
        Prefs.init(app)
        ids.forEach { manager.updateAppWidget(it, build(app, it)) }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.pages)
    }

    companion object {
        private fun build(app: Context, widgetId: Int): RemoteViews {
            val v = RemoteViews(app.packageName, R.layout.widget_swipe)
            val adapter = Intent(app, SwipePagesService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
            v.setRemoteAdapter(R.id.pages, adapter)
            // Mutable so each page can add its own kind/id as a fill-in intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            v.setPendingIntentTemplate(
                R.id.pages,
                PendingIntent.getBroadcast(app, 16, Intent(app, BgWidget::class.java).setAction(BgWidget.ACTION_PAGE), flags)
            )
            return v
        }

        fun requestPin(context: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(context)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            return mgr.requestPinAppWidget(ComponentName(context, SwipeWidget::class.java), null, null)
        }
    }
}

class SwipePagesService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = SwipeFactory(applicationContext)
}

private class SwipeFactory(private val app: Context) : RemoteViewsService.RemoteViewsFactory {
    private var specs: List<PageSpec> = emptyList()

    override fun onCreate() { load() }
    override fun onDataSetChanged() { load() }
    override fun onDestroy() {}

    private fun load() {
        Prefs.init(app)
        val people: List<Person> = try {
            runBlocking { AppDb.get(app).people().all() }
        } catch (e: Exception) {
            emptyList()
        }
        specs = BgWidget.pageSpecs(people)
    }

    override fun getCount(): Int = specs.size

    override fun getViewAt(position: Int): RemoteViews {
        val v = RemoteViews(app.packageName, R.layout.widget_swipe_item)
        val spec = specs.getOrNull(position) ?: return v
        BgWidget.applyPage(v, spec)
        BgWidget.applyDots(v, position, specs.size)
        v.setOnClickFillInIntent(
            R.id.page_root,
            Intent().putExtra(BgWidget.EXTRA_KIND, spec.kind).putExtra(BgWidget.EXTRA_ID, spec.id)
        )
        return v
    }

    /** Shown by the launcher while a page is being prepared, instead of a blank card. */
    override fun getLoadingView(): RemoteViews = RemoteViews(app.packageName, R.layout.widget_swipe_item)

    override fun getViewTypeCount(): Int = 1
    override fun hasStableIds(): Boolean = false
    override fun getItemId(position: Int): Long = position.toLong()
}
