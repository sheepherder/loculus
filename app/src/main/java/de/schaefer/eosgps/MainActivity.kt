package de.schaefer.eosgps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold { padding ->
                        Column(modifier = Modifier.padding(padding)) {
                            AppScreen()
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun AppScreen() {
    val ctx = LocalContext.current
    var permsGranted by remember { mutableStateOf(hasAllPerms(ctx)) }
    var bondedCanon by remember { mutableStateOf<BluetoothDevice?>(null) }

    val serviceRunning by TrackingState.serviceRunning.collectAsState()
    val connState by TrackingState.connState.collectAsState()
    val gpsState by TrackingState.gpsState.collectAsState()
    val fixCount by TrackingState.fixCount.collectAsState()
    val lastFix by TrackingState.lastFixText.collectAsState()
    val cameraInfo by TrackingState.cameraInfo.collectAsState()
    val rssi by TrackingState.rssi.collectAsState()
    val cameraPower by TrackingState.cameraPower.collectAsState()
    val lastAdvertAt by TrackingState.lastAdvertAt.collectAsState()
    val sessionStart by TrackingState.sessionStartedAt.collectAsState()
    val lastFixAt by TrackingState.lastFixAt.collectAsState()
    val writeErrors by TrackingState.writeErrors.collectAsState()
    val log by TrackingState.lastLog.collectAsState()

    // Ticker so elapsed-time displays refresh once per second while a session
    // runs. Cheap — only a handful of Text recompositions.
    var nowTick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(serviceRunning) {
        while (serviceRunning) {
            nowTick = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permsGranted = results.values.all { it }
        if (permsGranted) bondedCanon = findBondedCanon(ctx)
    }

    LaunchedEffect(permsGranted) {
        if (permsGranted) bondedCanon = findBondedCanon(ctx)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Text("EOS GPS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                bondedCanon?.name ?: "no bonded EOS",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))

        InfoCard("Connection") {
            KeyValueRow("power") {
                StatusDot(color = powerStateColor(cameraPower))
                Spacer(Modifier.width(6.dp))
                MonoText(cameraPower.name.lowercase())
                // Camera stops advertising during a GATT session; hide the
                // age then so it doesn't grow meaninglessly.
                if (lastAdvertAt != null && connState == ConnState.IDLE) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "adv ${formatDuration(nowTick - lastAdvertAt!!)} ago",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            KeyValueRow("link") {
                StatusDot(color = connStateColor(connState))
                Spacer(Modifier.width(6.dp))
                MonoText(connState.name.lowercase())
            }
            KeyValueRow("gps") {
                StatusDot(color = gpsStateColor(gpsState))
                Spacer(Modifier.width(6.dp))
                MonoText(gpsState.name.lowercase())
            }
            KeyValueRow("rssi") {
                MonoText(
                    rssi?.let { "$it dBm" } ?: "—",
                    color = rssiColor(rssi),
                )
            }
            KeyValueRow("uptime") {
                MonoText(sessionStart?.let { formatDuration(nowTick - it) } ?: "—")
            }
        }

        Spacer(Modifier.height(8.dp))

        InfoCard("Camera") {
            KeyValueRow("name") { MonoText(bondedCanon?.name ?: "—") }
            KeyValueRow("mac") { MonoText(bondedCanon?.address ?: "—") }
        }

        Spacer(Modifier.height(8.dp))

        InfoCard("GPS Session") {
            KeyValueRow("fixes") {
                MonoText("$fixCount", bold = true)
            }
            KeyValueRow("last") {
                Column {
                    MonoText(lastFix ?: "—")
                    if (lastFixAt != null) {
                        Text(
                            "${formatDuration(nowTick - lastFixAt!!)} ago",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            KeyValueRow("rate") {
                MonoText(rateText(fixCount, sessionStart, nowTick))
            }
            if (writeErrors > 0) {
                KeyValueRow("errors") {
                    MonoText("$writeErrors", color = Color(0xFFE57373))
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (!permsGranted) {
            Button(onClick = { permLauncher.launch(allPerms()) }) {
                Text("Grant permissions")
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { GpsTrackingService.start(ctx) },
                enabled = permsGranted && bondedCanon != null && !serviceRunning,
                modifier = Modifier.weight(1f),
            ) { Text("Start") }
            OutlinedButton(
                onClick = { GpsTrackingService.stop(ctx) },
                enabled = serviceRunning,
                modifier = Modifier.weight(1f),
            ) { Text("Stop") }
        }

        Spacer(Modifier.height(10.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.padding(10.dp).fillMaxSize()) {
                Text(
                    "LOG",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
                ) {
                    log.forEach { line ->
                        Text(
                            line, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

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
private fun KeyValueRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
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
    CameraPowerState.AWAKE -> Color(0xFF4CAF50)
    CameraPowerState.ASLEEP -> Color(0xFFFFB74D)
    CameraPowerState.UNSEEN -> Color(0xFF757575)
}

private fun rssiColor(rssi: Int?): Color = when {
    rssi == null -> Color(0xFF9E9E9E)
    rssi >= -60 -> Color(0xFF4CAF50)
    rssi >= -75 -> Color(0xFFFFB74D)
    else -> Color(0xFFE57373)
}

@SuppressLint("MissingPermission")
private fun findBondedCanon(ctx: Context): BluetoothDevice? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) return null
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter = mgr.adapter ?: return null
    return adapter.bondedDevices.firstOrNull { (it.name ?: "").contains("EOS", true) }
}

private fun allPerms(): Array<String> {
    val base = mutableListOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= 33) base += Manifest.permission.POST_NOTIFICATIONS
    return base.toTypedArray()
}

private fun hasAllPerms(ctx: Context): Boolean = allPerms().all {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}
