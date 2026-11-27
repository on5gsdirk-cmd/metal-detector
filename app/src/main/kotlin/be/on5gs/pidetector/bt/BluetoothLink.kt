package be.on5gs.pidetector.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import be.on5gs.pidetector.core.LineSplitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

enum class LinkState { IDLE, CONNECTING, CONNECTED, WAITING_RETRY }

data class PairedDevice(val name: String, val address: String)

/**
 * Bluetooth Classic SPP-verbinding met automatisch opnieuw verbinden.
 * Ontvangen regels gaan naar [onLine] (op een IO-thread).
 */
@SuppressLint("MissingPermission") // permissies worden in MainActivity gecontroleerd
class BluetoothLink(context: Context, private val scope: CoroutineScope, private val onLine: (String) -> Unit) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(LinkState.IDLE)
    val state: StateFlow<LinkState> = _state
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private var job: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun pairedDevices(): List<PairedDevice> = try {
        adapter?.bondedDevices.orEmpty()
            .map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedWith(compareByDescending<PairedDevice> { it.name.contains("Spirit", true) || it.name.contains("ESP32", true) }.thenBy { it.name })
    } catch (e: SecurityException) { emptyList() }

    /** Start (of herstart) de verbindingslus naar [address]. Blijft opnieuw proberen tot [disconnect]. */
    fun connect(address: String) {
        disconnect()
        job = scope.launch(Dispatchers.IO) {
            var backoff = 1500L
            while (isActive) {
                _state.value = LinkState.CONNECTING
                try {
                    val dev = adapter?.getRemoteDevice(address) ?: throw IOException("Geen Bluetooth-adapter")
                    val s = openSocket(dev)
                    socket = s
                    out = s.outputStream
                    _state.value = LinkState.CONNECTED
                    _lastError.value = null
                    backoff = 1500L
                    send("GET")
                    readLoop(s)
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "verbinding mislukt/verbroken: ${e.message}")
                    _lastError.value = e.message
                } finally {
                    closeSocket()
                }
                if (!isActive) break
                _state.value = LinkState.WAITING_RETRY
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(10_000L)
            }
            _state.value = LinkState.IDLE
        }
    }

    private fun openSocket(dev: BluetoothDevice): BluetoothSocket {
        try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}
        val first = dev.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            first.connect()
            return first
        } catch (e: IOException) {
            runCatching { first.close() }
            // Bekende work-around voor ESP32/oudere stacks: rechtstreeks RFCOMM-kanaal 1.
            val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val fallback = m.invoke(dev, 1) as BluetoothSocket
            fallback.connect()
            return fallback
        }
    }

    private fun readLoop(s: BluetoothSocket) {
        val input = s.inputStream
        val buf = ByteArray(512)
        val splitter = LineSplitter()
        while (true) {
            val n = input.read(buf)
            if (n < 0) throw IOException("stream gesloten")
            for (line in splitter.feed(String(buf, 0, n, Charsets.US_ASCII))) onLine(line)
        }
    }

    /** Stuurt één commando (er wordt '\n' toegevoegd). Veilig vanaf elke thread. */
    fun send(line: String): Boolean {
        val o = out ?: return false
        return try {
            synchronized(this) { o.write((line + "\n").toByteArray(Charsets.US_ASCII)); o.flush() }
            true
        } catch (e: IOException) { false }
    }

    suspend fun sendAll(lines: List<String>, gapMs: Long = 40) = withContext(Dispatchers.IO) {
        for (l in lines) { send(l); delay(gapMs) }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        closeSocket()
        _state.value = LinkState.IDLE
    }

    private fun closeSocket() {
        out = null
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    companion object {
        private const val TAG = "BluetoothLink"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
