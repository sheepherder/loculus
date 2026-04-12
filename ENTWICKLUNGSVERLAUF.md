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

### Scheinbarer Erfolg

Mit dem Prefix:
- `onCharacteristicWrite status=0`
- Kamera-GPS-Icon wechselte von grau-blinkend auf **hell** = Kamera schien einen gültigen GPS-Fix erkannt zu haben

Das sah aus wie der Durchbruch. War es aber nicht.

## Phase 4 — Die Kamera-Crashes und was wir wirklich falsch gemacht hatten

### Erster Firmware-Hang

Kurz nach dem "Erfolg" hörte die Kamera auf zu reagieren. Das Display zeigte "Busy", kein Knopf funktionierte mehr, Ausschalten unmöglich. Nach **Akku-Pull** bootete sie wieder normal, Canon-App konnte sie wieder sehen, Bond-Credentials waren intakt. Erleichterung.

### Zweiter Firmware-Hang

Nach einer defensiven Refactoring-Runde (GATT-Op-Queue, CCCD-Subscribe auf `00040003`, sauberer Teardown mit `0x03`-Stop vor Disconnect) war das nächste Ergebnis:

- `Enable GPS` → OK
- `Send Berlin` → NMEA-Write hing **30 Sekunden**, dann `status=133` (`GATT_ERROR`)
- Kamera **trennte die Verbindung während des Writes**
- Display "Busy" für mehrere Minuten

Zweiter Akku-Pull. Kamera wieder ok.

### Dritter Firmware-Hang

Minimierung auf die Sequenz die beim ersten Mal "funktioniert" hatte (nur `{6, 0x04}` ohne `{2}`-Request, plus NMEA-Write). Ergebnis: **Kamera-Display wurde schwarz, Self-Reset nach ~30 Sekunden ohne Akku-Pull**. Diesmal hat sie sich selbst erholt.

Drei Crashes in Folge — das Muster war unhaltbar. Wir schickten offensichtlich etwas, das die Firmware in einen unvorgesehenen Zustand brachte.

### Die richtige Erkenntnis: unsere Interpretation war komplett falsch

Ein zweiter, gründlicherer Durchgang durch das Dekompilat deckte auf:

**1.** Das Feld `c0275o.f4744G`, von dessen Bytes wir glaubten es seien NMEA-Daten, ist tatsächlich der **Device-Name-String** (Default `"NoName"`). Die `{4} + f4744G`-Stelle in `T.java` gehört zum Pairing-Protokoll, **nicht zum GPS-Pfad**.

**2.** In `T.java` und `C0298u.java` gibt es nur drei Writes auf den GPS-Data-Channel (`00040002`):
- `{1}/{2}/{3}` (enable/request/stop) aus `C0275o.I()`
- `{5}` (query source) aus `C0298u.a()`
- `{6, src, 0, 0, 0, 0, 0, 0}` — **8 Byte**, nicht 2 — aus `Z3/j.java:181-187`

**Keiner davon schreibt NMEA.** Unsere `{4} + NMEA`-Schreiben hatten also **keine dokumentierte Bedeutung** — die Kamera hat Müll auf einem Command-Channel empfangen, hat es als binären Payload interpretiert, und der Parser hat bei 139 willkürlichen Bytes die GPS-State-Machine geschrottet.

**3.** Warum wir dann kein NMEA-Code im Dekompilat fanden? Weil jadx die entscheidende Methode **übersprungen** hatte:

```
public final boolean G(android.location.Location r19) {
    Method dump skipped, instructions count: 357
    To view this dump add '--comments-level debug' option
}
```

Mit `jadx --show-bad-code` neu dekompiliert, tauchte `d4.C0500A.G(Location)` als echter GPS-Write-Pfad auf.

### Das wirkliche GPS-Datenformat

**Nicht NMEA.** Canon überträgt GPS-Fixes als **20-Byte-Binärframe**:

| Offset | Size | Inhalt |
|--------|------|--------|
| 0 | 1 | `0x04` (Kommando-Byte) |
| 1 | 1 | `'N'`/`'S'` |
| 2 | 4 | `abs(lat)` als float32 big-endian |
| 6 | 1 | `'E'`/`'W'` |
| 7 | 4 | `abs(lon)` als float32 |
| 11 | 1 | `'+'`/`'-'`/`0x00` |
| 12 | 4 | `abs(alt)` als float32 |
| 16 | 4 | Unix-Timestamp (Sekunden) als int32 |

Das Frame passt in die Standard-BLE-MTU (23 Byte) — **MTU-Negotiation ist gar nicht nötig**. Wir hatten ~140 Byte Text geschickt auf einen Kanal der 20 Byte Binary erwartet.

Und entscheidend: Canon schreibt dieses Frame mit **`WRITE_TYPE_NO_RESPONSE`** (op-type 3 in Canons eigener Queue, das mappt zu `setWriteType(1)` in C0275o.java). Wir hatten mit `WRITE_TYPE_DEFAULT` geschrieben und auf Response gewartet — die nie kam weil Canon-Firmware für `0x04`-Writes keine sendet.

### Der echte Durchbruch

