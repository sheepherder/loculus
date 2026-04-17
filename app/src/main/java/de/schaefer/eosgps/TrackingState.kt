package de.schaefer.eosgps

import kotlinx.coroutines.flow.MutableStateFlow

data class CameraInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val software: String? = null,
    val serial: String? = null,
)

/**
 * Power state derived from Canon BLE advertisement manufacturer data (last
 * byte of the 6-byte Canon-specific payload under company ID 0x01A9):
 *   0x02 → AWAKE   — camera is fully on, safe to connect + fire kickoff
 *   0x05 → ASLEEP  — camera in BLE standby; connecting & writing would wake it
 *   anything else → UNKNOWN, treat conservatively as asleep
 * When no advertisement has been observed (recently) we report UNSEEN — the
 * camera may be out of range, battery-less, or fully off.
 */
enum class CameraPowerState { UNSEEN, ASLEEP, AWAKE }

/**
 * Shared observable state between the tracking service and the UI.
 *
 * Simple singleton with StateFlow fields — no DI, no fancy repository. The
 * service writes, Compose reads via collectAsState.
 */
object TrackingState {
    val serviceRunning = MutableStateFlow(false)
    val connState = MutableStateFlow(ConnState.IDLE)
    val gpsState = MutableStateFlow(CanonGpsState.UNKNOWN)
    val lastFixText = MutableStateFlow<String?>(null)
    val fixCount = MutableStateFlow(0)
    val lastLog = MutableStateFlow<List<String>>(emptyList())

    val cameraInfo = MutableStateFlow(CameraInfo())
    val rssi = MutableStateFlow<Int?>(null)
    val cameraPower = MutableStateFlow(CameraPowerState.UNSEEN)
    val lastAdvertAt = MutableStateFlow<Long?>(null)
    // True when we currently have an offloaded PendingIntent scan armed with
    // the OS. Written by CanonScanRegistrar and ScanResultReceiver.
    val scanRegistered = MutableStateFlow(false)
    // elapsedRealtime() when the GPS session became active, null otherwise
    val sessionStartedAt = MutableStateFlow<Long?>(null)
    val lastFixAt = MutableStateFlow<Long?>(null)  // elapsedRealtime() of last successful write
    val writeErrors = MutableStateFlow(0)

    fun log(line: String) {
        val cur = lastLog.value
        lastLog.value = (listOf(line) + cur).take(200)
    }

    fun resetSession() {
        fixCount.value = 0
        lastFixText.value = null
        sessionStartedAt.value = null
        lastFixAt.value = null
        rssi.value = null
        writeErrors.value = 0
        cameraInfo.value = CameraInfo()
        cameraPower.value = CameraPowerState.UNSEEN
        lastAdvertAt.value = null
    }
}
