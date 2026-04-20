package de.schaefer.eosgps

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun WelcomeScreen(onContinue: () -> Unit) {
    val intervalRange = "${GPS_FASTEST_INTERVAL_MS / 1000}–${GPS_INTERVAL_MS / 1000}"
    val maxAgeSec = INITIAL_FIX_MAX_AGE_MS / 1000

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            APP_NAME,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "GPS für deine Canon EOS",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))

        val body = buildString {
            append("Canon-EOS-Kameras haben leider kein GPS-Modul. Sie können aber per Bluetooth GPS-Koordinaten vom Smartphone übernehmen. So kann der Aufnahmeort eines Fotos in den EXIF-Daten gespeichert werden.\n\n")
            append("Diese App wartet passiv, bis die Kamera eingeschaltet wird. Sie verbindet sich dann und sendet dann alle $intervalRange Sekunden die aktuelle GPS-Position.\n\n")
            append("Wenn das Smartphone bei Verbindungsstart eine maximal $maxAgeSec Sekunden alte Position hat, wird diese sofort gesendet. So hat auch ein schnelles Foto eine (evtl. nicht ganz aktuelle) Position.\n\n")
            append("Der Stromverbrauch ist minimal: Android weckt die App nur, wenn ein Funkpaket von der Kamera meldet, dass sie eingeschaltet ist. Moderne Handys können diese Pakete komplett in Hardware filtern.\n\n")
            append("Voraussetzung: Die Kamera muss einmalig über die Canon-App CameraConnect gekoppelt werden. Die Kopplung speichert Android geräteseitig, diese App kann sie mitnutzen. Die Canon-App wird also nicht weiter benötigt.\n\n")
            append("Diese App kann also installiert und vergessen werden. Alles passiert im Hintergrund. Man kann sie öffnen und sieht live den Kamerastatus (Bonusfeature: Fernauslöser!). Der Schalter im Hauptbildschirm deaktiviert alles komplett.\n\n")
            append("Um so zu funktionieren, braucht die App natürlich eine ganze Reihe von Berechtigungen. Diese werden jetzt Schritt für Schritt abgefragt. Nichts davon ist optional.")
        }

        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Los geht's")
        }
    }
}
