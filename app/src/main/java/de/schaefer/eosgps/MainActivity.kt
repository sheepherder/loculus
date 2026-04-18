package de.schaefer.eosgps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

// Camera advertises ~200 ms awake, ~3 s asleep. Eight seconds without a
// single advertisement means the camera is gone (battery pulled, out of
// range, or physically off beyond BLE standby). Used for the UI-side
// staleness derivation when no GATT session is active.
private const val AD_STALENESS_MS = 8_000L

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

    override fun onResume() {
        super.onResume()
        TrackingState.appVisible = true
    }

    override fun onPause() {
        super.onPause()
        TrackingState.appVisible = false
    }
}

@SuppressLint("MissingPermission")
@Composable
fun AppScreen() {
    val ctx = LocalContext.current
    var permsGranted by remember { mutableStateOf(hasAllPerms(ctx)) }
    var bondedCanon by remember { mutableStateOf<BluetoothDevice?>(null) }

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

    // The single user switch. Persisted in Prefs; UI mirrors it so toggles
    // feel instant and we sync Prefs + scanner side-effects on click.
    var trackingEnabled by remember { mutableStateOf(Prefs.trackingEnabled(ctx)) }
    var batteryOptIgnored by remember { mutableStateOf(isBatteryOptIgnored(ctx)) }
    var backgroundLocationGranted by remember { mutableStateOf(hasBackgroundLocation(ctx)) }

    // Activity-scoped scanner lifecycle.
    //  ON_RESUME: unregister the OS-offloaded scan (our live scanner takes
    //             over), start the live scanner. Re-read permission state —
    //             user may have flipped something in Settings while we were
    //             away.
    //  ON_PAUSE:  stop the live scanner. If tracking is on and no GATT
    //             session is active, arm the OS-offloaded scan so the system
    //             wakes us on the next POWER_ON advertisement.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    CanonScanRegistrar.unregister(ctx)
                    FgScanner.start(ctx)
                    batteryOptIgnored = isBatteryOptIgnored(ctx)
                    backgroundLocationGranted = hasBackgroundLocation(ctx)
                    trackingEnabled = Prefs.trackingEnabled(ctx)
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 1 Hz ticker for "X ago" displays. Bound to STARTED so it pauses
    // automatically while the Activity is in the background.
    var nowTick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowTick = SystemClock.elapsedRealtime()
                delay(1000)
            }
        }
    }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permsGranted = results.values.all { it }
        if (permsGranted) {
            bondedCanon = findBondedCanon(ctx)
            // Permissions may have just been granted — kick the scanner so
            // the first ads land immediately.
            FgScanner.start(ctx)
        }
    }

    // Background-location has to be asked for separately on Android 11+ —
    // the system dialog triggered by this request steers the user to
    // Settings with "Allow all the time".
    val backgroundLocationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        backgroundLocationGranted = granted
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

        // Derive the displayed power state from advertisement freshness. If
        // the camera went out of range / lost its battery, ads simply stop —
        // the last remembered state (e.g. POWER_ON) is then meaningless. We
        // don't keep a separate watchdog timer: nowTick already ticks once
        // per second, so this recomputes on every tick for free.
        //
        // During a GATT session the camera deliberately stops advertising;
        // trust the last known state instead of flipping to UNSEEN.
        val effectivePower = when {
            connState != ConnState.IDLE -> cameraPower
            lastAdvertAt == null -> CameraPowerState.UNSEEN
            nowTick - lastAdvertAt!! > AD_STALENESS_MS -> CameraPowerState.UNSEEN
            else -> cameraPower
        }

        InfoCard("Connection") {
            KeyValueRow("power") {
                StatusDot(color = powerStateColor(effectivePower))
                Spacer(Modifier.width(6.dp))
                MonoText(effectivePower.label())
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

        Spacer(Modifier.height(8.dp))

        // Kamera-Steuerung: nur während aktiver GPS-Session verfügbar, weil
        // die Shutter-Writes sowieso am READY_TO_RECEIVE-Gate in
        // CanonGattClient abprallen.
        if (gpsState == CanonGpsState.READY_TO_RECEIVE) {
            InfoCard("Kamera-Steuerung") {
                Button(
                    onClick = { GpsTrackingService.triggerShutter(ctx) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) { Text("Auslösen") }
            }
            Spacer(Modifier.height(8.dp))
        }

        InfoCard("Tracking") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    MonoText(if (trackingEnabled) "on" else "off", bold = true)
                    Text(
                        if (trackingEnabled)
                            "verbindet & streamt GPS sobald die Kamera angeht"
                        else
                            "zeigt nur Ads, kein GPS-Transmit",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = trackingEnabled,
                    enabled = permsGranted && bondedCanon != null,
                    onCheckedChange = { want ->
                        trackingEnabled = want
                        Prefs.setTrackingEnabled(ctx, want)
                        if (!want) {
                            // OFF: tear everything down. Defensive unregister
                            // even if we think nothing is armed.
                            CanonScanRegistrar.unregister(ctx)
                            if (TrackingState.serviceRunning.value) {
                                GpsTrackingService.stop(ctx)
                            }
                        }
                        // ON: nothing to do here. Activity is visible (we're
                        // in a click handler), FgScanner is already running,
                        // and it will start the FGS on the next POWER_ON ad.
                        // The OS-offloaded scan gets armed on our next
                        // ON_PAUSE handoff.
                    },
                    colors = SwitchDefaults.colors(),
                )
            }
            if (trackingEnabled && !batteryOptIgnored) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Akku-Optimierung ausschalten, sonst throttelt Android den Scan",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = Color(0xFFFFB74D),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { requestBatteryOptExemption(ctx) }) {
                    Text("Akku-Optimierung öffnen")
                }
            }
            if (trackingEnabled && !backgroundLocationGranted) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Hintergrund-Ortung: ohne 'Immer zulassen' kommen Fixes nur wenn App offen ist",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = Color(0xFFFFB74D),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = {
                    backgroundLocationLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                }) {
                    Text("Hintergrund-Ortung freigeben")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (!permsGranted) {
            Button(
                onClick = { permLauncher.launch(allPerms()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant permissions")
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
    CameraPowerState.POWER_ON -> Color(0xFF4CAF50)         // green
    CameraPowerState.AUTO_POWER_OFF -> Color(0xFFFFB74D)   // orange
    CameraPowerState.POWER_SW_OFF -> Color(0xFF9E9E9E)     // grey (deliberate off)
    CameraPowerState.UNSEEN -> Color(0xFF757575)           // dim grey (no contact)
}

private fun rssiColor(rssi: Int?): Color = when {
    rssi == null -> Color(0xFF9E9E9E)
    rssi >= -60 -> Color(0xFF4CAF50)
    rssi >= -75 -> Color(0xFFFFB74D)
    else -> Color(0xFFE57373)
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

private fun isBatteryOptIgnored(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

@SuppressLint("BatteryLife")
private fun requestBatteryOptExemption(ctx: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.fromParts("package", ctx.packageName, null))
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}
