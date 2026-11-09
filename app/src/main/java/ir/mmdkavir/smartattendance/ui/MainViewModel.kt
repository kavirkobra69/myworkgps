package ir.mmdkavir.smartattendance.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mmdkavir.smartattendance.data.AppDb
import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import ir.mmdkavir.smartattendance.data.Keys
import ir.mmdkavir.smartattendance.data.Prefs
import ir.mmdkavir.smartattendance.geo.LocationHelper
import ir.mmdkavir.smartattendance.geo.Monitor
import ir.mmdkavir.smartattendance.net.DiagStep
import ir.mmdkavir.smartattendance.net.GhException
import ir.mmdkavir.smartattendance.net.GitHub
import ir.mmdkavir.smartattendance.net.Payload
import ir.mmdkavir.smartattendance.net.RepoRef
import ir.mmdkavir.smartattendance.update.InstallResult
import ir.mmdkavir.smartattendance.update.UpdateException
import ir.mmdkavir.smartattendance.update.UpdateInfo
import ir.mmdkavir.smartattendance.update.UpdateState
import ir.mmdkavir.smartattendance.update.Updater
import ir.mmdkavir.smartattendance.util.Geo
import ir.mmdkavir.smartattendance.work.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GeoStatus { NO_SETUP, PERMISSION, GPS_OFF, TELEGRAM_ERROR, INSIDE, OUTSIDE, ACTIVE }

data class SetupUi(
    val busy: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val success: String? = null
)

