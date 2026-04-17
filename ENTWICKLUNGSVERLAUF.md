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

## Phase 5 — Noch zwei versteckte Fallstricke und der echte Abschluss

Die Freude über das funktionierende Single-Frame-Schreiben war verfrüht. Als wir den Foreground Service + FusedLocationProvider gebaut und kontinuierliches Tracking aktiviert haben (alle 5s ein Frame), passierte bei der dritten Session wieder das bekannte Muster:

- Erste Frames gingen durch (status=0)
- Kamera-GPS-Icon ging **aus statt hell** (anders als im manuellen Einzeltest)
- Nach einigen Frames wurde die Kamera unresponsive
- Nach Stop: Firmware-Hang

Diesmal hatten wir mehr Beobachtungen. Der Unterschied zu den funktionierenden Einzel-Tests: beim Service senden wir **echte Handy-Koordinaten** aus der Umgebung des Users statt der hardcodierten Berlin-Daten. Der Byte-Stream ist also anders.

### BLE Sniffing: HCI-Snoop

Statt weiter zu raten, haben wir **den echten Canon-App-Traffic aufgezeichnet** — Android hat einen eingebauten HCI-Snoop-Logger, aktivierbar in den Entwickleroptionen:

1. Settings → System → Developer options → "Enable Bluetooth HCI snoop log" = Enabled (Filtered)
2. Bluetooth aus/an
3. Canon Camera Connect öffnen, Session durchführen
4. `adb bugreport` zieht unter anderem `FS/data/misc/bluetooth/logs/btsnoop_hci.log`
5. Mit Wireshark/tshark analysieren

Im Log fand sich die echte Canon-Sequenz direkt nach Service-Discovery:

```
Write 0x0048 = 02 00                                   ← CCCD = 0x0200 (INDICATION!)
Write 0x0045 = 05 00 00 00 00 00 00 00                 ← 8-Byte Source-Query
Indication 0x0047 = 05 04                              ← "source = SMARTPHONE"
Write 0x0045 = 04 4e 6e 27 47 42 45 66 14 1f 41 2b …   ← erstes GPS-Frame
```

Drei unerwartete Details:

**1. Byte-Order ist LITTLE-ENDIAN, nicht Big-Endian.** Das Dekompilat zeigte ganz offensichtlich `ByteBuffer.allocate(4).putFloat(x)` ohne explizite Order — Java-Default ist Big-Endian. Aber die Bytes auf dem Wire sprechen eine andere Sprache: z.B. `6e 27 47 42` für Breitengrad ~49.79°N macht nur Sinn als `0x4247276E` = LE-float32. Canon muss die Order irgendwo via einen Pfad setzen, den jadx elided hat. Wir hatten bis dahin alles in Big-Endian enkodiert — die Kamera las riesige Unsinns-Gleitkommazahlen (`~1e+28°`), markierte den Fix kurz als "received" (daher GPS kurz hell), verwarf ihn sofort wieder (GPS geht aus), und bei Dauerbeschuss mit Müll kollabierte die State-Machine schließlich.

**2. CCCD-Write ist `0x0200` (Indication) nicht `0x0100` (Notification).** Das `00040003` NOTIFY-Characteristic auf der R6m2 unterstützt nur Indications. Mit `0x0100` bekamen wir nie Notifications zurück — wir wussten nicht mal, dass unsere Reads zwar initialen State geben, aber spätere Änderungen nie propagiert werden. Canon nutzt konsistent `0x0200`.

**3. Source-Query ist 8 Byte.** Nicht `{0x05}` wie wir dachten (und wie der erste Analyse-Agent behauptet hatte), sondern `{0x05, 0, 0, 0, 0, 0, 0, 0}` — Canon allociert `ByteBuffer.allocate(8)` und schreibt nur das erste Byte, lässt den Rest auf Null. Auf dem Wire gehen 8 Byte raus.

### Der tatsächliche Abschluss

