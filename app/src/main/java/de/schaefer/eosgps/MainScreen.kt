package de.schaefer.eosgps

import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

private const val AD_STALENESS_MS = 8_000L

@SuppressLint("MissingPermission")
@Composable
internal fun MainScreen(onChangeDevice: () -> Unit) {
    val ctx = LocalContext.current
    var selectedDevice by remember { mutableStateOf(findSelectedDevice(ctx)) }

    val connState by TrackingState.connState.collectAsState()
    val gpsState by TrackingState.gpsState.collectAsState()
    val fixCount by TrackingState.fixCount.collectAsState()
    val lastFix by TrackingState.lastFixText.collectAsState()
    val rssi by TrackingState.rssi.collectAsState()
    val cameraPower by TrackingState.cameraPower.collectAsState()
    val lastAdvertAt by TrackingState.lastAdvertAt.collectAsState()
    val sessionStart by TrackingState.sessionStartedAt.collectAsState()
    val lastFixAt by TrackingState.lastFixAt.collectAsState()
    val writeErrors by TrackingState.writeErrors.collectAsState()
    val accuracy by TrackingState.accuracy.collectAsState()
    val altitude by TrackingState.altitude.collectAsState()
    val speed by TrackingState.speed.collectAsState()

    var trackingEnabled by remember { mutableStateOf(Prefs.trackingEnabled(ctx)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    CanonScanRegistrar.unregister(ctx)
                    FgScanner.start(ctx)
                    trackingEnabled = Prefs.trackingEnabled(ctx)
                    selectedDevice = findSelectedDevice(ctx)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    FgScanner.stop()
                    if (Prefs.trackingEnabled(ctx) && !TrackingState.serviceRunning.value) {
                        CanonScanRegistrar.register(ctx)
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            CanonScanRegistrar.unregister(ctx)
            FgScanner.start(ctx)
            trackingEnabled = Prefs.trackingEnabled(ctx)
            selectedDevice = findSelectedDevice(ctx)
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var nowTick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowTick = SystemClock.elapsedRealtime()
                delay(1000)
            }
        }
    }

    val effectivePower = when {
        connState != ConnState.IDLE -> cameraPower
        lastAdvertAt == null -> CameraPowerState.UNSEEN
        nowTick - lastAdvertAt!! > AD_STALENESS_MS -> CameraPowerState.UNSEEN
        else -> cameraPower
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("EOS GPS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            selectedDevice?.name ?: "—",
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))

        InfoCard("Verbindung") {
            KeyValueRow("Status") {
                StatusDot(color = powerStateColor(effectivePower))
                Spacer(Modifier.width(6.dp))
                MonoText(effectivePower.label())
            }
            KeyValueRow("Link") {
                StatusDot(color = connStateColor(connState))
                Spacer(Modifier.width(6.dp))
                MonoText(connState.name.lowercase())
            }
            KeyValueRow("GPS") {
                StatusDot(color = gpsStateColor(gpsState))
                Spacer(Modifier.width(6.dp))
                MonoText(gpsState.name.lowercase())
            }
            KeyValueRow("RSSI") {
                MonoText(
                    rssi?.let { "$it dBm" } ?: "—",
                    color = rssiColor(rssi),
                )
            }
            KeyValueRow("Dauer") {
                MonoText(sessionStart?.let { formatDuration(nowTick - it) } ?: "—")
            }
        }

        Spacer(Modifier.height(8.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth().clickable { onChangeDevice() },
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "KAMERA",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "ändern",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                KeyValueRow("Name") { MonoText(selectedDevice?.name ?: "—") }
                KeyValueRow("MAC") { MonoText(selectedDevice?.address ?: "—") }
            }
        }

        Spacer(Modifier.height(8.dp))

        val gpsActive = gpsState == CanonGpsState.READY_TO_RECEIVE
        InfoCard(if (gpsActive) "GPS-Sitzung · aktiv" else "GPS-Sitzung · inaktiv") {
            val contentAlpha = if (gpsActive) 1f else 0.38f
            Column(modifier = Modifier.graphicsLayer { alpha = contentAlpha }) {
                KeyValueRow("Fixes") {
                    MonoText("$fixCount", bold = true)
                }
                KeyValueRow("Letzter", alignTop = true) {
                    Column {
                        MonoText(lastFix ?: "—")
                        val subLine = if (lastFixAt != null) {
                            val parts = buildList {
                                if (gpsActive) add("vor ${formatDuration(nowTick - lastFixAt!!)}")
                                accuracy?.let { add("±%.0f m".format(it)) }
                                altitude?.let { add("%.0f m".format(it)) }
                                speed?.let { add("%.0f km/h".format(it * 3.6f)) }
                            }
                            parts.joinToString(" · ")
                        } else "—"
                        Text(
                            subLine,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                KeyValueRow("Rate") {
                    MonoText(rateText(fixCount, sessionStart, nowTick))
                }
                if (writeErrors > 0) {
                    KeyValueRow("Fehler") {
                        MonoText("$writeErrors", color = Color(0xFFE57373))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val shutterReady = gpsState == CanonGpsState.READY_TO_RECEIVE
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "AUSLÖSER",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            if (shutterReady) Color.White
                            else Color(0xFF444444)
                        )
                        .clickable(enabled = shutterReady) {
                            GpsTrackingService.triggerShutter(ctx)
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (shutterReady) Color(0xFFE0E0E0)
                                else Color(0xFF333333)
                            ),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        InfoCard("GPS-Übertragung") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (trackingEnabled) "Aktiv" else "Aus",
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (trackingEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = trackingEnabled,
                    onCheckedChange = { want ->
                        trackingEnabled = want
                        Prefs.setTrackingEnabled(ctx, want)
                        if (!want) {
                            CanonScanRegistrar.unregister(ctx)
                            if (TrackingState.serviceRunning.value) {
                                GpsTrackingService.stop(ctx)
                            }
                        }
                    },
                    colors = SwitchDefaults.colors(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared composables
// ---------------------------------------------------------------------------

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun KeyValueRow(label: String, alignTop: Boolean = false, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = if (alignTop) Alignment.Top else Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(68.dp),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) { value() }
    }
}

@Composable
private fun MonoText(text: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun formatDuration(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun rateText(fixCount: Int, sessionStart: Long?, nowTick: Long): String {
    if (sessionStart == null || fixCount == 0) return "—"
    val minutes = (nowTick - sessionStart) / 60000.0
    if (minutes <= 0.0) return "—"
    return "%.1f/min".format(fixCount / minutes)
}

private fun connStateColor(s: ConnState): Color = when (s) {
    ConnState.IDLE -> Color(0xFF757575)
    ConnState.GPS_SESSION_ACTIVE -> Color(0xFF4CAF50)
    ConnState.DISCONNECTING -> Color(0xFFE57373)
    else -> Color(0xFFFFB74D)
}

private fun gpsStateColor(s: CanonGpsState): Color = when (s) {
    CanonGpsState.READY_TO_RECEIVE -> Color(0xFF4CAF50)
    CanonGpsState.UNKNOWN -> Color(0xFF757575)
    else -> Color(0xFFFFB74D)
}

private fun powerStateColor(s: CameraPowerState): Color = when (s) {
    CameraPowerState.POWER_ON -> Color(0xFF4CAF50)
    CameraPowerState.AUTO_POWER_OFF -> Color(0xFFFFB74D)
    CameraPowerState.POWER_SW_OFF -> Color(0xFF9E9E9E)
    CameraPowerState.UNSEEN -> Color(0xFF757575)
}

private fun rssiColor(rssi: Int?): Color = when {
    rssi == null -> Color(0xFF9E9E9E)
    rssi >= -60 -> Color(0xFF4CAF50)
    rssi >= -75 -> Color(0xFFFFB74D)
    else -> Color(0xFFE57373)
}