Nach dem Umbau auf das Binärformat + WRITE_NO_RESPONSE + korrekter Initialsequenz (READ von STATUS und NOTIFY nach Connect, dann CCCD-Subscribe auf NOTIFY):

- Erster Send: GPS-Icon grau → hell
- Zweiter und dritter Send: stabil, keine Crashes
- Disconnect: sauber (`status=0`)
- Reconnect + Send: alles weiter sauber
- Kamera bleibt durchgängig responsive

Das war der tatsächliche Durchbruch. Die drei Firmware-Crashes davor waren **keine Firmware-Bugs auf Canon-Seite** — sie waren Konsequenz davon, dass wir undefinierte Byte-Sequenzen auf einen binären Command-Channel gefeuert haben, der dazu nicht gedacht war.

## Stand April 2026

Protokoll vollständig und reproduzierbar validiert auf echter Hardware. Die Android-App schreibt GPS-Frames korrekt an die Kamera ohne Firmware-Probleme.

**Beobachtungen aus der Live-Testsession:**

- Der `{2}`-Request-Path wurde nie exerziert: die Kamera war bereits in `READY_TO_RECEIVE` von einer früheren Canon-App-Session. Die `{2}`-Logik ist defensiv im Code für den Fall einer frisch zurückgesetzten Kamera, aber ungetestet.
- Nach dem Disconnect wird das GPS-Icon **grau-statisch**, nicht blinkend. Das ist auch beim Canon-App-Disconnect so — also normales R6m2-Verhalten, kein Fehler.
- Wenn die offizielle Canon-App connected, wechselt ein **Bluetooth-Symbol** auf dem Kamera-Display von grau auf hell. Bei uns bleibt das Symbol grau. Vermutlich macht Canon einen Handshake über den Pairing-Service (`0001`) — liest `00010006` (Device Name) und schreibt `{1} + app-name` zu `00010005`. Kosmetischer Status-Indikator, nicht Gate-keeping für GPS.

**Offen:**

- FusedLocationProvider statt Berlin-Hardcoded
- Auto-Loop (alle ~5 s ein Frame)
- Foreground Service für Hintergrundbetrieb
- Auto-Reconnect wenn Kamera aus/an geht
- Optional: Canon-App-Handshake nachmachen, damit das BT-Icon leuchtet

Siehe [`PROJEKT_DOKUMENTATION.md`](PROJEKT_DOKUMENTATION.md) für die technische Referenz.

## Lessons Learned

- **Dekompilaten nicht blind vertrauen.** Der erste Durchgang durch das Dekompilat hat uns falsche Commands (`{4} + NMEA`) plausibel gemacht, weil der wichtigste Methoden-Body von jadx übersprungen worden war — und niemand hat das "Method dump skipped" als Warnung ernst genommen. `jadx --show-bad-code` sollte **Default** sein bei Reverse Engineering, nicht Fallback.
- **Feldnamen im Dekompilat sind nicht semantisch.** Wir hielten `f4744G` für den NMEA-Puffer, weil es im Kontext des GPS-Files stand. Tatsächlich war es der App-Name aus einem ganz anderen Flow. Bei obfuskierten Feldnamen hilft nur, *alle* Schreibpositionen des Felds zu checken, nicht nur eine.
- **Ein Command-Write, der "irgendwie akzeptiert" wird, heißt nicht, dass er das Richtige tut.** Die Kamera nahm unser `{4} + NMEA` an und zeigte sogar GPS als fixed — und trotzdem war es Müll, der die Firmware in undefinierte States brachte. "Es funktioniert" ist kein Beweis für Korrektheit bei Reverse Engineering.
- **WRITE_NO_RESPONSE ist ein eigenes Protokoll-Werkzeug, nicht nur eine Performance-Option.** Für High-Frequency-Payloads (Streaming-Daten, Sensor-Feeds) erwarten Geräte oft genau diesen Write-Typ. Default-Writes mit Response können je nach Firmware ignoriert oder als Fehler interpretiert werden.
- **MTU-Negotiation ist nicht immer nötig.** Wir hatten sie implementiert, weil NMEA nicht in 23 Byte passte. Das echte Protokoll hätte sie gar nicht gebraucht — 20 Byte passen rein. Wir hätten uns den ganzen MTU-Handshake sparen können.
- **Bestehende System-Pairing-Beziehungen sind Gold wert.** Die Pixel-Kamera-Bond war schon durch Canons eigene App eingerichtet. Eine neue App konnte den GATT-Connect direkt aufbauen, ohne sich mit Canons proprietärem Pairing-Protokoll beschäftigen zu müssen.
- **Firmware-Crashes ernst nehmen.** Die R6m2 hat unsere Fehler dreimal mit Hangs und Self-Reset bestraft. Nach dem zweiten Crash war die korrekte Reaktion nicht "anderes Timing probieren" sondern **komplett stoppen und die Grundannahmen prüfen**. Das haben wir erst beim dritten Crash gemacht — hätten wir nach dem ersten.
- **Hardware-Probleme nicht um jeden Preis umgehen wollen.** Der CSR-Adapter blockierte Phase 1 komplett — der Umstieg auf das Pixel war die schnellere Lösung als ein neuer Adapter plus Neutest, und das Zielgerät ist ohnehin das Handy.
