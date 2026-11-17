package io.github.jqssun.airplay.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import io.github.jqssun.airplay.AllowedDevice
import io.github.jqssun.airplay.AllowedDevices
import io.github.jqssun.airplay.Pref
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.get
import io.github.jqssun.airplay.observe
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class LocalNetworkAccess { GRANTED, ASK, SETTINGS }

data class AudioDebug(
    val backlogMs: Int,
    val tunedCushionMs: Int,
    val trims: Int,
    val drops: Int,
    val silences: Int,
    val underruns: Int,
    val xrun: Int,
    val decodeMeanUs: Int,
    val decodeMaxUs: Int,
    val decodeHeld: Int,
    val decodeErrors: Int,
)

data class DebugInfo(
    val videoCodec: String = "",
    val videoRes: String = "",
    val videoFps: Int = 0,
    val videoBitrate: Long = 0,
    val videoFrames: Long = 0,
    val droppedFrames: Long = 0,
    val framePacingJitterUs: Long = 0,
    val audioCodec: String = "",
    val serverVolume: Int = 100,
    val clientVolume: Int = 100,
    val audio: AudioDebug? = null,
    val connections: Int = 0,
) {
    val bitrateStr: String get() {
        val kbps = videoBitrate / 1000
        return if (kbps >= 1000) "${"%.1f".format(kbps / 1000.0)} Mbps" else "$kbps Kbps"
    }
    val jitterStr: String get() {
        return if (framePacingJitterUs >= 1000) {
            "${"%.1f".format(framePacingJitterUs / 1000.0)} ms"
        } else {
            "$framePacingJitterUs us"
        }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private val logFile = File(app.filesDir, "airplay_logs.txt")
    private var service: AirPlayService? = null
    val dacpPlayer: Player? get() = service?.dacpPlayer

    private val _clientVolume = MutableStateFlow(1f)
    val clientVolume: StateFlow<Float> = _clientVolume.asStateFlow()
    private val _clientVolumeAdjustable = MutableStateFlow(false)
    val clientVolumeAdjustable: StateFlow<Boolean> = _clientVolumeAdjustable.asStateFlow()
    fun setClientVolume(frac: Float) { service?.setClientVolume(frac) }

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    private val _pinCode = MutableStateFlow<String?>(null)
    val pinCode: StateFlow<String?> = _pinCode.asStateFlow()

    private val _clientRequest = MutableStateFlow<AirPlayService.ClientRequest?>(null)
    val clientRequest: StateFlow<AirPlayService.ClientRequest?> = _clientRequest.asStateFlow()

    private val _clientName = MutableStateFlow("")
    val clientName: StateFlow<String> = _clientName.asStateFlow()

    private val _connectionLost = MutableStateFlow<String?>(null)
    val connectionLost: StateFlow<String?> = _connectionLost.asStateFlow()

    val allowedDevices: StateFlow<List<AllowedDevice>> = prefs.observe { AllowedDevices.read(this) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AllowedDevices.read(prefs))

    fun answerClientRequest(allow: Boolean) {
        service?.answerClientRequest(allow)
        _clientRequest.value = null
    }

    fun disconnectSenders() { service?.disconnectSenders() }

    fun dismissConnectionLost() {
        service?.dismissConnectionLost()
        _connectionLost.value = null
    }

    fun removeAllowedDevice(id: String) = AllowedDevices.remove(prefs, id)

    fun clearAllowedDevices() = AllowedDevices.clear(prefs)

    private val _prefStates = mutableMapOf<Pref<*>, StateFlow<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> pref(p: Pref<T>): StateFlow<T> = _prefStates.getOrPut(p) {
        prefs.observe { this[p] }.stateIn(viewModelScope, SharingStarted.Eagerly, prefs[p])
    } as StateFlow<T>

    fun <T : Any> set(p: Pref<T>, v: T) { p.write(prefs, v) }

    private val _debugInfo = MutableStateFlow(DebugInfo())
    val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

    private val _localNetwork = MutableStateFlow(LocalNetworkAccess.GRANTED)
    val localNetwork: StateFlow<LocalNetworkAccess> = _localNetwork.asStateFlow()
    fun setLocalNetwork(v: LocalNetworkAccess) { _localNetwork.value = v }

    // audio mode
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive: StateFlow<Boolean> = _mirroringActive.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo: StateFlow<TrackInfo> = _trackInfo.asStateFlow()

    private val _audioStreaming = MutableStateFlow(false)
    val audioStreaming: StateFlow<Boolean> = _audioStreaming.asStateFlow()


    // logs: callers include the video decode thread (benchmark line, under the renderer lock)
    // and native audio threads, so addLog only queues; the UI list and airplay_logs.txt are
    // updated off-thread in coalesced batches, appending to the file instead of rewriting it
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _logLock = Any()
    private val _logList = ArrayDeque<String>()
    private val _pendingLines = ArrayList<String>()
    private var _flushScheduled = false
    private var _clearFile = false
    // serializes file IO; never taken by addLog
    private val _fileLock = Any()
    private var _fileLoaded = false
    private var _fileLines = 0
    // not viewModelScope: the last batch still lands after the activity finishes
    private val _logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    init {
        _scheduleLogFlush(0)
    }

    fun addLog(msg: String) {
        val line = "${_dateFmt.get()!!.format(Date())} $msg"
        val schedule: Boolean
        synchronized(_logLock) {
            _logList.addLast(line)
            while (_logList.size > MAX_LOG_LINES) _logList.removeFirst()
            _pendingLines.add(line)
            schedule = !_flushScheduled
            _flushScheduled = true
        }
        if (schedule) _scheduleLogFlush(LOG_FLUSH_DELAY_MS)
    }

    fun clearLogs() {
        synchronized(_logLock) {
            _logList.clear()
            _pendingLines.clear()
            _clearFile = true
        }
        _logs.value = emptyList()
        _scheduleLogFlush(0)
    }

    fun exportLogs() {
        val ctx = getApplication<Application>()
        val file = File(ctx.cacheDir, "airplay_logs.txt")
        file.writeText(synchronized(_logLock) { _logList.joinToString("\n") })
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserTitle = ctx.getString(io.github.jqssun.airplay.R.string.export_logs_chooser_title)
        ctx.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun _scheduleLogFlush(delayMs: Long) {
        _logScope.launch {
            if (delayMs > 0) delay(delayMs)
            _flushLogs()
        }
    }

    // IO thread; _fileLock keeps batches in order across overlapping flushes
    private fun _flushLogs(): Unit = synchronized(_fileLock) {
        if (!_fileLoaded) _loadPersistedLogs()
        val batch: List<String>
        val clear: Boolean
        val snapshot: List<String>
        synchronized(_logLock) {
            _flushScheduled = false
            batch = _pendingLines.toList()
            _pendingLines.clear()
            clear = _clearFile
            _clearFile = false
            snapshot = _logList.toList()
        }
        _logs.value = snapshot
        try {
            when {
                clear -> {
                    logFile.writeText(batch.joinToString("") { it + "\n" })
                    _fileLines = batch.size
                }
                // compact once the appended file holds twice what the UI keeps
                _fileLines + batch.size > 2 * MAX_LOG_LINES -> {
                    logFile.writeText(snapshot.joinToString("") { it + "\n" })
                    _fileLines = snapshot.size
                }
                batch.isNotEmpty() -> {
                    logFile.appendText(batch.joinToString("") { it + "\n" })
                    _fileLines += batch.size
                }
                else -> {}
            }
        } catch (_: java.io.IOException) {
            // best effort: the in-memory list still has every line
        }
    }

    // holds _fileLock; restored lines go before anything logged since start
    private fun _loadPersistedLogs() {
        _fileLoaded = true
        val restored = runCatching {
            if (!logFile.exists()) return@runCatching emptyList()
            val text = logFile.readText()
            if (text.isEmpty()) return@runCatching emptyList()
            // older builds wrote no trailing newline; terminate the last line before appending
            if (!text.endsWith("\n")) logFile.appendText("\n")
            text.lines().let { if (text.endsWith("\n")) it.dropLast(1) else it }
        }.getOrDefault(emptyList())
        _fileLines = restored.size
        synchronized(_logLock) {
            _logList.addAll(0, restored.takeLast(MAX_LOG_LINES))
            while (_logList.size > MAX_LOG_LINES) _logList.removeFirst()
        }
    }

    override fun onCleared() {
        _scheduleLogFlush(0)
    }

    // service binding
    fun bindService(svc: AirPlayService) {
        service = svc
        updateFromService()
    }

    fun unbindService() {
        service = null
    }

    fun startServer() {
        service?.startServer()
    }

    fun stopServer() {
        service?.stopServer()
    }

    fun onSurfaceAvailable(surface: Surface) {
        service?.setVideoSurface(surface)
    }

    fun onSurfaceDestroyed(surface: Surface) {
        service?.clearVideoSurface(surface)
    }

    fun dismissPin() {
        _pinCode.value = null
    }

    fun showPin(pin: String?) {
        _pinCode.value = pin
    }

    fun updateFromService() {
        service?.let {
            _serverState.value = it.serverState.value
            _clientRequest.value = it.clientRequest.value
            _clientName.value = it.clientName.value
            _connectionLost.value = it.connectionLost.value
            _connectionCount.value = it.connectionCount.value
            _audioOnly.value = it.audioOnly.value
            _mirroringActive.value = it.mirroringActive.value
            _trackInfo.value = it.trackInfo.value
            _audioStreaming.value = it.audioStreaming.value
            _clientVolume.value = it.clientVolume()
            _clientVolumeAdjustable.value = it.dacpController?.ready == true
            if (pref(Prefs.DEBUG_ENABLED).value) {
                _debugInfo.value = it.collectDebugInfo()
            }
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 9999
        const val LOG_FLUSH_DELAY_MS = 250L
    }
}
