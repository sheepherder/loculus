package de.schaefer.eosgps

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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
    var screen by remember {
        resolveSelectedDevice(ctx)
        mutableStateOf(currentScreen(ctx))
    }

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
