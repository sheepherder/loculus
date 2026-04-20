package de.schaefer.eosgps

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Power state decoded from bits 1–2 of byte 5 of the Canon BLE advertisement
 * (manufacturer-specific data, company id 0x01A9). The three non-UNSEEN
 * values are Canon's own enum names (EnumC0259k in the decompiled app);
 * connecting is only safe in POWER_ON.
 *   POWER_ON        — camera fully on, connect + GATT work without waking it
 *   AUTO_POWER_OFF  — camera auto-idled after inactivity, mechanics parked
 *                     loosely; connecting would wake it audibly
 *   POWER_SW_OFF    — main switch off, shutter closed, lens parked
 *   UNSEEN          — no recent advertisement (out of range / battery out)
 */
enum class CameraPowerState { UNSEEN, POWER_ON, AUTO_POWER_OFF, POWER_SW_OFF }

fun CameraPowerState.label(): String = when (this) {
    CameraPowerState.POWER_ON -> "on"
    CameraPowerState.AUTO_POWER_OFF -> "auto-off"
    CameraPowerState.POWER_SW_OFF -> "power off"
    CameraPowerState.UNSEEN -> "no signal"
}

/**
 * Shared observable state between the tracking service, the foreground
 * scanner and the UI. Simple singleton with StateFlow fields — no DI, no
 * repository. Writers: FGS + FgScanner. Reader: Compose UI via
 * collectAsState.
 */
object TrackingState {
    /**
     * True while the MainActivity is in `onResume..onPause`. Written by the
     * Activity, read by [GpsTrackingService.onDestroy] to decide whether to
     * re-arm [CanonScanRegistrar]. Plain `var` — all access happens on the
     * main thread (both Activity lifecycle callbacks and the Service's
     * lifecycle callbacks run there).
     */
    var appVisible: Boolean = false

    val serviceRunning = MutableStateFlow(false)
    val connState = MutableStateFlow(ConnState.IDLE)
    val gpsState = MutableStateFlow(CanonGpsState.UNKNOWN)
    val lastFixText = MutableStateFlow<String?>(null)
    val fixCount = MutableStateFlow(0)

    val rssi = MutableStateFlow<Int?>(null)
    val cameraPower = MutableStateFlow(CameraPowerState.UNSEEN)
    val lastAdvertAt = MutableStateFlow<Long?>(null)
    // elapsedRealtime() when the GPS session became active, null otherwise
    val sessionStartedAt = MutableStateFlow<Long?>(null)
    val lastFixAt = MutableStateFlow<Long?>(null)  // elapsedRealtime() of last successful write
    val writeErrors = MutableStateFlow(0)
    val accuracy = MutableStateFlow<Float?>(null)
    val altitude = MutableStateFlow<Double?>(null)
    val speed = MutableStateFlow<Float?>(null)

    fun resetSession() {
        fixCount.value = 0
        lastFixText.value = null
        sessionStartedAt.value = null
        lastFixAt.value = null
        rssi.value = null
        writeErrors.value = 0
        accuracy.value = null
        altitude.value = null
        speed.value = null
    }
}
