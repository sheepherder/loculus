# Entwicklungsverlauf — Canon EOS GPS Reverse Engineering

Dieses Dokument erzählt die Geschichte des Projekts chronologisch: was wurde probiert, was hat funktioniert, was nicht, und welche Erkenntnisse sind unterwegs entstanden. Für die rein technische Protokoll-Referenz siehe [`PROJEKT_DOKUMENTATION.md`](PROJEKT_DOKUMENTATION.md).

## Motivation

Die Canon EOS R6 Mark II kann GPS-Daten in EXIF schreiben — aber nur, wenn die offizielle **Canon Camera Connect App** auf einem Smartphone aktiv läuft und über Bluetooth LE mit der Kamera verbunden ist. Geht das Handy in den Hintergrund oder wird die App beendet, hört das Geotagging auf.

Ziel dieses Projekts: eine Lösung bauen, bei der die Kamera **automatisch** GPS-Daten bekommt, sobald sie eingeschaltet ist — ohne dass man die Canon-App manuell starten muss.

## Phase 1 — Reverse Engineering (Januar 2026)

### Ausgangslage

- Kamera: Canon EOS R6 Mark II (`EOSR6m2_7BC192`, MAC `34:90:EA:7B:C1:93`)
- Ziel-Handy: Pixel 9a, war bereits mit der Kamera über die Canon-App gepairt
- Entwicklungsrechner: Arch Linux mit CSR USB-Bluetooth-Adapter (`0a12:0001`)

### Vorhandene Ressourcen

Andere haben teile dieses Protokolls schon angefasst — allerdings nur für Remote-Auslösung, **nicht für GPS**:

