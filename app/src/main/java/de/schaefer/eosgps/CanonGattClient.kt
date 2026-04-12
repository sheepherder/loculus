package de.schaefer.eosgps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

private const val TAG = "CanonGatt"

object CanonUuids {
    val GPS_SERVICE: UUID = UUID.fromString("00040000-0000-1000-0000-d8492fffa821")
    val GPS_STATUS: UUID = UUID.fromString("00040001-0000-1000-0000-d8492fffa821")
    val GPS_DATA: UUID = UUID.fromString("00040002-0000-1000-0000-d8492fffa821")
    val GPS_NOTIFY: UUID = UUID.fromString("00040003-0000-1000-0000-d8492fffa821")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * GPS state machine mirrored from com.canon.eos.C0298u (the Canon SDK).
 * The camera reports its state via notifications on 00040003 with the first byte
 * of the notification being the state value.
 */
enum class CanonGpsState { UNKNOWN, STATE_1, READY_TO_RECEIVE, STATE_3 }

enum class ConnState {
    IDLE,
    CONNECTING,
    DISCOVERING,
    SUBSCRIBING,
    READY,              // connected, CCCDs enabled; GPS session not started yet
    REQUESTING_GPS,     // 0x02 sent, waiting for [2,...] notification
    GPS_SESSION_ACTIVE, // camera is ready to receive GPS frames
    STOPPING,
    DISCONNECTING,
}

private sealed class GattOp {
    data class Write(
        val ch: BluetoothGattCharacteristic,
        val data: ByteArray,
        val label: String,
        val writeType: Int,
    ) : GattOp()

    data class WriteDesc(
        val desc: BluetoothGattDescriptor,
        val data: ByteArray,
        val label: String,
    ) : GattOp()

