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
import ir.mmdkavir.smartattendance.net.ChatInfo
import ir.mmdkavir.smartattendance.net.CloudflareDeployer
import ir.mmdkavir.smartattendance.net.DeployException
import ir.mmdkavir.smartattendance.net.DiagStep
import ir.mmdkavir.smartattendance.net.TelegramClient
import ir.mmdkavir.smartattendance.net.TgResult
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
    val success: String? = null,
    val chats: List<ChatInfo> = emptyList(),
    val detectNote: String? = null
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

    fun saveTelegram(workerUrl: String, appKey: String?, chatId: String) {
        Prefs.edit {
            putString(Keys.URL, workerUrl.trim().trimEnd('/'))
            putString(Keys.CHAT, chatId.trim())
            if (!appKey.isNullOrBlank()) putString(Keys.APPKEY, appKey.trim())
            putInt(Keys.TG_STATUS, 0)
            putString(Keys.TG_ERR, "")
        }
    }

    fun setNotify(enabled: Boolean) {
        Prefs.edit { putBoolean(Keys.NOTIFY, enabled) }
        if (enabled) SendWorker.enqueue(getApplication())
    }

    fun setMonitoring(enabled: Boolean) {
        Prefs.edit { putBoolean(Keys.MONITOR, enabled) }
        val c = getApplication<Application>()
        if (enabled) Monitor.afterSetup(c) else Monitor.stop(c)
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

    fun testTelegram() {
        val s = Prefs.now()
        if (s.workerUrl.isBlank() || s.chatId.isBlank() || !s.hasAppKey) {
            toast("Enter the Worker URL, access key and Chat ID first")
            return
        }
        if (_testing.value) return
        viewModelScope.launch {
            _testing.value = true
            val key = Prefs.appKey()
            val steps = withContext(Dispatchers.IO) {
                val list = TelegramClient.diagnose(s.workerUrl, key, s.chatId).toMutableList()
                if (list.isNotEmpty() && list.all { it.ok }) {
                    val sent = TelegramClient.send(
                        s.workerUrl, key, s.chatId,
                        ir.mmdkavir.smartattendance.net.Messages.test(s),
                        "test-" + System.currentTimeMillis()
                    )
                    list += if (sent is TgResult.Fail) {
                        DiagStep("Message delivered", false, sent.message)
                    } else {
                        DiagStep("Message delivered", true, "Check your Telegram chat")
                    }
                }
                list
            }
            val ok = steps.isNotEmpty() && steps.all { it.ok }
            if (ok) {
                val bot = Regex("@\\w+").find(steps.firstOrNull { it.title == "Telegram bot" }?.detail.orEmpty())?.value.orEmpty()
                Prefs.setTg(1, "", bot)
                if (Prefs.now().notifyEnabled) SendWorker.enqueue(getApplication())
            } else {
                Prefs.setTg(2, steps.firstOrNull { !it.ok }?.detail ?: "Connection failed")
            }
            _diagnostics.value = (if (ok) "Everything works.\n\n" else "Something is not working.\n\n") +
                steps.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.title + ": " + it.detail }
            _testing.value = false
        }
    }

    fun deployWorker(accountId: String, apiToken: String, botToken: String) {
        if (_setup.value.busy) return
        viewModelScope.launch {
            _setup.value = SetupUi(busy = true, progress = "Deploying your Worker to Cloudflare…")
            val c = getApplication<Application>()
            val key = CloudflareDeployer.generateKey()
            try {
                val (url, steps) = withContext(Dispatchers.IO) {
                    val deployed = CloudflareDeployer.deploy(c, accountId, apiToken, botToken, key)
                    Prefs.edit {
                        putString(Keys.URL, deployed)
                        putString(Keys.APPKEY, key)
                        putInt(Keys.TG_STATUS, 0)
                        putString(Keys.TG_ERR, "")
                    }
                    _setup.value = SetupUi(busy = true, progress = "Worker uploaded. Checking that it answers…")
                    var last: List<DiagStep> = emptyList()
                    for (attempt in 1..6) {
                        last = TelegramClient.diagnose(deployed, key, "")
                        if (last.isNotEmpty() && last.all { it.ok }) break
                        if (attempt < 6) delay(4000)
                    }
                    deployed to last
                }
                val ok = steps.isNotEmpty() && steps.all { it.ok }
                _setup.value = if (ok) {
                    val bot = steps.firstOrNull { it.title == "Telegram bot" }?.detail.orEmpty()
                    SetupUi(success = "Your Worker is ready: $url\n$bot")
                } else {
                    val failed = steps.firstOrNull { !it.ok }
                    SetupUi(
                        success = "Worker deployed at $url",
                        error = (failed?.let { "${it.title}: ${it.detail}" } ?: "The Worker did not answer yet") +
                            ". If this network blocks workers.dev, attach a custom domain to the Worker in Cloudflare and enter it under Worker URL."
                    )
                }
            } catch (e: DeployException) {
                _setup.value = SetupUi(error = e.message ?: "Deployment failed")
            } catch (e: Exception) {
                _setup.value = SetupUi(error = "Deployment failed: ${e.javaClass.simpleName}")
            }
        }
    }

    fun detectChats() {
        val s = Prefs.now()
        if (s.workerUrl.isBlank() || !s.hasAppKey) {
            toast("Deploy or enter your Worker first")
            return
        }
        if (_setup.value.busy) return
        viewModelScope.launch {
            _setup.value = SetupUi(busy = true, progress = "Looking for your chat…")
            val (chats, error) = withContext(Dispatchers.IO) { TelegramClient.detectChats(s.workerUrl, Prefs.appKey()) }
            if (chats.size == 1 && error == null) {
                Prefs.edit {
                    putString(Keys.CHAT, chats[0].id)
                    putInt(Keys.TG_STATUS, 0)
                }
            }
            _setup.value = SetupUi(
                chats = chats,
                detectNote = error ?: if (chats.isEmpty()) {
                    "No messages found yet. Open your bot in Telegram, press Start (or send any message), then try again."
                } else if (chats.size == 1) {
                    "Chat ID filled in automatically."
                } else {
                    "Choose your chat."
                }
            )
        }
    }

    fun chooseChat(id: String) {
        Prefs.edit {
            putString(Keys.CHAT, id)
            putInt(Keys.TG_STATUS, 0)
            putString(Keys.TG_ERR, "")
        }
        _setup.value = _setup.value.copy(detectNote = "Chat ID saved.")
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
