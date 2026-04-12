package de.schaefer.eosgps

import kotlinx.coroutines.flow.MutableStateFlow

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

    fun log(line: String) {
        val cur = lastLog.value
        lastLog.value = (listOf(line) + cur).take(200)
    }
}
