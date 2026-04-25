package de.schaefer.eosgps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission")
fun findSelectedDevice(ctx: Context): BluetoothDevice? {
    val mac = Prefs.selectedDeviceMac(ctx) ?: return null
    if (!hasBluetoothPermissions(ctx)) return null
    val adapter = bluetoothAdapter(ctx) ?: return null
    return try {
        adapter.bondedDevices.firstOrNull { it.address == mac }
    } catch (_: SecurityException) { null }
}

@SuppressLint("MissingPermission")
fun findAllBondedCanon(ctx: Context): List<BluetoothDevice> {
    if (!hasBluetoothPermissions(ctx)) return emptyList()
    val adapter = bluetoothAdapter(ctx) ?: return emptyList()
    val selectedMac = Prefs.selectedDeviceMac(ctx)
    return try {
        adapter.bondedDevices.filter { device ->
            val name = device.name ?: ""
            name.contains("EOS", true) || name.contains("Canon", true) ||
                device.address == selectedMac
        }
    } catch (_: SecurityException) { emptyList() }
}

@SuppressLint("MissingPermission")
fun resolveSelectedDevice(ctx: Context) {
    if (findSelectedDevice(ctx) != null) return
    val all = findAllBondedCanon(ctx)
    if (all.size == 1) {
        Prefs.setSelectedDeviceMac(ctx, all[0].address)
    }
}

fun bluetoothAdapter(ctx: Context): BluetoothAdapter? {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = mgr?.adapter ?: return null
    return if (adapter.isEnabled) adapter else null
}

fun readyScanner(ctx: Context): BluetoothLeScanner? {
    if (!hasBluetoothPermissions(ctx)) return null
    return bluetoothAdapter(ctx)?.bluetoothLeScanner
}

fun hasBluetoothPermissions(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) ==
        PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

fun hasBackgroundLocation(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
