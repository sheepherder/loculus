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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
    val log by TrackingState.lastLog.collectAsState()

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permsGranted = results.values.all { it }
        if (permsGranted) bondedCanon = findBondedCanon(ctx)
    }

    LaunchedEffect(permsGranted) {
        if (permsGranted) bondedCanon = findBondedCanon(ctx)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("EOS GPS", style = MaterialTheme.typography.headlineSmall)
        Text(
            "target: ${bondedCanon?.name ?: "no bonded EOS found"}",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Text(
            "conn: $connState",
            color = connStateColor(connState),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Text(
            "gps:  $gpsState",
            color = gpsStateColor(gpsState),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Text(
            "fixes sent: $fixCount   last: ${lastFix ?: "—"}",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )

        if (!permsGranted) {
            Button(onClick = {
                permLauncher.launch(allPerms())
            }) { Text("Grant permissions") }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        ) {
            Button(
                onClick = { GpsTrackingService.start(ctx) },
                enabled = permsGranted && bondedCanon != null && !serviceRunning,
            ) { Text("Start Tracking") }
            Button(
                onClick = { GpsTrackingService.stop(ctx) },
                enabled = serviceRunning,
            ) { Text("Stop Tracking") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        Text("Log:", style = MaterialTheme.typography.labelLarge)
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .background(Color(0xFFF2F2F2)).padding(6.dp),
        ) {
            val scroll = rememberScrollState()
            Column(modifier = Modifier.verticalScroll(scroll)) {
                log.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun connStateColor(s: ConnState): Color = when (s) {
    ConnState.IDLE -> Color.Gray
    ConnState.READY, ConnState.GPS_SESSION_ACTIVE -> Color(0xFF2E7D32)
    else -> Color(0xFFE67E00)
}

private fun gpsStateColor(s: CanonGpsState): Color = when (s) {
    CanonGpsState.READY_TO_RECEIVE -> Color(0xFF2E7D32)
    CanonGpsState.UNKNOWN -> Color.Gray
    else -> Color(0xFFE67E00)
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