- [Ian Douglas Scott — Canon DSLR Bluetooth Remote Protocol](https://iandouglasscott.com/2018/07/04/canon-dslr-bluetooth-remote-protocol/) — erste öffentliche Analyse des Canon-Pairing-Protokolls
- [pklaus/canoremote](https://github.com/pklaus/canoremote), [ids1024/cannon-bluetooth-remote](https://github.com/ids1024/cannon-bluetooth-remote), [maxmacstn/ESP32-Canon-BLE-Remote](https://github.com/maxmacstn/ESP32-Canon-BLE-Remote)

**Keines davon implementiert GPS-Übertragung** — das war offenbar noch niemand öffentlich angegangen.

### Dekompilat der Canon Camera Connect App

APKs von der Kamera-App wurden mit [**jadx**](https://github.com/skylot/jadx) dekompiliert und liegen unter `apk/CameraConnect-decompiled/`. Aus der Analyse ergaben sich:

- Canon-UUID-Basis `d8492fffa821` — alle proprietären Services/Characteristics folgen dem Muster `0000XXXX-0000-1000-0000-d8492fffa821`
- GATT Service `0004` = GPS Service
  - `00040001` Read/Notify = Status
  - `00040002` Write = Daten- und Kommando-Kanal
  - `00040003` Notify = Notifications
- Kommando-Byte-Tabelle (siehe [`PROJEKT_DOKUMENTATION.md`](PROJEKT_DOKUMENTATION.md))
- Format der GPS-Daten: Standard NMEA-0183 (`$GPGGA...`, `$GPRMC...`)

Ein entscheidendes Detail — das **Prefix-Byte `0x04`** vor den NMEA-Bytes — wurde zu diesem Zeitpunkt übersehen. Es steckt in `com/canon/eos/T.java:183-188`, fiel aber erst später auf (siehe Phase 3).

### Python-PoC

Auf Basis der dekompilierten Protokollkenntnisse entstand `canon_gps_poc.py` — ein asynchrones Script mit [**bleak**](https://github.com/hbldh/bleak) (Python BLE-Lib), das:
- NMEA-Sentences korrekt generieren kann (offline testbar)
- Nach Canon-Kameras scannt (funktionierte)
- Pairing / Connect / GPS-Write implementiert

**Blocker:** Der CSR-USB-Bluetooth-Adapter (`0a12:0001`) am Linux-Rechner warf bei jedem BLE-Connect `le-connection-abort-by-local`. CSR-Adapter sind für ihre schlechte BLE-Unterstützung berüchtigt. Alle BLE-Writes blieben untestbar.

Damit endete Phase 1: **Protokoll theoretisch verstanden, praktisch unerreichbar wegen Hardware.**

## Phase 2 — Pivot auf Android (April 2026)

Einige Wochen später wurde klar: statt einen neuen BT-Adapter zu kaufen, kann man direkt auf dem **Pixel 9a** entwickeln. Das Handy hat bereits eine Pairing-Beziehung zur Kamera (über die Canon-App eingerichtet) — und Android verwaltet Bond-Credentials auf System-Ebene. Eine neue App kann die bestehende Bond-Beziehung **einfach wiederverwenden**, ohne selbst pairen zu müssen.

Das umgeht das CSR-Problem komplett.

### Minimal-App

Eine rudimentäre Kotlin/Compose-App unter `android/` — eine Activity, eine Liste der gebondeten Geräte, ein paar Buttons für `Connect` / `Enable GPS` / `Send Berlin`. Kein Foreground Service, keine echte Location, keine UI-Politur. Das Ziel war nur: **das Protokoll auf echter Hardware validieren**.

Toolchain-Stand April 2026:
- AGP 9.1.0 (März 2026)
- Kotlin 2.3.20
- Gradle 9.4.1
- Compose BOM 2026.03.00
- compileSdk 36, minSdk 31

Ein paar Stolpersteine der aktuellen Tool-Generation:
- AGP 9 hat Kotlin-Support eingebaut — das Plugin `org.jetbrains.kotlin.android` muss **entfernt** werden (Build schlägt sonst mit klarer Fehlermeldung fehl).
- `kotlinOptions { ... }` ist deprecated — Umstieg auf `kotlin { compilerOptions { ... } }`.
- AGP 9 verlangt Gradle ≥ 9.3.1, also Wrapper hochziehen.

## Phase 3 — Live-Debugging und die entscheidenden Fehlschläge

### Erster Verbindungsversuch

GATT connect funktionierte sofort. Service Discovery fand den Canon GPS-Service. Das `Enable GPS`-Kommando (`0x06 0x04`, zwei Byte) wurde geschrieben — **`onCharacteristicWrite status=0`**. Auf der Kamera wechselte das GPS-Icon von "aus" auf **blinkend grau** — die Kamera wartete nun auf GPS-Daten vom Smartphone.

### Fehlschlag 1 — MTU zu klein

Der Send-Berlin-Button schrieb ein komplettes NMEA-Frame (GPGGA + GPRMC, ~140 Byte) auf die Data-Characteristic. Ergebnis: **`status=14` (`GATT_INVALID_ATTRIBUTE_LENGTH`)**.

Grund: BLE verhandelt standardmäßig eine MTU von 23 Byte, davon 3 Byte Overhead → **20 Byte effektives Payload**. Ein NMEA-Frame passt da nicht rein.

**Fix:** Nach Service-Discovery `gatt.requestMtu(247)` aufrufen. Canon handelt das auf **512 Byte** hoch. Erst wenn `onMtuChanged` kommt, gilt die GATT-Verbindung als ready.

### Fehlschlag 2 — Die Kamera lehnt NMEA ab

Mit hochgehandelter MTU ging der Write durch den BLE-Stack — aber die Kamera antwortete mit **`status=6` (`GATT_REQUEST_NOT_SUPPORTED`)** und **trennte die Verbindung** (`status=19` = remote terminated).

Das war verwirrend: Laut ursprünglicher Analyse war `00040002` doch die Write-Characteristic für GPS-Daten? Die kurzen Kommandos (`0x06 0x04`) gingen ja auch durch. Nur die langen NMEA-Pakete nicht.

### Der Durchbruch

Zurück ins Dekompilat. Die Stelle, wo die Canon-App NMEA-Daten aufbaut, steckt in `com/canon/eos/T.java`. Relevant ist:

```java
byte[] bytes = c0275o.f4744G.getBytes(StandardCharsets.US_ASCII);
ByteBuffer byteBufferAllocate2 = ByteBuffer.allocate(bytes.length + 1);
byteBufferAllocate2.put(new byte[]{4});   // Prefix
byteBufferAllocate2.put(bytes);            // NMEA-Bytes
```

**Vor die NMEA-Bytes gehört das Kommando-Byte `0x04`**. Das ist — wie alle anderen Kommandos auch — ein normales Canon-GPS-Kommando, nur dass es einen Payload mitführt. Es fehlte komplett in unserer Kommando-Tabelle, weil es nie von Aussenstehenden dokumentiert wurde und wir es beim ersten Mal durch den Dekompilat nicht gesehen hatten.

**Fix:** Beim Write `[0x04] + nmea.toByteArray()` senden statt nur `nmea.toByteArray()`.

### Erfolg

Mit dem Prefix:
- `onCharacteristicWrite status=0`
- Kamera-GPS-Icon wechselt von grau-blinkend auf **hell** = Kamera hat gültigen GPS-Fix erkannt

Zeitlicher Rahmen für Phase 3: ein paar Iterationen Build → Install → auf dem Pixel tippen → Logcat lesen. Mit dem Android-Stack ging jeder Zyklus in Sekunden.

## Stand April 2026

Das Protokoll ist vollständig und reproduzierbar auf echter Hardware validiert. Die Android-App schreibt NMEA-Daten korrekt an die Kamera. Offen ist die Alltagstauglichkeit:

- Stabilität bei kontinuierlichen Updates (Loop-Test steht aus)
- Echte Location statt Berlin-Hardcoded (FusedLocationProvider)
- Foreground Service, damit es ohne offene App weiterläuft
- Auto-Reconnect, wenn die Kamera an/aus geht

Siehe [`PROJEKT_DOKUMENTATION.md`](PROJEKT_DOKUMENTATION.md) für die technische Referenz und den aktuellen Feature-Stand.

## Lessons Learned

- **Dekompilate doppelt lesen.** Das `0x04`-Prefix war die ganze Zeit in `T.java` sichtbar — es wurde nur nicht als Teil der Kommando-Tabelle erkannt. Wenn ein Write fehlschlägt obwohl die Characteristic "schreibbar" ist, lohnt sich der zweite Blick auf den Produzenten der Daten, nicht nur auf den Konsumenten.
- **MTU-Negotiation ist nicht optional** sobald man mehr als kurze Kommandos überträgt. Der Standardwert 23 ist so klein, dass praktisch jede sinnvolle Nutzlast in Fragment-Probleme läuft.
- **Bestehende System-Pairing-Beziehungen sind Gold wert.** Die Pixel-Kamera-Bond war schon durch Canons eigene App eingerichtet. Eine neue App konnte den GATT-Connect direkt aufbauen, ohne sich mit Canons proprietärem Pairing-Protokoll beschäftigen zu müssen.
- **Hardware-Probleme nicht um jeden Preis umgehen wollen.** Der CSR-Adapter blockierte Phase 1 komplett — der Umstieg auf das Pixel war die schnellere Lösung als ein neuer Adapter plus Neutest, und das Zielgerät ist ohnehin das Handy.
