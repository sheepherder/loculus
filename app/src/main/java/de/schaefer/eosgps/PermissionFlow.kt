package de.schaefer.eosgps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class PermGroup(
    val title: String,
    val description: String,
    val permissions: List<String>?,
    val isGranted: (Context) -> Boolean,
    val isBatteryOpt: Boolean = false,
)

internal fun permGroups(): List<PermGroup> = buildList {
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

internal fun hasAllPerms(ctx: Context): Boolean =
    permGroups().all { it.isGranted(ctx) }

@Composable
internal fun PermissionFlow(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val groups = remember { permGroups() }
    var refreshKey by remember { mutableIntStateOf(0) }

    val pending = remember(refreshKey) { groups.filter { !it.isGranted(ctx) } }
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