    data class Read(val ch: BluetoothGattCharacteristic, val label: String) : GattOp()
}

@SuppressLint("MissingPermission")
class CanonGattClient(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onStateChange: (ConnState) -> Unit,
    private val onGpsState: (CanonGpsState) -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val opQueue = ArrayDeque<GattOp>()
    @Volatile private var opInFlight = false

    @Volatile var state: ConnState = ConnState.IDLE
        private set(value) {
            field = value
            Log.i(TAG, "state -> $value")
            onLog("state -> $value")
            onStateChange(value)
        }

    @Volatile var gpsState: CanonGpsState = CanonGpsState.UNKNOWN
        private set(value) {
            field = value
            Log.i(TAG, "gpsState -> $value")
            onLog("gpsState -> $value")
            onGpsState(value)
        }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    // --- Public API ---

    fun connect(device: BluetoothDevice) {
        if (state != ConnState.IDLE) { log("connect ignored, state=$state"); return }
        log("connect ${device.address}")
        state = ConnState.CONNECTING
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Request GPS data channel from camera.
     *
     * Per Canon SDK: write 0x02 to DATA characteristic. Camera will respond with a
     * notification on NOTIFY (00040003) — when we see first byte == 2, the camera
     * is in READY_TO_RECEIVE and we may start pushing GPS frames.
     */
    fun requestGps() {
        if (state != ConnState.READY) { log("requestGps ignored, state=$state"); return }
        val ch = dataChar ?: return
        state = ConnState.REQUESTING_GPS
        // 0x02 = REQUEST GPS from smartphone
        enqueue(GattOp.Write(ch, byteArrayOf(0x02), "req GPS",
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
    }

    /**
     * Send a GPS fix to the camera. Uses WRITE_TYPE_NO_RESPONSE — Canon does this
     * for the 20-byte GPS frame in C0275o's op-type-3 path, which maps to
     * setWriteType(1) = WRITE_TYPE_NO_RESPONSE.
     */
    fun sendGps(latitude: Double, longitude: Double, altitude: Double, unixSeconds: Long): Boolean {
        if (gpsState != CanonGpsState.READY_TO_RECEIVE) {
            log("sendGps rejected, gpsState=$gpsState")
            return false
        }
        val ch = dataChar ?: return false
        val frame = CanonGpsFrame.encode(latitude, longitude, altitude, unixSeconds)
        enqueue(GattOp.Write(ch, frame, "GPS fix 20B",
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
        return true
    }

    /**
     * Graceful teardown. Always send 0x03 (stop GPS) when the camera is in a
     * ready-to-receive state — otherwise the camera is left expecting more
     * fixes and can end up in a bad state on the next reconnect, leading to a
     * firmware hang ("Busy" lock requiring battery pull).
     */
    fun stopAndDisconnect() {
        val g = gatt
        if (g == null) { state = ConnState.IDLE; return }
        val ch = dataChar
        val needsStop = ch != null && gpsState == CanonGpsState.READY_TO_RECEIVE
        if (needsStop) {
            state = ConnState.STOPPING
            enqueue(GattOp.Write(ch!!, byteArrayOf(0x03), "stop GPS",
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        } else {
            doDisconnect()
        }
    }

    private fun doDisconnect() {
        val g = gatt ?: run { state = ConnState.IDLE; return }
        state = ConnState.DISCONNECTING
        g.disconnect()
    }

    // --- Op queue ---

    private fun enqueue(op: GattOp) {
        synchronized(opQueue) {
            opQueue.add(op)
            if (!opInFlight) pump()
        }
    }

    private fun pump() {
        synchronized(opQueue) {
            if (opInFlight) return
            val g = gatt ?: return
            val op = opQueue.pollFirst() ?: return
            opInFlight = true
            val ok = when (op) {
                is GattOp.Write -> {
                    log("→ write ${op.label} (${op.data.size}B, type=${op.writeType})")
                    val success = writeChar(g, op.ch, op.data, op.writeType)
                    if (op.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE && success) {
                        opInFlight = false
                        pump()
                        return
                    }
                    success
                }
                is GattOp.WriteDesc -> {
                    log("→ write descriptor ${op.label}")
                    writeDesc(g, op.desc, op.data)
                }
                is GattOp.Read -> {
                    log("→ read ${op.label}")
                    g.readCharacteristic(op.ch)
                }
            }
            if (!ok) {
                log("op dispatch returned false, advancing")
                opInFlight = false
                pump()
            }
        }
    }

    private fun opCompleted() {
        synchronized(opQueue) { opInFlight = false }
        pump()
    }

    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray, writeType: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = writeType
            @Suppress("DEPRECATION")
            ch.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private fun writeDesc(g: BluetoothGatt, desc: BluetoothGattDescriptor, data: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(desc, data) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            desc.value = data
            @Suppress("DEPRECATION")
            g.writeDescriptor(desc)
        }
    }

    // --- GATT callback ---

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state = ConnState.DISCOVERING
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(opQueue) { opQueue.clear(); opInFlight = false }
                g.close()
                if (gatt === g) gatt = null
                statusChar = null; dataChar = null; notifyChar = null
                gpsState = CanonGpsState.UNKNOWN
                state = ConnState.IDLE
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) { doDisconnect(); return }
            val svc = g.getService(CanonUuids.GPS_SERVICE)
            if (svc == null) {
                log("GPS service not found")
                g.services.forEach { log("  service ${it.uuid}") }
                doDisconnect(); return
            }
            statusChar = svc.getCharacteristic(CanonUuids.GPS_STATUS)
            dataChar = svc.getCharacteristic(CanonUuids.GPS_DATA)
            notifyChar = svc.getCharacteristic(CanonUuids.GPS_NOTIFY)
            if (dataChar == null || notifyChar == null) {
                log("missing GPS chars"); doDisconnect(); return
            }
            // Canon's init sequence from C0243g: READ both STATUS and NOTIFY to pull
            // initial state, then ENABLE NOTIFY on NOTIFY char (STATUS has no CCCD).
            state = ConnState.SUBSCRIBING
            statusChar?.let { enqueue(GattOp.Read(it, "STATUS")) }
            notifyChar?.let { enqueue(GattOp.Read(it, "NOTIFY")) }
            subscribe(g, notifyChar!!, "NOTIFY")
        }

        private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic, label: String) {
            if (!g.setCharacteristicNotification(ch, true)) {
                log("setCharacteristicNotification($label) returned false"); return
            }
            val desc = ch.getDescriptor(CanonUuids.CCCD)
            if (desc == null) { log("CCCD missing on $label"); return }
            enqueue(GattOp.WriteDesc(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, "CCCD $label"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("onDescriptorWrite ${shortUuid(descriptor.characteristic.uuid)} status=$status")
            opCompleted()
            if (state == ConnState.SUBSCRIBING) {
                synchronized(opQueue) {
                    if (opQueue.isEmpty() && !opInFlight) state = ConnState.READY
                }
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            log("onCharacteristicRead ${shortUuid(ch.uuid)} status=$status val=${value.toHexCompact()}")
            opCompleted()
            // Reading NOTIFY/STATUS gives us the initial state byte; route it through
            // the same handler as a notification.
            if (status == BluetoothGatt.GATT_SUCCESS) handleStateByte(ch, value)
        }

        @Deprecated("legacy pre-33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val v = ch.value ?: ByteArray(0)
            log("onCharacteristicRead ${shortUuid(ch.uuid)} status=$status val=${v.toHexCompact()}")
            opCompleted()
            if (status == BluetoothGatt.GATT_SUCCESS) handleStateByte(ch, v)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            log("onCharacteristicWrite ${shortUuid(ch.uuid)} status=$status")
            opCompleted()
            if (state == ConnState.STOPPING) {
                synchronized(opQueue) {
                    if (opQueue.isEmpty() && !opInFlight) doDisconnect()
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            log("NOTIFY ${shortUuid(ch.uuid)} ${value.toHexCompact()}")
            handleStateByte(ch, value)
        }

        @Deprecated("legacy pre-33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = ch.value ?: return
            log("NOTIFY ${shortUuid(ch.uuid)} ${v.toHexCompact()}")
            handleStateByte(ch, v)
        }

        private fun handleStateByte(ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isEmpty()) return
            val uuid = ch.uuid
            if (uuid == CanonUuids.GPS_NOTIFY) {
                when (value[0].toInt()) {
                    1 -> gpsState = CanonGpsState.STATE_1
                    2 -> {
                        gpsState = CanonGpsState.READY_TO_RECEIVE
                        if (state == ConnState.REQUESTING_GPS) state = ConnState.GPS_SESSION_ACTIVE
                    }
                    3 -> gpsState = CanonGpsState.STATE_3
                    5 -> {
                        val src = if (value.size >= 2) value[1].toInt() else -1
                        log("  camera GPS source = $src (${gpsSourceName(src)})")
                    }
                }
            } else if (uuid == CanonUuids.GPS_STATUS) {
                // STATUS char: byte 0 is a bitfield. Canon checks bit 1 (& 2) for "GPS active".
                val b = value[0].toInt()
                log("  STATUS byte0=0x${"%02x".format(b)} bit1(active)=${(b and 2) != 0}")
                if ((b and 2) == 2) {
                    // Camera is already in GPS-active state. Treat as ready.
                    if (gpsState != CanonGpsState.READY_TO_RECEIVE) {
                        gpsState = CanonGpsState.READY_TO_RECEIVE
                    }
                    if (state == ConnState.REQUESTING_GPS) state = ConnState.GPS_SESSION_ACTIVE
                }
            }
        }
    }

    private fun gpsSourceName(src: Int) = when (src) {
        0 -> "DISABLE"; 1 -> "GPS_RECEIVER"; 2 -> "BUILTIN_GPS"
        3 -> "BUILTIN_OFF"; 4 -> "SMARTPHONE"; else -> "?"
    }

    private fun shortUuid(uuid: UUID): String = uuid.toString().substring(4, 8)
}

private fun ByteArray.toHexCompact(): String = joinToString("") { "%02x".format(it) }
