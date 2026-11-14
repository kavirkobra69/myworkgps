package ir.mmdkavir.smartattendance.bg

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.LocationHelper
import ir.mmdkavir.smartattendance.i18n.t
import ir.mmdkavir.smartattendance.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Invisible trampoline for taps on the widget pages. An activity started from a widget click is always allowed,
 * so it can start the foreground service, ask for permissions through the app, and finishes immediately.
 */
class WidgetActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        Prefs.init(app)
        val kind = intent.getStringExtra(EXTRA_KIND)
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        when (kind) {
            KIND_BG -> toggleBackground(app)
            KIND_EXIT -> async(app) { ExitReport.report(app) }
            KIND_PERSON -> async(app) { togglePerson(app, id) }
        }
        finish()
    }

    private fun toggleBackground(app: Context) {
        if (Prefs.now().bgMode) {
            BackgroundMode.disable(app)
            return
        }
        val ready = BackgroundMode.permissionsOk(app) &&
            (LocationHelper.isLocationEnabled(app) || LocationHelper.canWriteSecure(app))
        if (!ready || !BackgroundMode.enable(app)) {
            // Permissions and the location prompt are shown only through the app UI.
            startActivity(
                Intent(app, MainActivity::class.java)
                    .putExtra(BgWidget.EXTRA_ENABLE_BG, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    private suspend fun togglePerson(app: Context, id: Long): String {
        val dao = AppDb.get(app).people()
        val person = dao.all().firstOrNull { it.id == id } ?: return t("This person no longer exists")
        dao.setEnabled(id, !person.enabled)
        BgWidget.refresh(app)
        return if (person.enabled) t("%s: tracking off", person.name) else t("%s: tracking on", person.name)
    }

    private fun async(app: Context, block: suspend () -> String) {
        CoroutineScope(Dispatchers.IO).launch {
            val message = try {
                block()
            } catch (e: Exception) {
                t("Could not complete the widget action")
            }
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        const val EXTRA_KIND = "page_kind"
        const val EXTRA_ID = "page_id"
        const val KIND_BG = "bg"
        const val KIND_EXIT = "exit"
        const val KIND_PERSON = "person"
    }
}
