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
fun findBondedCanon(ctx: Context): BluetoothDevice? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) return null
    val adapter = bluetoothAdapter(ctx) ?: return null
    return try {
        adapter.bondedDevices.firstOrNull { (it.name ?: "").contains("EOS", true) }
    } catch (_: SecurityException) {
        null
    }
}

/**
 * Returns the enabled BLE scanner iff all preconditions for a scan are met:
 * runtime BT permissions, an adapter, the adapter is on, and a bonded EOS
 * camera exists. Returns `null` (not exceptions) on any failure — callers
 * simply no-op.
 */
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
