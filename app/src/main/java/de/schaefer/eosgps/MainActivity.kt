package de.schaefer.eosgps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var connected by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var permsGranted by remember { mutableStateOf(hasAllPerms(ctx)) }
    var bondedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    val client = remember {
        CanonGattClient(
            context = ctx.applicationContext,
            onLog = { line -> log.add(0, line) },
            onConnectionChange = { connected = it },
        )
    }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permsGranted = results.values.all { it }
        if (permsGranted) bondedDevices = loadBonded(ctx)
    }

    LaunchedEffect(permsGranted) {
        if (permsGranted) bondedDevices = loadBonded(ctx)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("EOS GPS", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = if (connected) "● GATT ready" else "○ not connected",
            color = if (connected) Color(0xFF2E7D32) else Color.Gray,
        )

        if (!permsGranted) {
            Button(onClick = {
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                )
            }) { Text("Grant permissions") }
        }

        // Action buttons at the top so they're always visible
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        ) {
            Button(
                onClick = { client.enableGpsFromSmartphone() },
                enabled = connected,
            ) { Text("Enable GPS") }
            Button(
                onClick = {
                    val nmea = Nmea.sentences(52.516275, 13.377704, 34.0)
                    client.sendNmea(nmea)
                },
                enabled = connected,
            ) { Text("Send Berlin") }
            Button(
                onClick = { client.disconnect() },
                enabled = connected,
            ) { Text("Disc") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
        ) {
            Button(
                onClick = {
                    if (sending) return@Button
                    sending = true
                    scope.launch {
                        try {
                            while (sending && client.isReady()) {
                                val nmea = Nmea.sentences(52.516275, 13.377704, 34.0)
                                client.sendNmea(nmea)
                                delay(5000)
                            }
                        } finally { sending = false }
                    }
                },
                enabled = connected && !sending,
            ) { Text("Loop 5s") }
            Button(onClick = { sending = false }, enabled = sending) { Text("Stop") }
        }

        Divider(modifier = Modifier.padding(vertical = 10.dp))

        // Compact device list — EOS first, scrolled
        Text("Bonded devices:", style = MaterialTheme.typography.labelLarge)
        LazyColumn(
            modifier = Modifier.heightIn(max = 160.dp).fillMaxWidth(),
        ) {
            items(bondedDevices) { d ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        "${d.name ?: "?"}  ${d.address}",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                    )
                    OutlinedButton(
                        onClick = { client.connect(d) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) { Text("Connect", fontSize = 11.sp) }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 10.dp))

        // Log panel — scrollable, takes remaining space
        Text("Log:", style = MaterialTheme.typography.labelLarge)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFF2F2F2))
                .padding(6.dp),
        ) {
            val scroll = rememberScrollState()
            Column(modifier = Modifier.verticalScroll(scroll)) {
                log.take(200).forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun loadBonded(ctx: Context): List<BluetoothDevice> {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) return emptyList()
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter = mgr.adapter ?: return emptyList()
    return adapter.bondedDevices
        .sortedByDescending { (it.name ?: "").contains("EOS", ignoreCase = true) }
}

private fun hasAllPerms(ctx: Context): Boolean {
    val needed = listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    return needed.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
}
