package be.on5gs.pidetector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.on5gs.pidetector.ui.BluetoothScreen
import be.on5gs.pidetector.ui.DetectorSettingsScreen
import be.on5gs.pidetector.ui.MainScreen
import be.on5gs.pidetector.ui.ProfilesScreen
import be.on5gs.pidetector.ui.SoundDisplayScreen
import be.on5gs.pidetector.ui.ThemedBackground
import be.on5gs.pidetector.ui.theme.LocalNeon
import be.on5gs.pidetector.ui.theme.PIDetectorTheme

class MainActivity : ComponentActivity() {
    private val vm: DetectorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { App(vm) }
    }

    override fun onStart() { super.onStart(); vm.setForeground(true) }
    override fun onStop() { vm.setForeground(false); super.onStop() }
}

private enum class Tab { SEARCH, DETECTOR, SOUND, PROFILES, BLUETOOTH }

fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
private fun App(vm: DetectorViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val detector by vm.detector.collectAsStateWithLifecycle()
    val learn by vm.learn.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val linkError by vm.linkError.collectAsStateWithLifecycle()

    val ctx = LocalContext.current
    fun granted() = requiredPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var hasPermission by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = granted()
    }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(requiredPermissions()) }

    var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
    val snackbar = remember { SnackbarHostState() }

    val msgSaved = stringResource(R.string.msg_saved)
    val msgApplied = stringResource(R.string.msg_applied)
    val msgLearnFailed = stringResource(R.string.msg_learn_failed)
    val msgLearned = stringResource(R.string.msg_learned)
    val msgError = stringResource(R.string.msg_detector_error)
    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        vm.consumeEvent()
        snackbar.showSnackbar(
            when (e) {
                UiEvent.Saved -> msgSaved
                UiEvent.Applied -> msgApplied
                UiEvent.LearnFailed -> msgLearnFailed
                is UiEvent.Learned -> msgLearned.format(e.name)
                is UiEvent.DetectorError -> msgError.format(e.reason)
            }
        )
    }

    PIDetectorTheme(prefs.theme) {
        val neon = LocalNeon.current
        Box(Modifier.fillMaxSize()) {
            ThemedBackground(prefs.theme) { live.reading?.level ?: 0f }
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = neon.card) {
                        val items = listOf(
                            Triple(Tab.SEARCH, Icons.Filled.Radar, R.string.tab_search),
                            Triple(Tab.DETECTOR, Icons.Filled.Tune, R.string.tab_detector),
                            Triple(Tab.SOUND, Icons.Filled.MusicNote, R.string.tab_sound),
                            Triple(Tab.PROFILES, Icons.Filled.Bookmarks, R.string.tab_profiles),
                            Triple(Tab.BLUETOOTH, Icons.Filled.Bluetooth, R.string.tab_bluetooth),
                        )
                        for ((t, icon, label) in items) {
                            NavigationBarItem(
                                selected = tab == t, onClick = { tab = t },
                                icon = { Icon(icon, null) },
                                label = { Text(stringResource(label), fontSize = 10.sp, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = neon.text, indicatorColor = neon.accent,
                                    unselectedIconColor = neon.textDim, unselectedTextColor = neon.textDim, selectedTextColor = neon.accent,
                                ),
                            )
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    when (tab) {
                        Tab.SEARCH -> MainScreen(
                            prefs, conn, live,
                            onConnect = {
                                if (prefs.deviceAddress == null && !prefs.simulation && conn == ConnState.DISCONNECTED) tab = Tab.BLUETOOTH
                                else vm.toggleConnection()
                            },
                            onBalance = vm::balance,
                            onSensitivity = vm::changeSensitivity,
                            onToggleMute = vm::toggleMute,
                            onToggleGraph = { vm.updatePrefs { it.copy(showGraph = !it.showGraph) } },
                            onToggleAutoBalance = { v -> vm.updatePrefs { it.copy(processor = it.processor.copy(autoBalance = v)) } },
                        )
                        Tab.DETECTOR -> DetectorSettingsScreen(
                            prefs, conn, detector,
                            onApply = vm::applyParams,
                            onSave = vm::saveToDetector,
                            onDefaults = vm::factoryDefaults,
                            onSaveAsProfile = vm::saveProfile,
                        )
                        Tab.SOUND -> SoundDisplayScreen(prefs, vm::updatePrefs)
                        Tab.PROFILES -> ProfilesScreen(
                            prefs, live, learn,
                            onLoad = vm::loadProfile,
                            onUpdate = vm::updateProfile,
                            onDelete = vm::deleteProfile,
                            onRestore = vm::restoreBuiltIn,
                            onStartLearn = vm::startLearn,
                            onFinishLearn = vm::finishLearn,
                            onCancelLearn = vm::cancelLearn,
                            onDeleteLearned = vm::deleteLearned,
                        )
                        Tab.BLUETOOTH -> BluetoothScreen(
                            prefs, conn, hasPermission,
                            btAvailable = vm.bluetoothAvailable,
                            btEnabled = vm.bluetoothEnabled,
                            linkError = linkError,
                            devices = vm::pairedDevices,
                            onRequestPermission = { launcher.launch(requiredPermissions()) },
                            onSelect = { vm.connectTo(it); tab = Tab.SEARCH },
                            onSimulation = vm::setSimulation,
                        )
                    }
                }
            }
        }
    }
}
