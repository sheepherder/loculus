package de.schaefer.eosgps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

private const val TAG = "CanonGatt"
private const val STOP_WRITE_DRAIN_MS = 200L

object CanonUuids {
    // GPS service (0x00040000). All GPS-related reads, writes and indications.
    val GPS_SERVICE: UUID = UUID.fromString("00040000-0000-1000-0000-d8492fffa821")
    val GPS_DATA: UUID = UUID.fromString("00040002-0000-1000-0000-d8492fffa821")
    val GPS_NOTIFY: UUID = UUID.fromString("00040003-0000-1000-0000-d8492fffa821")

    // Pairing service (0x00010000). Writing 0x01 to PAIRING_DATA (0001000a) is
    // what makes the camera commit to the GPS session — it responds with an
    // indication [02, ...] on GPS_NOTIFY ("ready to receive"). Without this
    // write Canon's firmware never sends the ready signal and any GPS frames
    // we push end up waking the camera's GPS subsystem in an ill-defined state.
    val PAIRING_SERVICE: UUID = UUID.fromString("00010000-0000-1000-0000-d8492fffa821")
    val PAIRING_DATA: UUID = UUID.fromString("0001000a-0000-1000-0000-d8492fffa821")

    // BLE→WiFi handover service (0x00020000). Writing 0x0a to HANDOVER_DATA is
    // part of Canon's kickoff sequence before the pairing-0x01 write.
    val HANDOVER_SERVICE: UUID = UUID.fromString("00020000-0000-1000-0000-d8492fffa821")
    val HANDOVER_DATA: UUID = UUID.fromString("00020002-0000-1000-0000-d8492fffa821")

    // Remote-control service (0x00030000). Shutter writes go to REMOTE_SHUTTER.
    val REMOTE_SERVICE: UUID = UUID.fromString("00030000-0000-1000-0000-d8492fffa821")

    // Remote-control write char. Canon-App-Dekompilat (`C0500A.E()` +
    // `N.java`) listet diese 2-Byte-Werte mit Log-String-Labels — die
    // Byte→Label-Zuordnung ist belegt, ob das Label tatsächlich dem
    // Kamera-Verhalten entspricht ist aber je Code ungeprüft.
    //
    //   0x0001 / 0x0002 AF_REL_ON / AF_REL_OFF   ← empirisch: Foto bei AF-OK
    //   0x0003 / 0x0004 AF_ON / AF_OFF           (ungetestet)
    //   0x0005 / 0x0006 REL_ON / REL_OFF         (ungetestet)
    //   0x0010 / 0x0011 MOVIE_START / MOVIE_STOP (ungetestet)
    //   0x0020..0x0023  ZOOM_TELE/WIDE + _STOP   (ungetestet)
    val REMOTE_SHUTTER: UUID = UUID.fromString("00030030-0000-1000-0000-d8492fffa821")

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
    REQUESTING_GPS,     // Canon kickoff sent, waiting for [2,…] ready indication
    GPS_SESSION_ACTIVE, // camera is ready to receive GPS frames
    DISCONNECTING,
}

internal sealed class GattOp {
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
}

