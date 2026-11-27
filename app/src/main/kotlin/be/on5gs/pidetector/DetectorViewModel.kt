package be.on5gs.pidetector

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.on5gs.pidetector.audio.AudioEngine
import be.on5gs.pidetector.bt.BluetoothLink
import be.on5gs.pidetector.bt.LinkState
import be.on5gs.pidetector.bt.PairedDevice
import be.on5gs.pidetector.core.BuiltInProfiles
import be.on5gs.pidetector.core.DetectorParams
import be.on5gs.pidetector.core.LearnSession
import be.on5gs.pidetector.core.Message
import be.on5gs.pidetector.core.Profile
import be.on5gs.pidetector.core.Protocol
import be.on5gs.pidetector.core.Reading
import be.on5gs.pidetector.core.SignalProcessor
import be.on5gs.pidetector.core.Simulator
import be.on5gs.pidetector.core.SoundControl
import be.on5gs.pidetector.core.TargetMatch
import be.on5gs.pidetector.core.TargetMatcher
import be.on5gs.pidetector.data.AppPrefs
import be.on5gs.pidetector.data.AppStore
import be.on5gs.pidetector.data.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, SIMULATION }

data class LiveState(
    val reading: Reading? = null,
    val vbat: Float? = null,
    val lowBattery: Boolean = false,
    /** Oude firmware/oud formaat: geen s1/s2-verhouding beschikbaar. */
    val legacy: Boolean = false,
    val history: List<Pair<Float, Float>> = emptyList(),
    val match: TargetMatch? = null,
    /** Hoogste niveau sinds het laatste doel begon (voor de piekmarkering). */
    val peakLevel: Float = 0f,
)

data class DetectorInfo(
    val params: DetectorParams? = null,
    val firmware: String? = null,
    val name: String? = null,
    val info: Map<String, String> = emptyMap(),
)

data class LearnState(val name: String, val samples: Int, val peak: Float)

sealed interface UiEvent {
    data class DetectorError(val reason: String) : UiEvent
    data object Saved : UiEvent
    data object Applied : UiEvent
    data object LearnFailed : UiEvent
    data class Learned(val name: String) : UiEvent
}

class DetectorViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppStore(app)
    val prefs: StateFlow<AppPrefs> = store.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs())

    private val processor = SignalProcessor()
    private val lock = Any()
    private val audio = AudioEngine()
    private val link = BluetoothLink(app, viewModelScope) { line -> onLine(line) }

    private val simRunning = MutableStateFlow(false)
    val conn: StateFlow<ConnState> = combine(link.state, simRunning) { l, sim ->
        when {
            sim -> ConnState.SIMULATION
            l == LinkState.CONNECTED -> ConnState.CONNECTED
            l == LinkState.CONNECTING || l == LinkState.WAITING_RETRY -> ConnState.CONNECTING
            else -> ConnState.DISCONNECTED
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnState.DISCONNECTED)
    val linkError: StateFlow<String?> = link.lastError

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val _detector = MutableStateFlow(DetectorInfo())
    val detector: StateFlow<DetectorInfo> = _detector.asStateFlow()

    private val _learn = MutableStateFlow<LearnState?>(null)
    val learn: StateFlow<LearnState?> = _learn.asStateFlow()
    private var learnSession: LearnSession? = null

    private val _events = MutableStateFlow<UiEvent?>(null)
    val events: StateFlow<UiEvent?> = _events.asStateFlow()
    fun consumeEvent() { _events.value = null }

    private val history = ArrayDeque<Pair<Float, Float>>()
    private var lastLowbatMs = 0L
    private var simJob: Job? = null
    private var matchHold: TargetMatch? = null
    private var matchHoldUntil = 0L
    private var peakLevel = 0f
    private var peakD1 = 0f
    private var balancingSince = -1L

    init {
        viewModelScope.launch {
            prefs.collect { p ->
                synchronized(lock) { processor.settings = p.processor }
                audio.settings = p.audio
            }
        }
        viewModelScope.launch {
            val p = store.load()
            if (p.simulation) startSimulation()
            else p.deviceAddress?.let { if (link.isEnabled) link.connect(it) }
        }
        // geluid uitzetten als er geen data meer binnenkomt
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                val r = _live.value.reading
                val stale = r == null || SystemClock.elapsedRealtime() - r.timeMs > 1500
                if (stale) audio.control = audio.control.copy(active = false, level = 0f, targetPresent = false)
            }
        }
    }

    // ---------------- verbinding ----------------

    val bluetoothAvailable: Boolean get() = link.isAvailable
    val bluetoothEnabled: Boolean get() = link.isEnabled
    fun pairedDevices(): List<PairedDevice> = link.pairedDevices()

    fun connectTo(device: PairedDevice) {
        stopSimulation()
        viewModelScope.launch {
            store.update { it.copy(deviceAddress = device.address, deviceName = device.name, simulation = false) }
        }
        resetSignal()
        link.connect(device.address)
    }

    /** Verbindt met het onthouden apparaat (of stopt de huidige verbinding). */
    fun toggleConnection() {
        when (conn.value) {
            ConnState.CONNECTED, ConnState.CONNECTING -> link.disconnect()
            ConnState.SIMULATION -> setSimulation(false)
            ConnState.DISCONNECTED -> {
                val p = prefs.value
                if (p.simulation) startSimulation()
                else p.deviceAddress?.let { resetSignal(); link.connect(it) }
            }
        }
    }

    fun setSimulation(on: Boolean) {
        viewModelScope.launch { store.update { it.copy(simulation = on) } }
        if (on) { link.disconnect(); startSimulation() } else stopSimulation()
    }

    private fun startSimulation() {
        if (simJob != null) return
        resetSignal()
        simRunning.value = true
        val sim = Simulator((System.currentTimeMillis() % 10000).toInt())
        simJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                handle(sim.sample(SystemClock.elapsedRealtime(), prefs.value.params))
                delay(36)
            }
        }
    }

    private fun stopSimulation() {
        simJob?.cancel(); simJob = null
        simRunning.value = false
    }

    private fun resetSignal() {
        synchronized(lock) {
            processor.clearBalance()
            history.clear()
            balancingSince = -1
        }
        _live.value = LiveState()
    }

    // ---------------- ontvangst ----------------

    private fun onLine(line: String) {
        Protocol.parse(line)?.let { handle(it) }
    }

    private fun handle(m: Message) {
        when (m) {
            is Message.Data -> onData(m.s1, m.s2, m.vbat, legacy = false)
            // oud formaat: enkel de late sample (/10) is beschikbaar
            is Message.Legacy -> onData(m.signal * 10, m.signal * 10, m.volt, legacy = true)
            is Message.Config -> {
                val params = m.toParams()?.sanitized()
                _detector.value = _detector.value.copy(params = params, firmware = m.firmware, name = m.values["name"])
                if (params != null && params != prefs.value.params) {
                    viewModelScope.launch { store.update { it.copy(params = params) } }
                }
            }
            is Message.Info -> _detector.value = _detector.value.copy(info = m.values)
            is Message.Error -> {
                if (m.reason == "lowbat") lastLowbatMs = SystemClock.elapsedRealtime()
                else _events.value = UiEvent.DetectorError(m.reason)
            }
            is Message.Ok -> {}
        }
    }

    private fun onData(s1: Int, s2: Int, vbat: Float, legacy: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val reading: Reading
        val hist: List<Pair<Float, Float>>
        synchronized(lock) {
            if (!processor.isBalanced && !isBalancing()) { processor.startBalance(); balancingSince = now }
            reading = processor.process(s1, s2, now)
            if (reading.balanced) balancingSince = -1
            history.addLast(if (reading.balanced) reading.d1 to reading.d2 else s1.toFloat() to s2.toFloat())
            while (history.size > HISTORY) history.removeFirst()
            hist = history.toList()
        }
        val r = if (legacy) reading.copy(ratio = null) else reading

        // piek bijhouden zolang er een doel is
        if (r.targetPresent) {
            peakLevel = maxOf(peakLevel, r.level); peakD1 = maxOf(peakD1, r.d1)
        } else if (r.level < prefs.value.processor.threshold * 0.5f) {
            peakLevel = 0f; peakD1 = 0f
        }

        // leermodus
        learnSession?.let { s ->
            s.add(r)
            _learn.value = _learn.value?.copy(samples = s.sampleCount, peak = s.peak)
        }

        // best overeenkomend geleerd voorwerp (alleen met dezelfde pw/d1/d2)
        var match: TargetMatch? = null
        if (r.targetPresent && r.ratio != null) {
            val cur = prefs.value.params
            val candidates = prefs.value.learned.filter { t ->
                t.params == null || (t.params!!.pw == cur.pw && t.params!!.d1 == cur.d1 && t.params!!.d2 == cur.d2)
            }
            match = TargetMatcher.best(candidates, r.ratio, peakD1)
            if (match != null) { matchHold = match; matchHoldUntil = now + 1500 }
        }
        if (match == null && now < matchHoldUntil) match = matchHold

        val low = now - lastLowbatMs < 3000
        _live.value = LiveState(r, vbat, low, legacy, hist, match, peakLevel)

        val p = prefs.value
        audio.control = SoundControl(
            active = r.balanced && !low,
            level = r.level,
            ratio = r.ratio,
            targetPresent = r.targetPresent,
            funMode = p.theme == AppTheme.COIN_HUNTERS,
        )
    }

    private fun isBalancing() = balancingSince >= 0

    // ---------------- bediening ----------------

    fun balance() {
        synchronized(lock) { processor.startBalance(); balancingSince = SystemClock.elapsedRealtime() }
    }

    fun setForeground(fg: Boolean) { if (fg) audio.start() else audio.stop() }

    fun updatePrefs(transform: (AppPrefs) -> AppPrefs) {
        viewModelScope.launch { store.update(transform) }
    }

    fun changeSensitivity(delta: Int) = updatePrefs {
        it.copy(processor = it.processor.copy(sensitivity = (it.processor.sensitivity + delta).coerceIn(1, 20)))
    }

    fun toggleMute() = updatePrefs { it.copy(audio = it.audio.copy(muted = !it.audio.muted)) }

    /** Stuurt de parameters naar de detector (SET ...) en onthoudt ze. */
    fun applyParams(p: DetectorParams) {
        val clean = p.sanitized()
        viewModelScope.launch {
            store.update { it.copy(params = clean) }
            if (conn.value == ConnState.CONNECTED) {
                link.sendAll(Protocol.setAll(clean))
                link.send(Protocol.GET)
            }
            _events.value = UiEvent.Applied
            delay(400)
            balance() // ander signaalniveau na een parameterwissel
        }
    }

    fun saveToDetector() {
        if (link.send(Protocol.SAVE)) _events.value = UiEvent.Saved
    }

    fun factoryDefaults() {
        link.send(Protocol.DEFAULTS)
        viewModelScope.launch {
            store.update { it.copy(params = DetectorParams.DEFAULT) }
            delay(400); balance()
        }
    }

    fun refreshConfig() { link.send(Protocol.GET) }

    fun sendRaw(line: String) { link.send(line) }

    // ---------------- profielen ----------------

    fun loadProfile(p: Profile) {
        // avg en vcal blijven zoals ze zijn: die horen bij de detector, niet bij het profiel
        val cur = prefs.value.params
        applyParams(p.params.copy(avg = cur.avg, vcal = cur.vcal))
    }

    fun saveProfile(name: String, params: DetectorParams) = updatePrefs {
        it.copy(profiles = it.profiles + Profile(UUID.randomUUID().toString(), name, params.sanitized()))
    }

    fun updateProfile(p: Profile) = updatePrefs { prefs ->
        prefs.copy(profiles = prefs.profiles.map { if (it.id == p.id) p.copy(params = p.params.sanitized()) else it })
    }

    fun deleteProfile(p: Profile) = updatePrefs { prefs ->
        if (p.builtIn) prefs else prefs.copy(profiles = prefs.profiles.filterNot { it.id == p.id })
    }

    fun restoreBuiltIn(p: Profile) {
        val orig = BuiltInProfiles.ALL.firstOrNull { it.id == p.id } ?: return
        updateProfile(orig)
    }

    // ---------------- leermodus ----------------

    fun startLearn(name: String) {
        learnSession = LearnSession()
        _learn.value = LearnState(name, 0, 0f)
    }

    fun finishLearn() {
        val s = learnSession ?: return
        val name = _learn.value?.name ?: return
        learnSession = null
        _learn.value = null
        val t = s.result(UUID.randomUUID().toString(), name, prefs.value.params)
        if (t == null) { _events.value = UiEvent.LearnFailed; return }
        updatePrefs { it.copy(learned = it.learned + t) }
        _events.value = UiEvent.Learned(name)
    }

    fun cancelLearn() { learnSession = null; _learn.value = null }

    fun deleteLearned(id: String) = updatePrefs { p -> p.copy(learned = p.learned.filterNot { it.id == id }) }

    override fun onCleared() {
        link.disconnect()
        audio.stop()
        super.onCleared()
    }

    companion object {
        /** ~10 s bij ~28 Hz. */
        const val HISTORY = 280
    }
}
