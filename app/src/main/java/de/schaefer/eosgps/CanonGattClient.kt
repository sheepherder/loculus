package de.schaefer.eosgps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID

private const val TAG = "CanonGatt"

object CanonUuids {
    val GPS_SERVICE: UUID = UUID.fromString("00040000-0000-1000-0000-d8492fffa821")
    val GPS_DATA: UUID = UUID.fromString("00040002-0000-1000-0000-d8492fffa821")
    val GPS_STATUS: UUID = UUID.fromString("00040001-0000-1000-0000-d8492fffa821")
}

@SuppressLint("MissingPermission")
class CanonGattClient(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var gpsDataChar: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected, discovering services…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                onConnectionChange(false)
                log("Disconnected")
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = g.getService(CanonUuids.GPS_SERVICE)
            if (svc == null) {
                log("GPS service NOT found. Available services:")
                g.services.forEach { log("  ${it.uuid}") }
                return
            }
            gpsDataChar = svc.getCharacteristic(CanonUuids.GPS_DATA)
            if (gpsDataChar == null) {
                log("GPS_DATA characteristic not found")
                return
            }
            log("Requesting MTU 247…")
            if (!g.requestMtu(247)) {
                log("requestMtu call returned false — proceeding anyway")
                markReady()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("onMtuChanged mtu=$mtu status=$status")
            markReady()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            log("onCharacteristicWrite ${ch.uuid} status=$status")
        }
    }

    private fun markReady() {
        log("GATT ready — GPS service + MTU negotiated")
        ready = true
        onConnectionChange(true)
    }

    fun connect(device: BluetoothDevice) {
        log("Connecting to ${device.address}…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun isReady(): Boolean = ready

    fun enableGpsFromSmartphone(): Boolean {
        val ch = gpsDataChar ?: return false
        return write(ch, byteArrayOf(0x06, 0x04))
    }

    fun sendNmea(nmea: String): Boolean {
        val ch = gpsDataChar ?: return false
        val nmeaBytes = nmea.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(nmeaBytes.size + 1)
        payload[0] = 0x04
        System.arraycopy(nmeaBytes, 0, payload, 1, nmeaBytes.size)
        return write(ch, payload)
    }

    private fun write(ch: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }
}