Mit allen drei Fixes in der App:

- Continuous-Tracking-Session mit echten FusedLocation-Updates
- Erste echte **Indikation empfangen**: `NOTIFY 0003 0504` — Kamera bestätigt "source = SMARTPHONE"
- 8 GPS-Frames über eine Minute hinweg, jeweils alle 10-15 Sekunden
- GPS-Icon **bleibt** hell
- Kamera durchgehend responsive
- Sauberer Stop mit `{3}` + Disconnect
- Kein Firmware-Hang

Und der Test, der zählt: **Foto auf der Kamera aufgenommen, EXIF-Daten im Bild kontrolliert — Geo-Tag-Format identisch zum Output der offiziellen Canon-App.**

Das war der echte Abschluss. Die MVP-Kette aus Protokoll-Reverse-Engineering, falschen Annahmen, mehreren Firmware-Hangs, und schließlich dem HCI-Sniff ist damit durchlaufen.

## Phase 6 — Haupt-Screen, Kickoff, Scan-First (April 2026)

Die MVP-Runde aus Phase 5 war protokollmäßig abgeschlossen — GPS fließt, EXIF stimmt. Ziel dieser Phase: die App alltagstauglich machen. Infos auf dem Hauptbildschirm (Modell, Firmware, Seriennummer), Auto-Reconnect, optional Fernauslöser. Dabei sind wir über mehrere hartnäckige Annahmen gestolpert und haben am Ende die gesamte Architektur auf **Scan-First** umgestellt.

### DIS-Reads scheitern an Canons Pairing

