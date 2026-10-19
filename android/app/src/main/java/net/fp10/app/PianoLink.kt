package net.fp10.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

/**
 * Opens the FP-10 through Android's MIDI service: USB if it is plugged in, otherwise Bluetooth.
 * Bluetooth goes through MidiManager.openBluetoothDevice, Android's own BLE-MIDI driver, which
 * pairs/encrypts the link and receives from the piano (Chrome's Web Bluetooth only got one direction).
 * Callbacks arrive on the main thread, except onMidi, which comes from the MIDI thread.
 */
@SuppressLint("MissingPermission") // MainActivity asks for the Bluetooth permissions before connect().
class PianoLink(private val context: Context, private val events: Events) {
    interface Events {
        fun onMidi(data: ByteArray, offset: Int, count: Int, timestampNs: Long)
        fun onState(connected: Boolean, name: String?, message: String)
    }

    private val midi = context.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var device: MidiDevice? = null
    private var inPort: MidiInputPort? = null  // to the piano
    private var outPort: MidiOutputPort? = null  // from the piano
    private var name: String? = null
    private var busy = false
    private var scanCallback: ScanCallback? = null

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) =
            events.onMidi(msg, offset, count, timestamp)
    }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            // Plugging in the USB cable connects straight away.
            if (device == null && !busy && info.type == MidiDeviceInfo.TYPE_USB && isPiano(info)) open(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == device?.info?.id) {
                close()
                events.onState(false, null, "The piano disconnected.")
            }
        }
    }

    init {
        @Suppress("DEPRECATION")
        midi.registerDeviceCallback(deviceCallback, main)
    }

    val isConnected get() = device != null
    val connectedName get() = name

    fun connect() {
        if (device != null) { events.onState(true, name, "Connected to $name."); return }
        if (busy) return
        val known = pianoDevices().firstOrNull()
        if (known != null) open(known) else connectBluetooth()
    }

    fun send(bytes: ByteArray): Boolean {
        val port = inPort ?: return false
        return try { port.send(bytes, 0, bytes.size); true } catch (e: Exception) { Log.e(TAG, "send", e); false }
    }

    fun close() {
        stopScan()
        try { outPort?.disconnect(receiver) } catch (_: Exception) {}
        listOf(outPort, inPort, device).forEach { try { it?.close() } catch (_: Exception) {} }
        outPort = null; inPort = null; device = null; name = null
    }

    fun release() {
        midi.unregisterDeviceCallback(deviceCallback)
        close()
    }

    // --- Finding the piano ------------------------------------------------------------------
    private fun nameOf(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device"

    private fun looksLikePiano(name: String?) = name != null && PIANO.containsMatchIn(name)

    private fun isPiano(info: MidiDeviceInfo) =
        info.inputPortCount > 0 && info.outputPortCount > 0 && looksLikePiano(nameOf(info))

    /** MIDI devices Android already knows: USB, or Bluetooth opened by another app. */
    private fun pianoDevices(): List<MidiDeviceInfo> =
        midi.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).filter { isPiano(it) }
            .sortedBy { if (it.type == MidiDeviceInfo.TYPE_USB) 0 else 1 }

    private fun open(info: MidiDeviceInfo) {
        busy = true
        events.onState(false, null, "Opening ${nameOf(info)}…")
        midi.openDevice(info, { dev -> attach(dev, nameOf(info)) }, main)
    }

    private fun connectBluetooth() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { events.onState(false, null, "This phone has no Bluetooth."); return }
        if (!adapter.isEnabled) { events.onState(false, null, "Bluetooth is off. Turn it on and tap Connect again."); return }
        val scanner = adapter.bluetoothLeScanner
        val bonded = adapter.bondedDevices.firstOrNull { looksLikePiano(it.name) }
        if (scanner == null) { bonded?.let { openBluetooth(it) } ?: events.onState(false, null, "Bluetooth scan unavailable."); return }

        // Scan first: a bonded piano that is switched off would otherwise hang the open.
        busy = true
        events.onState(false, null, "Looking for the FP-10 over Bluetooth… (turn the piano on)")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val advertisesMidi = result.scanRecord?.serviceUuids?.contains(BLE_MIDI) == true
                if (!advertisesMidi && !looksLikePiano(dev.name ?: result.scanRecord?.deviceName)) return
                stopScan()
                openBluetooth(dev)
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                if (bonded != null) openBluetooth(bonded)
                else { busy = false; events.onState(false, null, "Bluetooth scan failed ($errorCode).") }
            }
        }
        scanCallback = cb
        val filters = listOf(ScanFilter.Builder().setServiceUuid(BLE_MIDI).build())
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        main.postDelayed({
            if (scanCallback !== cb) return@postDelayed
            stopScan()
            if (bonded != null) openBluetooth(bonded)
            else { busy = false; events.onState(false, null, "FP-10 not found. Is it on, and not connected to another app?") }
        }, SCAN_MS)
    }

    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    private fun openBluetooth(dev: BluetoothDevice) {
        busy = true
        val label = dev.name ?: "FP-10"
        events.onState(false, null, "Connecting to $label over Bluetooth…")
        midi.openBluetoothDevice(dev, { d -> attach(d, label) }, main)
    }

    private fun attach(dev: MidiDevice?, label: String) {
        busy = false
        if (dev == null) { events.onState(false, null, "Could not open $label."); return }
        val input = dev.openInputPort(0)
        val output = dev.openOutputPort(0)
        if (input == null || output == null) {
            listOf(input, output, dev).forEach { try { it?.close() } catch (_: Exception) {} }
            events.onState(false, null, "$label is busy. Close other apps using it and try again.")
            return
        }
        output.connect(receiver)
        device = dev; inPort = input; outPort = output; name = label
        val how = if (dev.info.type == MidiDeviceInfo.TYPE_BLUETOOTH) "Bluetooth" else "USB"
        events.onState(true, label, "Connected to $label over $how.")
    }

    companion object {
        private const val TAG = "FP10"
        private const val SCAN_MS = 12_000L
        private val PIANO = Regex("fp-?10|roland|digital piano", RegexOption.IGNORE_CASE)
        private val BLE_MIDI: ParcelUuid = ParcelUuid.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")
    }
}
