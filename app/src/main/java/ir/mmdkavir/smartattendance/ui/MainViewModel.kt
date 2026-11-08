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
import ir.mmdkavir.smartattendance.net.TelegramClient
import ir.mmdkavir.smartattendance.net.TgResult
import ir.mmdkavir.smartattendance.util.Geo
import ir.mmdkavir.smartattendance.work.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GeoStatus { NO_SETUP, PERMISSION, GPS_OFF, TELEGRAM_ERROR, INSIDE, OUTSIDE, ACTIVE }

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

    private var locationJob: Job? = null

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

    fun testTelegram() {
        val s = Prefs.now()
        if (s.workerUrl.isBlank() || s.chatId.isBlank() || !s.hasAppKey) {
            toast("Enter the Worker URL, access key and Chat ID first")
            return
        }
        viewModelScope.launch {
            _testing.value = true
            val result = withContext(Dispatchers.IO) {
                val health = TelegramClient.health(s.workerUrl, Prefs.appKey())
                if (health is TgResult.Fail) {
                    health
                } else {
                    val bot = (health as TgResult.Ok).info
                    val sent = TelegramClient.send(
                        s.workerUrl, Prefs.appKey(), s.chatId,
                        ir.mmdkavir.smartattendance.net.Messages.test(s),
                        "test-" + System.currentTimeMillis()
                    )
                    if (sent is TgResult.Ok) TgResult.Ok(bot) else sent
                }
            }
            when (result) {
                is TgResult.Ok -> {
                    Prefs.setTg(1, "", result.info)
                    toast("Test message delivered")
                }

                is TgResult.Fail -> {
                    Prefs.setTg(2, result.message)
                    toast("Failed: ${result.message}")
                }
            }
            _testing.value = false
        }
    }
}