data class Device(
    val fine: Boolean = false,
    val background: Boolean = false,
    val gpsOn: Boolean = true,
    val notifications: Boolean = true
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)

    val settings: StateFlow<AppSettings> = Prefs.state

    val records: StateFlow<List<AttendanceRecord>> = db.attendance().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pending: StateFlow<Int> = db.outbox().observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _device = MutableStateFlow(Device())
    val device: StateFlow<Device> = _device

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _diagnostics = MutableStateFlow<String?>(null)
    val diagnostics: StateFlow<String?> = _diagnostics

    private val _setup = MutableStateFlow(SetupUi())
    val setup: StateFlow<SetupUi> = _setup

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    private var locationJob: Job? = null

    fun consumeDiagnostics() {
        _diagnostics.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun toast(text: String) {
        _message.value = text
    }

    fun refreshDevice(notificationsGranted: Boolean) {
        val c = getApplication<Application>()
        _device.value = Device(
            fine = LocationHelper.hasFine(c),
            background = LocationHelper.hasBackground(c),
            gpsOn = LocationHelper.isLocationEnabled(c),
            notifications = notificationsGranted
        )
        if (Prefs.now().setupDone) ir.mmdkavir.smartattendance.geo.GeofenceController.ensure(c)
    }

    fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        val c = getApplication<Application>()
        if (!LocationHelper.hasFine(c) && !LocationHelper.hasCoarse(c)) return
        locationJob = viewModelScope.launch {
            LocationHelper.updates(c, 5_000L)
                .catch { }
                .collect { _location.value = it }
        }
    }

    fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
    }

    suspend fun fetchCurrent(): Location? =
        LocationHelper.currentFix(getApplication(), 25_000L)

    fun status(s: AppSettings, d: Device, loc: Location?): GeoStatus = when {
        !s.setupDone || !s.hasLocation -> GeoStatus.NO_SETUP
        !d.fine || !d.background -> GeoStatus.PERMISSION
        !d.gpsOn -> GeoStatus.GPS_OFF
        s.notifyEnabled && s.tgStatus == 2 -> GeoStatus.TELEGRAM_ERROR
        s.inVisit -> GeoStatus.INSIDE
        loc != null && distance(s, loc) <= s.radius -> GeoStatus.INSIDE
        loc != null -> GeoStatus.OUTSIDE
        else -> GeoStatus.ACTIVE
    }

    fun distance(s: AppSettings, loc: Location): Double =
        Geo.distanceMeters(loc.latitude, loc.longitude, s.lat, s.lng)

    fun saveProfile(name: String, employeeId: String, workplace: String) {
        Prefs.edit {
            putString(Keys.NAME, name.trim())
            putString(Keys.EMP, employeeId.trim())
            putString(Keys.WORKPLACE, workplace.trim())
        }
    }

    fun saveWorkplace(lat: Double, lng: Double, radius: Int, workplace: String? = null) {
        Prefs.edit {
            putBoolean(Keys.HAS_LOC, true)
            putLong(Keys.LAT, lat.toBits())
            putLong(Keys.LNG, lng.toBits())
            putInt(Keys.RADIUS, radius.coerceIn(50, 1000))
            if (workplace != null) putString(Keys.WORKPLACE, workplace.trim())
        }
        applyMonitoring()
    }

    fun saveRadius(radius: Int) {
        Prefs.edit { putInt(Keys.RADIUS, radius.coerceIn(50, 1000)) }
        applyMonitoring()
    }

    fun saveDwell(seconds: Int) {
        Prefs.edit { putInt(Keys.DWELL, seconds.coerceIn(0, 300)) }
        applyMonitoring()
    }

    fun setNotify(enabled: Boolean) {
        Prefs.edit { putBoolean(Keys.NOTIFY, enabled) }
        if (enabled) SendWorker.enqueue(getApplication())
    }

    fun setMonitoring(enabled: Boolean) {
        Prefs.edit { putBoolean(Keys.MONITOR, enabled) }
        val c = getApplication<Application>()
        if (enabled) {
            Monitor.afterSetup(c)
            SendWorker.enqueue(c)
        } else {
            Monitor.stop(c)
        }
    }

    fun finishSetup() {
        Prefs.edit { putBoolean(Keys.SETUP, true) }
        Monitor.afterSetup(getApplication())
    }

    fun applyMonitoring() {
        if (Prefs.now().setupDone) Monitor.afterSetup(getApplication())
    }

    fun setMsgLang(lang: String) {
        Prefs.edit { putString(Keys.MSG_LANG, if (lang == "fa") "fa" else "en") }
    }

    fun setDateFormat(format: String) {
        Prefs.edit { putString(Keys.DATE_FMT, if (format == "jalali") "jalali" else "gregorian") }
    }

    private fun steps(title: String, list: List<DiagStep>): String {
        val ok = list.isNotEmpty() && list.all { it.ok }
        return (if (ok) "Everything works.\n\n" else "Something is not working.\n\n") +
            list.joinToString("\n\n") { (if (it.ok) "✓ " else "✗ ") + it.title + ": " + it.detail }
    }

    /** Validates token + repository, installs the workflow and checks the Telegram secrets. */
    fun connectGitHub(token: String, repoUrl: String) {
        if (_setup.value.busy) return
        val saved = Prefs.ghToken()
        val t = token.trim().ifBlank { saved }
        if (t.isBlank()) {
            toast("Enter your GitHub token first")
            return
        }
        viewModelScope.launch {
            _setup.value = SetupUi(busy = true, progress = "Connecting to GitHub…")
            val c = getApplication<Application>()
            try {
                val conn = withContext(Dispatchers.IO) { GitHub.connect(c, t, repoUrl) }
                Prefs.edit {
                    putString(Keys.GH_TOKEN, t)
                    putString(Keys.GH_URL, "https://github.com/${conn.repo.full}")
                    putString(Keys.GH_OWNER, conn.repo.owner)
                    putString(Keys.GH_REPO, conn.repo.name)
                    putString(Keys.GH_BRANCH, conn.branch)
                    putString(Keys.GH_LOGIN, conn.login)
                    putInt(Keys.GH_STATUS, 1)
                    putString(Keys.GH_ERR, "")
                    putBoolean(Keys.SECRETS_OK, conn.secretsPresent)
                }
                if (Prefs.now().chatId.isNotBlank() && conn.secretsPresent) Prefs.edit { putBoolean(Keys.NOTIFY, true) }
                _diagnostics.value = steps("GitHub", conn.steps)
                _setup.value = SetupUi(success = "Connected to ${conn.repo.full}")
            } catch (e: GitHub.StepsException) {
                Prefs.edit { putInt(Keys.GH_STATUS, 2); putString(Keys.GH_ERR, e.message ?: "Failed") }
                _diagnostics.value = steps("GitHub", e.steps)
                _setup.value = SetupUi(error = e.message)
            } catch (e: GhException) {
                Prefs.edit { putInt(Keys.GH_STATUS, 2); putString(Keys.GH_ERR, e.message ?: "Failed") }
                _setup.value = SetupUi(error = e.message)
            } catch (e: Exception) {
                _setup.value = SetupUi(error = "Connection failed (${e.javaClass.simpleName})")
            }
        }
    }

    /** Stores the bot token and chat ID as encrypted GitHub Actions secrets. Neither is kept on the phone except the chat ID. */
    fun saveTelegramSecrets(botToken: String, chatId: String) {
        val s = Prefs.now()
        if (!s.ghReady || s.ghStatus != 1) {
            toast("Connect GitHub first")
            return
        }
        val bot = botToken.trim()
        val chat = chatId.trim()
        if (!Regex("^\\d{5,}:[A-Za-z0-9_-]{20,}$").matches(bot)) {
            toast("Bot token format is wrong. It looks like 123456789:AAH...")
            return
        }
        if (!Regex("^-?\\d{1,20}$").matches(chat)) {
            toast("Chat ID must be a number (a group ID starts with -)")
            return
        }
        if (_setup.value.busy) return
        viewModelScope.launch {
            _setup.value = SetupUi(busy = true, progress = "Saving secrets to GitHub…")
            try {
                withContext(Dispatchers.IO) { GitHub.storeSecrets(Prefs.ghToken(), RepoRef(s.ghOwner, s.ghRepo), bot, chat) }
                Prefs.edit {
                    putString(Keys.CHAT, chat)
                    putBoolean(Keys.SECRETS_OK, true)
                    putBoolean(Keys.NOTIFY, true)
                    putInt(Keys.TG_STATUS, 0)
                    putString(Keys.TG_ERR, "")
                }
                _setup.value = SetupUi(success = "Telegram settings saved in GitHub Secrets. Press Send test message to verify.")
            } catch (e: GhException) {
                _setup.value = SetupUi(error = e.message)
            } catch (e: Exception) {
                _setup.value = SetupUi(error = "Could not save secrets (${e.javaClass.simpleName})")
            }
        }
    }

    /** Full path test: Android → GitHub API → GitHub Actions → Telegram. */
    fun sendTest() {
        val s = Prefs.now()
        if (!s.ghReady || s.ghStatus != 1 || !s.secretsOk) {
            toast("Connect GitHub and save the Telegram settings first")
            return
        }
        if (_testing.value) return
        viewModelScope.launch {
            _testing.value = true
            val list = withContext(Dispatchers.IO) {
                GitHub.sendTest(Prefs.ghToken(), RepoRef(s.ghOwner, s.ghRepo), s.ghBranch, Payload.test(s)) { p ->
                    _setup.value = SetupUi(busy = true, progress = p)
                }
            }
            val ok = list.isNotEmpty() && list.all { it.ok }
            if (ok) Prefs.setTg(1, "") else Prefs.setTg(2, list.firstOrNull { !it.ok }?.detail ?: "Test failed")
            _setup.value = SetupUi()
            _diagnostics.value = steps("Test", list)
            _testing.value = false
        }
    }

    fun clearSetupMessage() {
        _setup.value = SetupUi()
    }

    fun checkForUpdate() {
        val st = _update.value
        if (st is UpdateState.Checking || st is UpdateState.Downloading) return
        viewModelScope.launch {
            _update.value = UpdateState.Checking
            val c = getApplication<Application>()
            _update.value = try {
                val info = withContext(Dispatchers.IO) { Updater.fetchLatest(c) }
                val current = Updater.currentVersion(c)
                if (Updater.isNewer(info.version, current)) UpdateState.Available(info) else UpdateState.UpToDate(current)
            } catch (e: UpdateException) {
                UpdateState.Error(e.message ?: "Update check failed")
            } catch (e: Exception) {
                UpdateState.Error("Update check failed (${e.javaClass.simpleName})")
            }
        }
    }

    fun downloadAndInstall(info: UpdateInfo) {
        if (_update.value is UpdateState.Downloading) return
        viewModelScope.launch {
            val c = getApplication<Application>()
            _update.value = UpdateState.Downloading(info, 0)
            try {
                val file = withContext(Dispatchers.IO) {
                    val f = Updater.download(c, info) { p -> _update.value = UpdateState.Downloading(info, p) }
                    val note = Updater.verify(c, f, info)
                    _update.value = UpdateState.Ready(info, f, note)
                    f
                }
                if (Updater.install(c, file) == InstallResult.NEEDS_PERMISSION) {
                    toast("Allow installs from Smart Attendance, then press Install")
                }
            } catch (e: UpdateException) {
                _update.value = UpdateState.Error(e.message ?: "Update failed")
            } catch (e: Exception) {
                _update.value = UpdateState.Error("Update failed (${e.javaClass.simpleName})")
            }
        }
    }

    fun installDownloaded(file: java.io.File) {
        val c = getApplication<Application>()
        try {
            if (Updater.install(c, file) == InstallResult.NEEDS_PERMISSION) {
                toast("Allow installs from Smart Attendance, then press Install again")
            }
        } catch (e: Exception) {
            _update.value = UpdateState.Error("Could not open the installer (${e.javaClass.simpleName})")
        }
    }
}
