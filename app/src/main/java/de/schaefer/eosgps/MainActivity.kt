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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var teardown: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold { padding ->
                        Column(modifier = Modifier.padding(padding)) {
                            AppScreen(registerTeardown = { teardown = it })
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        teardown?.invoke()
        super.onStop()
    }
}

@SuppressLint("MissingPermission")
@Composable
fun AppScreen(registerTeardown: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val log = remember { mutableStateListOf<String>() }
    var connState by remember { mutableStateOf(ConnState.IDLE) }
    var gpsState by remember { mutableStateOf(CanonGpsState.UNKNOWN) }
    var permsGranted by remember { mutableStateOf(hasAllPerms(ctx)) }
    var bondedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    val client = remember {
        CanonGattClient(
            context = ctx.applicationContext,
            onLog = { line -> log.add(0, line) },
            onStateChange = { connState = it },
            onGpsState = { gpsState = it },
        )
    }

    DisposableEffect(client) {
        registerTeardown { client.stopAndDisconnect() }
        onDispose { client.stopAndDisconnect() }
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
        Text("conn: $connState", color = connStateColor(connState), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("gps:  $gpsState", color = gpsStateColor(gpsState), fontFamily = FontFamily.Monospace, fontSize = 12.sp)

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

        val canReqGps = connState == ConnState.READY
        val canSend = gpsState == CanonGpsState.READY_TO_RECEIVE
        val canStop = connState != ConnState.IDLE && connState != ConnState.DISCONNECTING

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        ) {
            Button(onClick = { client.requestGps() }, enabled = canReqGps) { Text("Req GPS") }
            Button(onClick = {
                // Berlin Brandenburger Tor for test
                client.sendGps(52.516275, 13.377704, 34.0, System.currentTimeMillis() / 1000)
            }, enabled = canSend) { Text("Send Berlin") }
            Button(onClick = { client.stopAndDisconnect() }, enabled = canStop) { Text("Stop + Disc") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        Text("Bonded devices:", style = MaterialTheme.typography.labelLarge)
        LazyColumn(modifier = Modifier.heightIn(max = 140.dp).fillMaxWidth()) {
            items(bondedDevices) { d ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${d.name ?: "?"}  ${d.address}", modifier = Modifier.weight(1f), fontSize = 12.sp)
                    OutlinedButton(
                        onClick = { client.connect(d) },
                        enabled = connState == ConnState.IDLE,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) { Text("Connect", fontSize = 11.sp) }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        Text("Log:", style = MaterialTheme.typography.labelLarge)
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF2F2F2)).padding(6.dp),
        ) {
            val scroll = rememberScrollState()
            Column(modifier = Modifier.verticalScroll(scroll)) {
                log.take(300).forEach { line ->
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
