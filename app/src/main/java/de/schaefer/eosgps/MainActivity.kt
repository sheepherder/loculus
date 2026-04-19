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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

private const val AD_STALENESS_MS = 8_000L

private enum class Screen { PERMISSION, DEVICE_PICKER, MAIN }

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

// ---------------------------------------------------------------------------
// Screen router
// ---------------------------------------------------------------------------

private fun currentScreen(ctx: Context): Screen = when {
    !hasAllPerms(ctx) -> Screen.PERMISSION
    findSelectedDevice(ctx) == null -> Screen.DEVICE_PICKER
    else -> Screen.MAIN
}

@Composable
fun AppScreen() {
    val ctx = LocalContext.current
    resolveSelectedDevice(ctx)
    var screen by remember { mutableStateOf(currentScreen(ctx)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resolveSelectedDevice(ctx)
                screen = currentScreen(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (screen) {
        Screen.PERMISSION -> PermissionFlow(onDone = {
            resolveSelectedDevice(ctx)
            screen = currentScreen(ctx)
        })
        Screen.DEVICE_PICKER -> DevicePicker(
            onDeviceSelected = { screen = currentScreen(ctx) },
            onBack = if (Prefs.selectedDeviceMac(ctx) != null) {{
                FgScanner.start(ctx)
                screen = Screen.MAIN
            }} else null,
        )
        Screen.MAIN -> MainScreen(onChangeDevice = {
            if (TrackingState.serviceRunning.value) {
                GpsTrackingService.stop(ctx)
            }
            CanonScanRegistrar.unregister(ctx)
            FgScanner.stop()
            screen = Screen.DEVICE_PICKER
        })
    }
}

// ---------------------------------------------------------------------------
// Permission flow — one group per screen, sequentially
// ---------------------------------------------------------------------------

private data class PermGroup(
    val title: String,
    val description: String,
    val permissions: List<String>?,
    val isGranted: (Context) -> Boolean,
    val isBatteryOpt: Boolean = false,
)

private fun permGroups(): List<PermGroup> = buildList {
    add(PermGroup(
        title = "Bluetooth",
        description = "Die App kommuniziert per Bluetooth LE mit deiner Canon-Kamera. Dafür braucht sie Zugriff auf Bluetooth.",
        permissions = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
        isGranted = { hasBluetoothPermissions(it) },
    ))
    add(PermGroup(
        title = "Standort",
        description = "GPS-Koordinaten werden an die Kamera gesendet und in die EXIF-Daten deiner Fotos geschrieben. Außerdem benötigt Android Standortzugriff für Bluetooth-LE-Scans.",
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        isGranted = { ContextCompat.checkSelfPermission(it, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED },
    ))
    add(PermGroup(
        title = "Hintergrund-Standort",
        description = "Damit GPS auch bei geschlossener App an die Kamera gestreamt wird, muss der Standortzugriff auf 'Immer zulassen' stehen.",
        permissions = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        isGranted = { hasBackgroundLocation(it) },
    ))
    if (Build.VERSION.SDK_INT >= 33) {
        add(PermGroup(
            title = "Benachrichtigungen",
            description = "Während GPS an die Kamera gestreamt wird, zeigt die App eine Benachrichtigung. So weißt du immer, wenn die Verbindung aktiv ist.",
            permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            isGranted = { ContextCompat.checkSelfPermission(it, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
        ))
    }
    add(PermGroup(
        title = "Akku-Optimierung",
        description = "Ohne diese Ausnahme kann Android die App im Hintergrund drosseln und die Bluetooth-Verbindung unterbrechen.",
        permissions = null,
        isGranted = { isBatteryOptIgnored(it) },
        isBatteryOpt = true,
    ))
}

@Composable
private fun PermissionFlow(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val groups = remember { permGroups() }
    var refreshKey by remember { mutableIntStateOf(0) }

    val pending = groups.filter { !it.isGranted(ctx) }
    if (pending.isEmpty()) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val group = pending.first()

    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshKey++ }

    val singlePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshKey++ }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "EOS GPS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(48.dp))
        Text(
            group.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            group.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                if (group.isBatteryOpt) {
                    requestBatteryOptExemption(ctx)
                } else {
                    val perms = group.permissions ?: return@Button
                    if (perms.size == 1) {
                        singlePermLauncher.launch(perms[0])
                    } else {
                        multiPermLauncher.launch(perms.toTypedArray())
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (group.isBatteryOpt) "Einstellung öffnen" else "Erlauben")
        }

        if (!group.isBatteryOpt && group.permissions != null) {
            val anyDenied = group.permissions.any {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
            if (anyDenied && refreshKey > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Falls die Abfrage nicht erscheint: in den App-Einstellungen aktivieren.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", ctx.packageName, null))
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }) {
                    Text("App-Einstellungen")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Device picker
// ---------------------------------------------------------------------------

@SuppressLint("MissingPermission")
@Composable
private fun DevicePicker(onDeviceSelected: () -> Unit, onBack: (() -> Unit)? = null) {
    if (onBack != null) {
        BackHandler(onBack = onBack)
    }
    val ctx = LocalContext.current
    var devices by remember { mutableStateOf(findAllBondedCanon(ctx)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                devices = findAllBondedCanon(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "EOS GPS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            "Kamera auswählen",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Wähle die Canon-Kamera, die GPS-Daten empfangen soll.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        if (devices.isEmpty()) {
            Text(
                "Keine Canon-Kamera gekoppelt.\n\nKopple deine Kamera zuerst über die Canon Camera Connect App oder die Android Bluetooth-Einstellungen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                Text("Bluetooth-Einstellungen")
            }
        } else {
            val currentMac = Prefs.selectedDeviceMac(ctx)
            for (device in devices) {
                val isSelected = device.address == currentMac
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            Prefs.setSelectedDeviceMac(ctx, device.address)
                            onDeviceSelected()
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                        Text(
                            device.name ?: "Canon EOS",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            device.address,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { devices = findAllBondedCanon(ctx) }) {
            Text("Aktualisieren")
        }
    }
}

// ---------------------------------------------------------------------------
// Main screen (existing UI, extracted)
// ---------------------------------------------------------------------------

@SuppressLint("MissingPermission")
@Composable
private fun MainScreen(onChangeDevice: () -> Unit) {
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var nowTick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycleOwner) {
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

        InfoCard("Connection") {
            KeyValueRow("power") {
                StatusDot(color = powerStateColor(effectivePower))
                Spacer(Modifier.width(6.dp))
                MonoText(effectivePower.label())
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
                        "CAMERA",
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
                KeyValueRow("name") { MonoText(selectedDevice?.name ?: "—") }
                KeyValueRow("mac") { MonoText(selectedDevice?.address ?: "—") }
            }
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

        InfoCard("GPS-Tracking") {
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

private fun hasAllPerms(ctx: Context): Boolean =
    permGroups().all { it.isGranted(ctx) }

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
