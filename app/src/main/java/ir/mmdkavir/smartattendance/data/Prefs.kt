package ir.mmdkavir.smartattendance.data

import android.content.Context
import android.content.SharedPreferences
import ir.mmdkavir.smartattendance.i18n.Lang
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Keys {
    const val NAME = "name"
    const val EMP = "employee_id"
    const val WORKPLACE = "workplace"
    const val HAS_LOC = "has_location"
    const val LAT = "lat_bits"
    const val LNG = "lng_bits"
    const val RADIUS = "radius"
    const val DWELL = "dwell_sec"
    const val CHAT = "chat_id"
    const val OLD_URL = "worker_url"
    const val OLD_APPKEY = "app_key"
    const val GH_TOKEN = "gh_token"
    const val APP_LANG = "app_lang"
    const val GH_URL = "gh_repo_url"
    const val GH_OWNER = "gh_owner"
    const val GH_REPO = "gh_repo"
    const val GH_BRANCH = "gh_branch"
    const val GH_LOGIN = "gh_login"
    const val GH_STATUS = "gh_status"
    const val GH_ERR = "gh_error"
    const val SECRETS_OK = "secrets_ok"
    const val NOTIFY = "notify_enabled"
    const val SETUP = "setup_done"
    const val MONITOR = "monitoring"
    const val IN_VISIT = "in_visit"
    const val LAST_ARRIVAL = "last_arrival"
    const val LAST_EXIT = "last_exit"
    const val TG_STATUS = "tg_status"
    const val TG_ERR = "tg_error"
    const val TG_BOT = "tg_bot"
    const val GEO_OK = "geo_ok"
    const val GEO_ERR = "geo_error"
    const val GEO_SIG = "geo_sig"
    const val GPS_OFF = "gps_off_seen"
    const val MSG_LANG = "msg_lang"
    const val DATE_FMT = "date_format"
    const val ENTER_AT = "enter_at"
}

data class AppSettings(
    val name: String = "",
    val employeeId: String = "",
    val workplace: String = "",
    val hasLocation: Boolean = false,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val radius: Int = 100,
    val dwellSec: Int = 10,
    val chatId: String = "",
    val appLang: String = "en",
    val ghRepoUrl: String = "",
    val ghOwner: String = "",
    val ghRepo: String = "",
    val ghBranch: String = "",
    val ghLogin: String = "",
    val hasGhToken: Boolean = false,
    val ghStatus: Int = 0,
    val ghError: String = "",
    val secretsOk: Boolean = false,
    val notifyEnabled: Boolean = false,
    val setupDone: Boolean = false,
    val monitoring: Boolean = true,
    val inVisit: Boolean = false,
    val lastArrival: Long = 0L,
    val lastExit: Long = 0L,
    val tgStatus: Int = 0,
    val tgError: String = "",
    val tgBot: String = "",
    val geoOk: Boolean = false,
    val geoError: String = "",
    val geoSig: String = "",
    val gpsOffSeen: Boolean = false,
    val msgLang: String = "en",
    val dateFormat: String = "gregorian",
    val enterAt: Long = 0L
) {
    val ghReady: Boolean get() = hasGhToken && ghOwner.isNotBlank() && ghRepo.isNotBlank() && ghBranch.isNotBlank()
}

object Prefs {
    private const val FILE = "secure_prefs"

    private lateinit var sp: SharedPreferences
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = read()
        Lang.code = _state.value.appLang
    }

    @Synchronized
    fun init(context: Context) {
        if (::sp.isInitialized) return
        val app = context.applicationContext
        sp = try {
            create(app)
        } catch (e: Exception) {
            app.deleteSharedPreferences(FILE)
            create(app)
        }
        sp.edit().remove(Keys.OLD_URL).remove(Keys.OLD_APPKEY).apply()
        sp.registerOnSharedPreferenceChangeListener(listener)
        _state.value = read()
        Lang.code = _state.value.appLang
    }

    private fun create(app: Context): SharedPreferences {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun now(): AppSettings = read()

    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
    }

    fun ghToken(): String = sp.getString(Keys.GH_TOKEN, "") ?: ""

    fun setTg(status: Int, error: String, bot: String? = null) {
        edit {
            putInt(Keys.TG_STATUS, status)
            putString(Keys.TG_ERR, error)
            if (bot != null) putString(Keys.TG_BOT, bot)
        }
    }

    private fun read(): AppSettings = AppSettings(
        name = sp.getString(Keys.NAME, "") ?: "",
        employeeId = sp.getString(Keys.EMP, "") ?: "",
        workplace = sp.getString(Keys.WORKPLACE, "") ?: "",
        hasLocation = sp.getBoolean(Keys.HAS_LOC, false),
        lat = Double.fromBits(sp.getLong(Keys.LAT, 0L)),
        lng = Double.fromBits(sp.getLong(Keys.LNG, 0L)),
        radius = sp.getInt(Keys.RADIUS, 100),
        dwellSec = sp.getInt(Keys.DWELL, 10),
        chatId = sp.getString(Keys.CHAT, "") ?: "",
        appLang = Lang.normalize(sp.getString(Keys.APP_LANG, null)),
        ghRepoUrl = sp.getString(Keys.GH_URL, "") ?: "",
        ghOwner = sp.getString(Keys.GH_OWNER, "") ?: "",
        ghRepo = sp.getString(Keys.GH_REPO, "") ?: "",
        ghBranch = sp.getString(Keys.GH_BRANCH, "") ?: "",
        ghLogin = sp.getString(Keys.GH_LOGIN, "") ?: "",
        hasGhToken = !sp.getString(Keys.GH_TOKEN, "").isNullOrEmpty(),
        ghStatus = sp.getInt(Keys.GH_STATUS, 0),
        ghError = sp.getString(Keys.GH_ERR, "") ?: "",
        secretsOk = sp.getBoolean(Keys.SECRETS_OK, false),
        notifyEnabled = sp.getBoolean(Keys.NOTIFY, false),
        setupDone = sp.getBoolean(Keys.SETUP, false),
        monitoring = sp.getBoolean(Keys.MONITOR, true),
        inVisit = sp.getBoolean(Keys.IN_VISIT, false),
        lastArrival = sp.getLong(Keys.LAST_ARRIVAL, 0L),
        lastExit = sp.getLong(Keys.LAST_EXIT, 0L),
        tgStatus = sp.getInt(Keys.TG_STATUS, 0),
        tgError = sp.getString(Keys.TG_ERR, "") ?: "",
        tgBot = sp.getString(Keys.TG_BOT, "") ?: "",
        geoOk = sp.getBoolean(Keys.GEO_OK, false),
        geoError = sp.getString(Keys.GEO_ERR, "") ?: "",
        geoSig = sp.getString(Keys.GEO_SIG, "") ?: "",
        gpsOffSeen = sp.getBoolean(Keys.GPS_OFF, false),
        msgLang = sp.getString(Keys.MSG_LANG, "en") ?: "en",
        dateFormat = sp.getString(Keys.DATE_FMT, "gregorian") ?: "gregorian",
        enterAt = sp.getLong(Keys.ENTER_AT, 0L)
    )
}