Erster Schritt: die App soll beim Connect Modell und Firmware aus dem Standard-Device-Information-Service (`0x180a`) lesen — Canon-App macht das laut Dekompilat auch. In der Praxis kam beim ersten Read prompt `status=133` (`GATT_ERROR`), direkt danach `onConnectionStateChange status=61` (HCI `0x3D` = „Connection Failed to be Established / MAC Connection Failed"). Der Link wurde vom Android-BT-Stack abgerissen.

Ursache: die DIS-Characteristics auf der R6m2 sind **verschlüsselungspflichtig**. Canons eigenes proprietäres Pairing (über `00010000`-Service) installiert aber **keine SMP-Keys** im Android-Bond-Store. Unser System-Bond existiert als Record, aber ohne Encryption-Keys. Ein Read auf einen verschlüsselten Char triggert Android's Encryption-Escalation, die scheitert, Link stirbt.

Die Canon-App sieht dagegen „Canon Inc.", „330b", „1.0.0" etc. beim Lesen derselben Chars — vermutlich hat sie einen Auth-Weg den wir nicht nachvollziehen konnten. Mangels Alternative: **DIS-Reads bleiben draußen**. Modell aus BT-Namen ableiten wurde ebenfalls verworfen (User-Instruktion: nicht aus User-editierbaren Feldern raten).

### Echter Kickoff: `0a` + `01`

Beim Versuch, das Canon-BT-Icon auf der Kamera zum Leuchten zu bringen, fiel uns auf dass wir beim Connect einen anderen State bekamen als Canon. Erneuter HCI-Snoop der Canon-App zeigte eine Sequenz die wir komplett übersehen hatten:

1. CCCD-Subscribes auf **acht** Channels (nicht drei wie bei uns): GPS NOTIFY, HANDOVER DATA (`00020002`, sowohl Write als auch Notify-Property), HANDOVER NOTIFY (`00020003`, Indicate), plus fünf Remote-Service-Notify-Chars (`00030001/2/11/21/31`)
2. Write `0a` auf `00020002` (HANDOVER DATA) — Kamera antwortet mit einer Notification
3. Write `{5, 0, 0, 0, 0, 0, 0, 0}` auf `00040002` (Source-Query — das kannten wir schon)
4. Indication `05 04` auf `00040003` — Source = SMARTPHONE
5. **Write `01` auf `0001000a` (PAIRING DATA)** — dies triggert die eigentliche Ready-Indication
6. Indication `02 00` auf `00040003` — Kamera ist jetzt wirklich ready

Wir hatten vorher ausschließlich die Source-Query gemacht und uns über einen STATUS-Bit-1-Shortcut in den GPS-Session-State geschoben. Das war fragil: `STATUS byte0 & 2` bedeutet nicht „Kamera ist jetzt ready", sondern „Kamera-GPS-Source war zuletzt auf Smartphone konfiguriert", und **das Bit bleibt auch im Standby gesetzt**. Wir hatten also Frames an schlafende Kameras gesendet — die zwar akzeptiert wurden, aber die halb-aufgewachte GPS-State-Machine in ihre Sensorreinigungs-Startroutine schickte (das „Klackern" beim Einschalten). Mit der echten Kickoff-Sequenz + Ready-Indication gehört das der Vergangenheit an.

**Nebeneffekt**: nach dem `01`-Pairing-Write leuchtet auf der Kamera **auch das BT-Symbol** blau. Das war in Phase 5 noch ein kosmetisches offenes Ende.

### Die Ready-Indication bleibt manchmal aus

Nach dem Umbau auf den vollen Kickoff trat ein Folgeproblem auf: wenn die Kamera schon in einer vorigen Session `02 00` gesendet hat und wir reconnecten bevor sie in den Schlaf geht, liefert Canons Firmware **keine zweite Ready-Indication** (State hat sich aus ihrer Sicht nicht geändert). Unser Code wartete ewig, Session-State blieb auf `REQUESTING_GPS`, keine GPS-Frames wurden gesendet.

Fix: beim ersten Read von `00040003` speichern wir das erste Byte. Ist es bereits `0x02` und nach dem Kickoff kommt keine frische Indication, nehmen wir das als „Kamera war bereits ready" und springen auf `GPS_SESSION_ACTIVE`. Wenn das initiale Byte etwas anderes ist (z.B. `0x05` = source-known-aber-nicht-ready), warten wir auf die echte Indication.

### Wie erkennt man wach vs. schlafend — ohne zu wecken?

Nächste Hürde: selbst mit korrektem Kickoff wachte die Kamera jedes Mal auf wenn wir uns zu einer schlafenden Kamera verbanden. Canons App macht das nachweislich nicht — sie connected sich zu schlafenden Kameras völlig geräuschlos. Wir brauchten also **Wake-Detection ohne Schreibzugriff**.

Sackgassen:
- **STATUS / NOTIFY initial reads** — Werte sind identisch zwischen wach und schlafend, oder zeigen cached Sessions-State. Kein Unterschied.
- **Extra CCCD-Subscribes auf Remote-Service-Chars** — sollten uns „Kamera wach jetzt"-Notifications liefern, taten es aber nicht. Null unsolicited Indications in allen Tests.
- **Connect-Latenz-Timing** — schlafende Kamera braucht 3–6s, wache < 200ms. Aber BLE-Advertising-Zyklen bringen Varianz rein, nicht robust genug (User hat das zurecht abgelehnt).
- **Silent-Mode (Connect + Subscribe, keine Writes)** — Camera kappte uns nach ~10s trotzdem mit `status=19`, und selbst das bloße Subscribe auf GPS_NOTIFY löste schon eine Sensor-Aufwach-Reinigung aus.
- **Noch silenter: nur Connect + discoverServices, null Writes** — Kamera droppte nach 2,7s mit `status=61`. Geräusche kamen trotzdem. Selbst der bare Connect weckt die R6m2 offenbar an.

### Der Withings-Irrtum

Im ersten Full-HCI-Snoop mit Canon-App + schlafender Kamera fanden wir einen mysteriösen „Standby-Service" mit UUID128 `0000002057495448005d000000000000` und Writes auf Handle 0x0010/0x0011. Nach einigem Starren auf UUIDs und Byte-Mustern fiel der Groschen: `57495448` ist ASCII `"WITH"` — das war die **Withings ScanWatch** des Users, die im Hintergrund mitgelaufen ist. Alle drei beobachteten `LE Create Connection`-Events gingen zu einer Random Private Address, nicht zur Canon-MAC. Canon hatte die Kamera im gesamten Log **null mal connected** — nur gescannt.

Die eigentliche Erkenntnis aus diesem Schnitzer: Canon's „BT-Icon blau"-UI-State bedeutet nur **„Kamera advertiset in Reichweite"**, nicht „GATT-Verbindung steht". Canon scannt passiv und connected **nicht** bei schlafender Kamera. Das passt zum User-Bericht: Canon erkennt auch nach Battery-Pull wieder-Einstecken korrekt ohne Weckgeräusch.

### Das Signal steckt im Advertisement

Mit dieser Erkenntnis zurück zum HCI-Log: die Kamera advertiset, wir müssen nur passiv zuhören. Im alten Snoop fanden wir bei Canons aktiver Testsession zwei distinkte Manufacturer-Data-Payloads:

```
01 0b 33 f3 4b 02   ← 1x, genau beim Kamera-Einschalten, bevor Canon connected
01 0b 33 f3 4b 05   ← 55x, während aller Schlafphasen
```

**Das letzte Byte** encodiert den Power-State: `0x02` = wach, `0x05` = schlafend. Die ersten fünf Bytes (`01 0b 33 f3 4b`) sind konstant, vermutlich Modell/Revision. User hat mit einem separaten BLE-Scanner-Tool verifiziert dass awake-Ads alle ~200ms kommen, asleep-Ads alle ~3s — zweite unabhängige Signatur.

Manufacturer-ID ist `0x01A9` = **Murata Manufacturing** (nicht Canon Inc. = `0x0B01`, was ich erst vermutet hatte — Canon nutzt halt Murata-Module). Byte-Index ist **5** (letztes von 6 Custom-Bytes nach der Company-ID — ich hatte erst irrtümlich 3 gerechnet weil ich die Wireshark-Ausgabe falsch geparst hatte: das erste Test-Screen zeigte die UI dauerhaft auf `asleep`, weil wir Byte 3 (`0xf3`, konstant) gelesen haben, der nicht matchte → Fallback auf ASLEEP).

### Scan-First-Architektur

Mit dem sauberen Power-State-Signal ist die Logik komplett umgebaut:

- `GpsTrackingService` startet beim Start einen `BluetoothLeScanner` (low-power, filtert per MAC in-code, nicht per ScanFilter — der ist picky bei Address-Types)
- Jedes Advertisement wird geparst; nur wenn letztes Byte = `0x02` (wach) wird `startGattSession()` aufgerufen
- Scanner stoppt sobald GATT aufgebaut ist (vermeidet Overhead)
- Auf Disconnect (egal ob Kamera schlafend, Battery-Pull, Out-of-Range) → Scanner wieder an, warten auf nächstes Awake-Ad
- Ein Watchdog flippt `cameraPower` nach 8s ohne Advertisement auf `UNSEEN` (Battery-Pull / Out-of-Range Visualisierung)
- Beim Disconnect resettet man `lastAdvertAt` auf jetzt, damit der Watchdog nach GATT-Sessions nicht sofort fälschlich UNSEEN zeigt

Live-Test-Matrix erfolgreich:
- Kamera an + in Reichweite → automatisch Connect + Kickoff + GPS
- Kamera aus → Scanner bleibt still, keine Geräusche, kein Connect-Versuch
- Kamera ein → innerhalb 200ms Auto-Reconnect + Kickoff
- Battery-Pull während Session → BLE-Supervision-Timeout (`status=19` oder `status=8`) → Scanner resumed → nach 8s Funkstille → `UNSEEN`
- Out-of-Range → dasselbe Muster, zurück in Reichweite → Auto-Reconnect
- Kamera aus (normal) → sauberer `awake → asleep` Übergang ohne UNSEEN-Blitz

### Android-BT-Stack wird launisch nach Churn

Ein beobachtetes Artefakt: nach ~20 Connect/Disconnect-Zyklen in kurzer Folge fing Androids BT-Stack an, Canon-Advertisements **gar nicht mehr** an unsere App weiterzureichen (andere BLE-Geräte kamen weiter durch). Auch danach: nach BT-Toggle Connections die nach 5s mit `status=8` wieder abbrachen. Nach ein paar Minuten Ruhe erholte sich der Stack von selbst. Das ist ein Android-Stack-Bug, nicht unserer — BT-Toggle oder kurzes Warten hilft, nichts was wir per Code verhindern können.

### Stand April 2026 (Ende Phase 6)

App macht in Alltag-Szenarien das was sie soll:
- Foreground Service läuft im Hintergrund, scannt passiv
- Keine Kamera-Weck-Geräusche mehr
- Auto-Reconnect auf Kamera-Einschalten + Rückkehr-in-Reichweite funktioniert
- Power-State (`awake` / `asleep` / `unseen`) in der UI sichtbar
- RSSI-Polling während Session
- Session-Uptime + Fix-Count + Fix-Rate live
- GPS-Fixes landen korrekt in EXIF, BT-Icon auf Kamera leuchtet

**Bewusst offen gelassen:**

- Fernauslöser (`00030001` Write) — eigene Crash-Oberfläche, reizvoll aber separates Feature
- Shutter-Counter / Batterie-Anzeige — nur via PTP-IP über WiFi zugänglich, BLE kann das nicht liefern
- `{2}`-Request-Path bei frisch zurückgesetzter Kamera — defensiv im Code drin, weiterhin ungetestet da unsere Bond-Beziehung nie vollständig reset wurde
- DIS-Reads reaktivieren — bräuchte einen Weg die SMP-Keys nachzuinstallieren, nicht-trivial

Siehe [`PROJEKT_DOKUMENTATION.md`](PROJEKT_DOKUMENTATION.md) für die aktualisierte technische Referenz.

## Nachbetrachtung — was wir rückblickend hätten wissen können

Nach Abschluss von Phase 6 haben wir die Literaturrecherche gemacht die wir vor Projektstart hätten machen sollen. Ergebnisse, geordnet nach Überlappung mit unserem Projekt:

### Prior art im Community-Umfeld

**[`gkoh/furble` Issue #189 — Support Canon EOS GPS](https://github.com/gkoh/furble/issues/189)** (eröffnet Mai 2025, gemerged November 2025 via PR #199): das einzige öffentliche Repo das Canon-GPS-über-BLE tatsächlich dokumentiert. `@Jerroder` (EOS R6) und `@gkoh` haben via HCI-Snoops herausgefunden:

- **20-Byte-Binärframe exakt identisch zu unserem**: `0x04 + N/S + lat-float32-LE + E/W + lon + alt-sign + alt + uint32-epoch`
- **INDICATE-CCCDs statt NOTIFY** auf GPS- und Pairing-Service
- **Write `0x01` auf `0001000a`** (sie nennen's `CHR_IDEN_UUID`) als „Paired!"-Confirmation — das ist *exakt* unser Pairing-Kickoff-Write
- Die R6m2-spezifische **Constraint**: entweder Focus+Shutter ODER Shutter+GPS, nie beides gleichzeitig (durch Remote- vs Smart-Pairing-Mode bestimmt)

Das heißt: Phase 5 hätten wir (mit dieser Quelle im Rücken) in einem Bruchteil der Zeit abschließen können. Der 20-Byte-Frame plus LE-Encoding plus INDICATE-CCCD war 2025-06-21 von Jerroder/gkoh gecrackt, sechs Monate bevor wir in Phase 1 begonnen haben.

### Was furble nicht hat und was wir neu beitragen

- **Advertisement-Byte-5-Power-Detection**: furble matcht Canons nur auf Service-UUID. Sie dekodieren für **Sony**-Kameras im selben Repo einen analogen Mode-Byte (`ADV_MODE22_PAIRING_SUPPORTED | ADV_MODE22_PAIRING_ENABLED | ADV_MODE22_REMOTE_ENABLED`), haben das für Canon aber nie gemacht.
- **Handover-Service `00020000`**: furble kennt den Service nicht, sie pairen „just works" direkt über `00010000`. Unser `{0x0a}`-Write auf `00020002` ist in keiner öffentlichen Quelle dokumentiert. Wir brauchen den Write aber vermutlich nur weil wir eine bestehende Canon-App-Bond erben statt frisch zu pairen.
- **R6m2-Testing**: furbles Thread hatte genau einen R6m2-Besitzer (`@hijae`), der sich nach erstem Feedback nie wieder gemeldet hat. Nur R6 wurde offiziell validiert. Unsere App ist die erste verifizierte R6m2-BLE-GPS-Implementierung außerhalb Canons eigener App.
- **Scan-First + Auto-Reconnect-Architektur**: Jedes andere Canon-Remote-Tool (inkl. furble) connectet beim User-Trigger. Wir scannen passiv und connecten nur bei echten Wake-Events.

### Andere Repos zum Canon-BLE-Protokoll (alle rein Remote/Shutter, kein GPS)

Alle matchen nur auf Canon-Service-UUID, nutzen `haveServiceUUID()`-Filter, kein Advertisement-Parsing:

- [`pklaus/canoremote`](https://github.com/pklaus/canoremote) — Python, MAC-per-CLI, kein Scan
- [`ids1024/cannon-bluetooth-remote`](https://github.com/ids1024/cannon-bluetooth-remote) — Python via `btgatt-client`
- [`maxmacstn/ESP32-Canon-BLE-Remote`](https://github.com/maxmacstn/ESP32-Canon-BLE-Remote) — ESP32, service-UUID-match
- [`RReverser/eos-remote-web`](https://github.com/RReverser/eos-remote-web) — Web Bluetooth API
- [`iebyt/cbremote`](https://github.com/iebyt/cbremote) — Android, auch nur Service-Match

### Ian Douglas Scott (2017/2018)

[Die zwei Blog-Posts](https://iandouglasscott.com/2018/07/04/canon-dslr-bluetooth-remote-protocol/) dokumentieren das GATT-Pairing-Protokoll auf T7i (ältere Canon). Grundlage für `canoremote`-etc. Nichts zu GPS, nichts zu Advertisements, nichts zu R6m2-Quirks.

### Fazit

Die „eigentliche GPS-Crack-Leistung" war 2025 bei furble. Unser Eigenanteil liegt in:

1. **Advertisement-Power-Byte als Wake-Signal** (genuine novel für Canon)
2. **Handover-Service-Kickoff** (unklar ob nötig in furble's fresh-pair-Flow, bei uns essentiell)
3. **Scan-First-Architektur** auf Android mit Auto-Reconnect
4. **R6m2-Verifizierung** und Dokumentation der modellspezifischen Constraints
5. **Android-BT-Stack-Quirks-Katalog** (Churn-Limits, ScanFilter-Picky, etc.)

Wäre ein dankbarer PR an `gkoh/furble` um dort die Canon-Scan-Robustheit mit dem Power-Byte zu ergänzen — die Code-Struktur ist vorhanden (ihre Sony-Implementation zeigt den Weg).

## Lessons Learned

- **Wake-Detection gehört in die Advertisement-Payload, nicht in die Verbindung.** Die R6m2 broadcastet ihren Power-State (letztes Byte der Canon-Manufacturer-Data unter Company-ID `0x01A9`) bei jeder Advertising-Pulse. Passiv mit­lesen reicht, kein GATT, kein Wake. Canon macht das genauso — nicht verbinden, sondern scannen.
- **Connection-Timing ist kein Signal.** BLE-Advertising-Zyklen bringen so viel Varianz rein dass „schneller Connect = wach" keine robuste Aussage ist. Korrelation mit Power-State-Byte war 1:1, Timing war es nie.
- **Wireshark-Ausgabe ist nicht „die Bytes".** Manufacturer-Specific-Records werden halb-dekodiert dargestellt — ich habe zuerst `01 0b` als Company-ID LE gelesen (hab's sogar als „Canon Inc." zuordnen wollen und landete fälschlich bei `0x0B01`), obwohl die echte Company-ID `0x01A9` vorher abgetrennt wurde und die 6 Bytes die Wireshark als „Data" zeigt bereits der Rest **nach** der Company-ID sind. Im Android-API kommt's genauso an (`getManufacturerSpecificData(companyId)` gibt die Rest-Bytes zurück). Dreimal die Bytes mit Wireshark-Packet-Tree statt mit der Flat-Ausgabe verifizieren.
- **MAC-OID und BLE-Company-ID sind zwei verschiedene Registries.** Canons R6m2 hat eine MAC `34:90:EA:...` deren OID laut IEEE an **Murata Manufacturing** vergeben ist (der Hersteller des BLE-Moduls), während die Advertisement-Company-ID `0x01A9` laut Bluetooth SIG an **Canon Inc.** vergeben ist. Es ist kein Widerspruch — die Hardware kommt von Murata, das Protokoll ist Canons. Scanner-Tools zeigen beides gleichzeitig an; leicht verwechselbar.
- **Recherche vor Implementation.** `gkoh/furble` Issue #189 hatte das 20-Byte-Frame-Format, die INDICATE-CCCDs und den `01`-Pairing-Write-Trigger schon sechs Monate vor Projektstart entschlüsselt. Ein GitHub-Issue-Search oder grep nach Canon+GPS+BLE am Anfang hätte Phase 5 halbiert. Gelernt: **vor dem Dekompilieren die öffentlichen Issue-Threads und PRs durchsuchen**.
- **Prüfe ob dein BLE-Sniff wirklich von dem Gerät ist, das du glaubst.** Ich habe eine Withings-ScanWatch-Konversation als Canon-Standby-Service fehlgedeutet. Zu schnell auf UUID-ähnelndes Pattern gesprungen, nicht auf MAC-Filter bestanden. Beim nächsten Mal: Filter auf Peer-MAC **vor** der Analyse, nicht nach.
- **Initial-Read-Werte sind nicht „jetzt", sondern „zuletzt".** `ByteBuffer`-Werte der GATT-Characteristics sind die letzten Werte die die Kamera gesendet hat — oft aus einer früheren Session. Für Live-State musst du auf `onCharacteristicChanged` warten (Notification/Indication), nicht auf `onCharacteristicRead`. Ausnahme: wenn die Kamera ihren State seit dem letzten Mal nicht gewechselt hat, sendet sie keine frische Indication → dann ist der Read-Wert das Beste was du hast. Bedeutet: wir brauchen **beide** Pfade, mit Vorrang auf die Indication.
- **Android-BT-Stack hat ein Churn-Limit.** Nach einem Dutzend schneller Connect/Disconnect-Zyklen hört Android auf, Advertisements für eine MAC weiterzureichen. BT-Toggle oder Abwarten hilft, aber verhindern kann man es im App-Code nicht.
- **Nicht aus User-editierbaren Feldern raten.** Der BT-Name auf der Kamera ist frei einstellbar. Modell darauf zu basen („EOSR6m2_xxx" → „Canon EOS R6 Mark II") wirkt elegant, wird aber falsch sobald jemand den Namen ändert. Lieber Feld weglassen als falsche Info zeigen.
- **Dekompilaten nicht blind vertrauen.** Der erste Durchgang durch das Dekompilat hat uns falsche Commands (`{4} + NMEA`) plausibel gemacht, weil der wichtigste Methoden-Body von jadx übersprungen worden war — und niemand hat das "Method dump skipped" als Warnung ernst genommen. `jadx --show-bad-code` sollte **Default** sein bei Reverse Engineering, nicht Fallback.
- **Sniff den echten Traffic so früh wie möglich.** Der Android HCI-Snoop-Log hätte uns Phasen 3-5 komplett erspart. Sobald ein proprietäres BLE-Protokoll relevant ist, ist ein Wireshark-Capture der realen Gegen-App Pflicht-Grundlagenarbeit — nicht Last Resort.
- **Dekompilate und Wire-Format können auseinanderlaufen.** Canon setzt `ByteOrder.LITTLE_ENDIAN` über einen Code-Pfad, den jadx komplett elided hat. Im Dekompilat sah `ByteBuffer.putFloat` aus wie Big-Endian (Java-Default). Auf dem Wire ist es LE. Ohne Packet-Capture wäre das nie aufgeflogen.
- **Feldnamen im Dekompilat sind nicht semantisch.** Wir hielten `f4744G` für den NMEA-Puffer, weil es im Kontext des GPS-Files stand. Tatsächlich war es der App-Name aus einem ganz anderen Flow. Bei obfuskierten Feldnamen hilft nur, *alle* Schreibpositionen des Felds zu checken, nicht nur eine.
- **Indications vs. Notifications**: ein Detail mit großer Wirkung. CCCD `0x0100` (Notify) und `0x0200` (Indicate) sehen in Android-Code fast identisch aus (`setCharacteristicNotification(ch, true)` funktioniert für beide), aber viele Peripherals unterstützen nur genau eins davon. Bei fehlenden State-Callbacks: prüfen, welchen Modus die Peripherie erwartet.
- **Ein Command-Write, der "irgendwie akzeptiert" wird, heißt nicht, dass er das Richtige tut.** Die Kamera nahm unser `{4} + NMEA` an und zeigte sogar GPS als fixed — und trotzdem war es Müll, der die Firmware in undefinierte States brachte. "Es funktioniert" ist kein Beweis für Korrektheit bei Reverse Engineering.
- **WRITE_NO_RESPONSE ist ein eigenes Protokoll-Werkzeug, nicht nur eine Performance-Option.** Für High-Frequency-Payloads (Streaming-Daten, Sensor-Feeds) erwarten Geräte oft genau diesen Write-Typ. Default-Writes mit Response können je nach Firmware ignoriert oder als Fehler interpretiert werden.
- **MTU-Negotiation ist nicht immer nötig.** Wir hatten sie implementiert, weil NMEA nicht in 23 Byte passte. Das echte Protokoll hätte sie gar nicht gebraucht — 20 Byte passen rein. Wir hätten uns den ganzen MTU-Handshake sparen können.
- **Bestehende System-Pairing-Beziehungen sind Gold wert.** Die Pixel-Kamera-Bond war schon durch Canons eigene App eingerichtet. Eine neue App konnte den GATT-Connect direkt aufbauen, ohne sich mit Canons proprietärem Pairing-Protokoll beschäftigen zu müssen.
- **Firmware-Crashes ernst nehmen.** Die R6m2 hat unsere Fehler mehrfach mit Hangs und Self-Reset bestraft. Nach dem zweiten Crash war die korrekte Reaktion nicht "anderes Timing probieren" sondern **komplett stoppen und die Grundannahmen prüfen**. Das haben wir erst beim fünften Crash konsequent gemacht — hätten wir nach dem ersten.
- **Hardware-Probleme nicht um jeden Preis umgehen wollen.** Der CSR-Adapter blockierte Phase 1 komplett — der Umstieg auf das Pixel war die schnellere Lösung als ein neuer Adapter plus Neutest, und das Zielgerät ist ohnehin das Handy.
