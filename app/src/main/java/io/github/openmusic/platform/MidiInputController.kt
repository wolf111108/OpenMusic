package io.github.openmusic.platform

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.Locale

data class MidiDeviceEntry(val id: Int, val name: String, val outputPortNumbers: List<Int>)

data class MidiState(
    val supported: Boolean,
    val devices: List<MidiDeviceEntry> = emptyList(),
    val connectedDeviceId: Int? = null,
    val connectedName: String? = null,
    val error: String? = null,
    val log: List<String> = emptyList(),
)

/**
 * Android's MIDI service owns USB discovery and access. No RECORD_AUDIO permission or USB
 * audio endpoint is involved: an output port is the keyboard's output, and our input.
 */
class MidiInputController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(
        MidiState(manager != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)),
    )
    val state: StateFlow<MidiState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<MidiInputEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<MidiInputEvent> = mutableEvents.asSharedFlow()

    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private var device: MidiDevice? = null
    private var output: MidiOutputPort? = null
    private var receiver: MidiReceiver? = null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = refresh()
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (mutableState.value.connectedDeviceId == device.id) {
                disconnectOnMain()
                record("设备已拔出：${deviceName(device)}")
            }
            refresh()
        }
        override fun onDeviceStatusChanged(status: MidiDeviceStatus) = refresh()
    }

    init {
        if (mutableState.value.supported) {
            try {
                manager?.registerDeviceCallback(callback, handler)
                refresh()
            } catch (error: Exception) {
                fail("无法初始化 Android MIDI：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    @Suppress("DEPRECATION") // MIDI 1.0 enumeration remains available on every supported API level.
    fun refresh() {
        if (closed) return
        try {
            val devices = manager?.devices.orEmpty()
                .filter { it.outputPortCount > 0 }
                .map { info ->
                    MidiDeviceEntry(info.id, deviceName(info), info.ports
                        .filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
                        .map { it.portNumber })
                }
            mutableState.update { it.copy(devices = devices) }
        } catch (error: Exception) {
            fail("无法读取 MIDI 设备：${error.message ?: error.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    fun connect(deviceId: Int, portNumber: Int? = null) = onMain {
        if (closed) return@onMain
        disconnectOnMain()
        val token = generation
        try {
            val info = manager?.devices?.firstOrNull { it.id == deviceId }
            if (info == null) {
                fail("设备已离线，请重新扫描后连接。")
                refresh()
                return@onMain
            }
            val ports = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
            val selectedPort = if (portNumber == null) ports.firstOrNull()
                else ports.firstOrNull { it.portNumber == portNumber }
            if (selectedPort == null) {
                fail("设备没有可用的 MIDI 输出端口。")
                return@onMain
            }
            mutableState.update { it.copy(error = null) }
            record("正在连接：${deviceName(info)}，端口 ${selectedPort.portNumber}")
            manager?.openDevice(info, { opened ->
                if (closed || token != generation) {
                    runCatching { opened?.close() }
                } else if (opened == null) {
                    fail("MIDI 设备打开失败，请检查数据线、OTG 和设备占用情况。")
                } else {
                    try {
                        val port = opened.openOutputPort(selectedPort.portNumber)
                            ?: error("端口已被占用或无法打开")
                        val parser = MidiByteStreamDecoder { event ->
                            if (!closed && token == generation && !mutableEvents.tryEmit(event)) {
                                fail("MIDI 事件处理队列已满；本次结果需要重新练习确认。")
                            }
                        }
                        val newReceiver = object : MidiReceiver() {
                            override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                                if (closed || token != generation) return
                                val time = if (timestamp > 0L) timestamp else System.nanoTime()
                                // Bounded raw logs are useful when diagnosing a real keyboard connection.
                                val hex = (offset until offset + minOf(count, 48)).joinToString(" ") {
                                    String.format(Locale.ROOT, "%02X", data[it].toInt() and 0xff)
                                }
                                record("$time ns · $hex${if (count > 48) " … ($count bytes)" else ""}")
                                parser.feed(data, offset, count, time)
                            }
                        }
                        device = opened
                        output = port
                        receiver = newReceiver
                        port.connect(newReceiver)
                        mutableState.update { it.copy(
                            connectedDeviceId = info.id,
                            connectedName = deviceName(info),
                            error = null,
                        ) }
                        record("已连接：${deviceName(info)}；等待 Note On / Note Off / CC64。")
                    } catch (error: Exception) {
                        runCatching { opened.close() }
                        disconnectOnMain()
                        fail("MIDI 连接失败：${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }, handler)
        } catch (error: Exception) {
            fail("MIDI 连接失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun disconnect() = onMain { disconnectOnMain() }

    fun clearLog() = mutableState.update { it.copy(log = emptyList()) }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        onMain {
            runCatching { manager?.unregisterDeviceCallback(callback) }
            disconnectOnMain()
        }
    }

    private fun disconnectOnMain() {
        generation++
        receiver?.let { input -> runCatching { output?.disconnect(input) } }
        runCatching { output?.close() }
        runCatching { device?.close() }
        receiver = null
        output = null
        device = null
        mutableState.update { it.copy(connectedDeviceId = null, connectedName = null) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post(action)
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(error = message) }
        record(message)
    }

    private fun record(message: String) {
        mutableState.update { it.copy(log = (it.log + message).takeLast(160)) }
    }

    private fun deviceName(info: MidiDeviceInfo): String {
        val properties = info.properties
        return properties.getString(MidiDeviceInfo.PROPERTY_NAME)?.takeIf(String::isNotBlank)
            ?: listOfNotNull(
                properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
                properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            ).filter(String::isNotBlank).joinToString(" ").ifBlank { "MIDI ${info.id}" }
    }
}
