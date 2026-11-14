package ir.mmdkavir.smartattendance.bg

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import ir.mmdkavir.smartattendance.R
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.Person
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.i18n.t
import kotlinx.coroutines.runBlocking
import java.util.Locale

/** Pages of the widget, in a fixed order: 1 = ON/OFF, 2 = EXIT, 3… = one page per registered person. */
class WidgetPagesService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = PagesFactory(applicationContext)
}

private class PagesFactory(private val app: Context) : RemoteViewsService.RemoteViewsFactory {
    private var people: List<Person> = emptyList()

    override fun onCreate() {
        Prefs.init(app)
        load()
    }

    override fun onDataSetChanged() {
        Prefs.init(app)
        load()
    }

    private fun load() {
        people = try {
            runBlocking { AppDb.get(app).people().all() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onDestroy() {}

    override fun getCount(): Int = 2 + people.size

    override fun getViewAt(position: Int): RemoteViews {
        val v = RemoteViews(app.packageName, R.layout.widget_page)
        val total = count
        v.setTextViewText(R.id.page_no, String.format(Locale.US, "%d/%d", position + 1, total))
        val fill = Intent()
        when {
            position == 0 -> {
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
                fill.putExtra(WidgetActionActivity.EXTRA_KIND, WidgetActionActivity.KIND_BG)
            }
            position == 1 -> {
                v.setInt(R.id.page_root, "setBackgroundResource", R.drawable.widget_bg_exit)
                v.setInt(R.id.page_dot, "setBackgroundResource", R.drawable.widget_dot_off)
                v.setTextViewText(R.id.page_title, t("EXIT"))
                v.setTextViewText(R.id.page_sub, t("Tap to send exit"))
                v.setContentDescription(R.id.page_root, t("Send a message that I left work"))
                fill.putExtra(WidgetActionActivity.EXTRA_KIND, WidgetActionActivity.KIND_EXIT)
            }
            position - 2 in people.indices -> {
                val p = people[position - 2]
                v.setInt(R.id.page_root, "setBackgroundResource", if (p.enabled) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
                v.setInt(R.id.page_dot, "setBackgroundResource", if (p.enabled) R.drawable.widget_dot_on else R.drawable.widget_dot_off)
                v.setTextViewText(R.id.page_title, p.name)
                v.setTextViewText(R.id.page_sub, if (p.enabled) t("Tracking on") else t("Tracking off"))
                v.setContentDescription(R.id.page_root, p.name + ": " + if (p.enabled) t("Tracking on") else t("Tracking off"))
                fill.putExtra(WidgetActionActivity.EXTRA_KIND, WidgetActionActivity.KIND_PERSON)
                    .putExtra(WidgetActionActivity.EXTRA_ID, p.id)
            }
        }
        v.setOnClickFillInIntent(R.id.page_root, fill)
        return v
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun hasStableIds(): Boolean = true
    override fun getItemId(position: Int): Long = when {
        position == 0 -> -1L
        position == 1 -> -2L
        position - 2 in people.indices -> people[position - 2].id
        else -> position.toLong() + 1_000_000L
    }
}