@SuppressLint("MissingPermission")
class CanonGattClient(
    private val context: Context,
    private val onStateChange: (ConnState) -> Unit,
    private val onGpsState: (CanonGpsState) -> Unit,
    private val onRssi: (Int) -> Unit = {},
    private val onWriteResult: (status: Int) -> Unit = {},
) {
    private var gatt: BluetoothGatt? = null
    var lastDisconnectStatus: Int = 0
        private set

    private var dataChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var pairingDataChar: BluetoothGattCharacteristic? = null
    private var handoverDataChar: BluetoothGattCharacteristic? = null
    private var shutterChar: BluetoothGattCharacteristic? = null

    private val opQueue = ArrayDeque<GattOp>()
    @Volatile private var opInFlight = false
    @Volatile private var disposed = false

    private val handler = Handler(Looper.getMainLooper())
    private val stateTimeout = Runnable {
        when (state) {
            ConnState.CONNECTING, ConnState.DISCOVERING, ConnState.SUBSCRIBING -> {
                Log.w(TAG, "$state timeout (15s) — disconnecting")
                doDisconnect()
            }
            ConnState.REQUESTING_GPS -> {
                Log.w(TAG, "REQUESTING_GPS timeout (10s) — disconnecting")
                doDisconnect()
            }
            else -> {}
        }
    }

    @Volatile var state: ConnState = ConnState.IDLE
        internal set(value) {
            field = value
            Log.i(TAG, "state -> $value")
            if (!disposed) onStateChange(value)
        }

    @Volatile var gpsState: CanonGpsState = CanonGpsState.UNKNOWN
        internal set(value) {
            field = value
            Log.i(TAG, "gpsState -> $value")
            if (!disposed) onGpsState(value)
        }

    // --- Public API ---

    fun connect(device: BluetoothDevice) {
        if (state != ConnState.IDLE) { Log.i(TAG,"connect ignored, state=$state"); return }
        Log.i(TAG,"connect ${device.address}")
        state = ConnState.CONNECTING
        @Suppress("DEPRECATION") // replacement BluetoothGattConnectionSettings is hidden on API 36
        val g = device.connectGatt(
            context, false, callback,
            BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M, handler,
        )
        if (g == null) {
            Log.w(TAG, "connectGatt returned null")
            state = ConnState.IDLE
            return
        }
        gatt = g
        handler.removeCallbacks(stateTimeout)
        handler.postDelayed(stateTimeout, 15_000L)
    }

    /** Request a fresh RSSI reading from the connected GATT link. Result arrives via onRssi. */
    fun readRssi(): Boolean = gatt?.readRemoteRssi() ?: false

    /**
     * Löst ein Foto aus, wenn der Autofokus scharfstellen konnte. Schickt
     * `00 01` gefolgt von `00 02` auf REMOTE_SHUTTER. Bei fehlgeschlagenem
     * Autofokus passiert nichts.
     */
    fun triggerShutter(): Boolean {
        val ch = shutterChar ?: return false
        if (gpsState != CanonGpsState.READY_TO_RECEIVE) {
            Log.i(TAG,"triggerShutter rejected, gpsState=$gpsState"); return false
        }
        enqueue(GattOp.Write(ch, byteArrayOf(0x00, 0x01), "AF_REL_ON",
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        enqueue(GattOp.Write(ch, byteArrayOf(0x00, 0x02), "AF_REL_OFF",
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        return true
    }

    /**
     * Send a GPS fix to the camera. Uses WRITE_TYPE_NO_RESPONSE — Canon does this
     * for the 20-byte GPS frame in C0275o's op-type-3 path, which maps to
     * setWriteType(1) = WRITE_TYPE_NO_RESPONSE.
     */
    fun sendGps(latitude: Double, longitude: Double, altitude: Double, unixSeconds: Long): Boolean {
        if (gpsState != CanonGpsState.READY_TO_RECEIVE) {
            Log.i(TAG,"sendGps rejected, gpsState=$gpsState")
            return false
        }
        val ch = dataChar ?: return false
        val frame = CanonGpsFrame.encode(latitude, longitude, altitude, unixSeconds)
        enqueue(GattOp.Write(ch, frame, "GPS fix 20B",
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
        return true
    }

    /**
     * Send 0x03 fire-and-forget, then disconnect and silence all further
     * callbacks so no delayed work can outlive the owning service and
     * overwrite [TrackingState].
     */
    fun sendStopAndClose() {
        disposed = true
        handler.removeCallbacks(stateTimeout)
        val g = gatt ?: return
        val ch = dataChar
        if (ch != null && gpsState == CanonGpsState.READY_TO_RECEIVE) {
            writeChar(g, ch, byteArrayOf(0x03), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            handler.postDelayed({ closeGatt(g) }, STOP_WRITE_DRAIN_MS)
        } else {
            closeGatt(g)
        }
    }

    private fun closeGatt(g: BluetoothGatt) {
        clearQueue()
        gatt = null
        g.disconnect()
        g.close()
    }

    internal fun doDisconnect() {
        val g = gatt ?: run { state = ConnState.IDLE; return }
        state = ConnState.DISCONNECTING
        g.disconnect()
    }

    // --- Op queue ---

    internal fun enqueue(op: GattOp) {
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
                    Log.i(TAG,"→ write ${op.label} (${op.data.size}B, type=${op.writeType})")
                    writeChar(g, op.ch, op.data, op.writeType)
                }
                is GattOp.WriteDesc -> {
                    Log.i(TAG,"→ write descriptor ${op.label}")
                    writeDesc(g, op.desc, op.data)
                }
            }
            if (!ok) {
                Log.i(TAG,"op dispatch returned false, advancing")
                opInFlight = false
                pump()
            }
        }
    }

    internal fun clearQueue() = synchronized(opQueue) { opQueue.clear(); opInFlight = false }

    internal fun opCompleted() {
        synchronized(opQueue) { opInFlight = false }
        pump()
    }

    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray, writeType: Int): Boolean =
        g.writeCharacteristic(ch, data, writeType) == BluetoothStatusCodes.SUCCESS

    private fun writeDesc(g: BluetoothGatt, desc: BluetoothGattDescriptor, data: ByteArray): Boolean =
        g.writeDescriptor(desc, data) == BluetoothStatusCodes.SUCCESS

    // --- GATT callback ---

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG,"onConnectionStateChange status=0x${status.toString(16)} newState=$newState")
            if (disposed) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG,"connect failed status=0x${status.toString(16)}, closing")
                    g.close()
                    if (gatt === g) gatt = null
                    state = ConnState.IDLE
                    return
                }
                state = ConnState.DISCOVERING
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(stateTimeout)
                lastDisconnectStatus = status
                clearQueue()
                g.close()
                if (gatt === g) gatt = null
                dataChar = null; notifyChar = null
                pairingDataChar = null; handoverDataChar = null
                shutterChar = null
                gpsState = CanonGpsState.UNKNOWN
                state = ConnState.IDLE
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG,"onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) { doDisconnect(); return }
            val gpsSvc = g.getService(CanonUuids.GPS_SERVICE)
            if (gpsSvc == null) {
                Log.i(TAG,"GPS service not found")
                doDisconnect(); return
            }
            dataChar = gpsSvc.getCharacteristic(CanonUuids.GPS_DATA)
            notifyChar = gpsSvc.getCharacteristic(CanonUuids.GPS_NOTIFY)
            if (dataChar == null || notifyChar == null) {
                Log.i(TAG,"missing GPS chars"); doDisconnect(); return
            }
            pairingDataChar = g.getService(CanonUuids.PAIRING_SERVICE)
                ?.getCharacteristic(CanonUuids.PAIRING_DATA)
            g.getService(CanonUuids.HANDOVER_SERVICE)?.let { hs ->
                handoverDataChar = hs.getCharacteristic(CanonUuids.HANDOVER_DATA)

            }
            val remoteSvc = g.getService(CanonUuids.REMOTE_SERVICE)
            shutterChar = remoteSvc?.getCharacteristic(CanonUuids.REMOTE_SHUTTER)

            state = ConnState.SUBSCRIBING

            subscribe(g, notifyChar!!, "GPS_NOTIFY",
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        }

        private fun subscribe(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            label: String,
            cccdValue: ByteArray,
        ) {
            if (!g.setCharacteristicNotification(ch, true)) {
                Log.i(TAG,"setCharacteristicNotification($label) returned false"); return
            }
            val desc = ch.getDescriptor(CanonUuids.CCCD)
            if (desc == null) { Log.i(TAG,"CCCD missing on $label"); return }
            enqueue(GattOp.WriteDesc(desc, cccdValue, "CCCD $label"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG,"onDescriptorWrite ${shortUuid(descriptor.characteristic.uuid)} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD write failed status=$status — disconnecting")
                clearQueue()
                doDisconnect()
                return
            }
            opCompleted()
            if (state == ConnState.SUBSCRIBING) {
                synchronized(opQueue) {
                    if (opQueue.isEmpty() && !opInFlight) fireCanonKickoff()
                }
            }
        }

        /**
         * Canon kickoff sequence, verified against the Camera Connect app via HCI
         * snoop. Order matters — the ready-indication 0x02 0x00 on GPS_NOTIFY only
         * arrives after the pairing-0x01 write, and only if handover-0x0a has been
         * sent first. Skipping any step leaves the camera in a state where GPS
         * frames are accepted syntactically but never propagate into EXIF (GPS
         * icon on camera stays grey/off).
         */
        private fun fireCanonKickoff() {
            Log.i(TAG,"→ Canon kickoff (handover 0x0a / source query / pairing 0x01)")
            handoverDataChar?.let {
                enqueue(GattOp.Write(it, byteArrayOf(0x0a), "handover 0a",
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
            }
            dataChar?.let {
                enqueue(GattOp.Write(it,
                    byteArrayOf(0x05, 0, 0, 0, 0, 0, 0, 0),
                    "source query (8B)",
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
            }
            pairingDataChar?.let {
                enqueue(GattOp.Write(it, byteArrayOf(0x01), "pairing 01",
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
            }
            state = ConnState.REQUESTING_GPS
            handler.removeCallbacks(stateTimeout)
            handler.postDelayed(stateTimeout, 10_000L)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            Log.i(TAG,"onCharacteristicWrite ${shortUuid(ch.uuid)} status=$status")
            onWriteResult(status)
            opCompleted()
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onRssi(rssi)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            Log.i(TAG,"NOTIFY ${shortUuid(ch.uuid)} ${value.toHexCompact()}")
            handleStateByte(ch, value)
        }

        /**
         * Handle a real-time indication/notification from the camera. The
         * ready-to-receive transition is ONLY driven by an indication [2, …]
         * after the pairing-0x01 write. We don't trust read-values for state
         * because they reflect persistent last-session state, not current
         * aliveness — reading the NOTIFY char on a sleeping camera returns
         * "0200" from its last awake session.
         */
        private fun handleStateByte(ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isEmpty()) return
            if (ch.uuid != CanonUuids.GPS_NOTIFY) return
            when (value[0].toInt()) {
                1 -> gpsState = CanonGpsState.STATE_1
                2 -> {
                    gpsState = CanonGpsState.READY_TO_RECEIVE
                    if (state == ConnState.REQUESTING_GPS) {
                        handler.removeCallbacks(stateTimeout)
                        state = ConnState.GPS_SESSION_ACTIVE
                    }
                }
                3 -> gpsState = CanonGpsState.STATE_3
                5 -> {
                    val src = if (value.size >= 2) value[1].toInt() else -1
                    Log.i(TAG,"  camera GPS source = $src (${gpsSourceName(src)})")
                }
            }
        }
    }

    internal fun gpsSourceName(src: Int) = when (src) {
        0 -> "DISABLE"; 1 -> "GPS_RECEIVER"; 2 -> "BUILTIN_GPS"
        3 -> "BUILTIN_OFF"; 4 -> "SMARTPHONE"; else -> "?"
    }

    internal fun shortUuid(uuid: UUID): String = uuid.toString().substring(4, 8)
}

internal fun ByteArray.toHexCompact(): String = joinToString("") { "%02x".format(it) }
