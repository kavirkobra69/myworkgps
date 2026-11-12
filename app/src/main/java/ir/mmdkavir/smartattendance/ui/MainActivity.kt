package ir.mmdkavir.smartattendance.ui

import ir.mmdkavir.smartattendance.i18n.t
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.mmdkavir.smartattendance.geo.LocationHelper

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bgRequest.value = intent.getBooleanExtra(ir.mmdkavir.smartattendance.bg.BgWidget.EXTRA_ENABLE_BG, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bgRequest.value = intent?.getBooleanExtra(ir.mmdkavir.smartattendance.bg.BgWidget.EXTRA_ENABLE_BG, false) == true
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppRoot()
            }
        }
    }
}

/** Set when the widget sends the user here to grant permissions and turn background mode on. */
val bgRequest = kotlinx.coroutines.flow.MutableStateFlow(false)

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settings by vm.settings.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    DiagnosticsDialog(vm)
    var tab by rememberSaveable { mutableIntStateOf(0) }

    fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    var askLocation: () -> Unit = {}

    fun refresh() {
        vm.refreshDevice(notificationsGranted())
        vm.startLocationUpdates()
        if (vm.pendingBg && ir.mmdkavir.smartattendance.bg.BackgroundMode.permissionsOk(context) &&
            !LocationHelper.isLocationEnabled(context)
        ) askLocation()
    }

    fun launchSettings(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            vm.toast(t("Could not open settings on this device"))
        }
    }

    fun appDetails(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        if (vm.pendingBg && ir.mmdkavir.smartattendance.bg.BackgroundMode.permissionsOk(context)) vm.setBackgroundMode(true)
        refresh()
    }

    val actions = remember {
        val enableLocation = {
            if (LocationHelper.trySilentEnable(context)) {
                if (vm.pendingBg) vm.setBackgroundMode(true)
                refresh()
            } else {
                var handled = false
                val openSettings = {
                    if (!handled) {
                        handled = true
                        launchSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                }
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build()
                val settings = LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true).build()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ openSettings() }, 3_000L)
                try {
                    LocationServices.getSettingsClient(context).checkLocationSettings(settings)
                        .addOnSuccessListener { handled = true; refresh() }
                        .addOnFailureListener { e ->
                            if (handled) return@addOnFailureListener
                            try {
                                if (e is ResolvableApiException) {
                                    handled = true
                                    locationLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                                } else openSettings()
                            } catch (x: Exception) {
                                openSettings()
                            }
                        }
                } catch (e: Exception) {
                    openSettings()
                }
            }
            Unit
        }
        askLocation = enableLocation
        PermissionActions(
            requestBasic = {
                val wanted = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
                permissionLauncher.launch(wanted.toTypedArray())
            },
            requestBackground = {
                when {
                    Build.VERSION.SDK_INT < 29 -> refresh()
                    !LocationHelper.hasFine(context) -> permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                    Build.VERSION.SDK_INT == 29 ->
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    else -> {
                        vm.toast(t("Open Permissions → Location and choose \"Allow all the time\""))
                        launchSettings(appDetails())
                    }
                }
            },
            enableLocation = enableLocation,
            openLocationSettings = { launchSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            openAppSettings = { launchSettings(appDetails()) },
            openBatterySettings = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + context.packageName))
                    )
                } catch (e: Exception) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        launchSettings(appDetails())
                    }
                }
            },
            openNotificationSettings = {
                launchSettings(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        )
    }

    val wantBg by bgRequest.collectAsState()
    LaunchedEffect(wantBg) {
        if (wantBg) {
            bgRequest.value = false
            startBackgroundFlow(context, vm, actions)
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> refresh()
                Lifecycle.Event.ON_STOP -> vm.stopLocationUpdates()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(message) {
        val m = message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (settings.setupDone) {
                val items = listOf(
                    t("Home") to Icons.Default.Home,
                    t("Map") to Icons.Default.Map,
                    t("History") to Icons.Default.History,
                    t("Settings") to Icons.Default.Settings
                )
                NavigationBar {
                    items.forEachIndexed { i, item ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(item.second, contentDescription = item.first) },
                            label = { Text(item.first) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (!settings.setupDone) {
                SetupWizard(vm, actions)
            } else {
                Crossfade(targetState = tab, label = "tabs") { current ->
                    when (current) {
                        0 -> HomeScreen(vm, actions, onOpenMap = { tab = 1 })
                        1 -> MapScreen(vm)
                        2 -> HistoryScreen(vm)
                        else -> SettingsScreen(vm, actions)
                    }
                }
            }
        }
    }
}
