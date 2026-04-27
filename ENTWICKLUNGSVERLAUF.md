# Entwicklungsverlauf — Canon EOS GPS Reverse Engineering

Dieses Dokument erzählt die Geschichte des Projekts chronologisch: was wurde probiert, was hat funktioniert, was nicht, und welche Erkenntnisse sind unterwegs entstanden. Für die rein technische Protokoll-Referenz siehe [`PROTOCOL.md`](PROTOCOL.md).

## Motivation

Die Canon EOS R6 Mark II kann GPS-Daten in EXIF schreiben — aber nur, wenn die offizielle **Canon Camera Connect App** auf einem Smartphone aktiv läuft und über Bluetooth LE mit der Kamera verbunden ist. Geht das Handy in den Hintergrund oder wird die App beendet, hört das Geotagging auf.

Ziel dieses Projekts: eine Lösung bauen, bei der die Kamera **automatisch** GPS-Daten bekommt, sobald sie eingeschaltet ist — ohne dass man die Canon-App manuell starten muss.

## Phase 1 — Reverse Engineering (Januar 2026)

### Ausgangslage

- Kamera: Canon EOS R6 Mark II
- Ziel-Handy: Pixel 9a, war bereits mit der Kamera über die Canon-App gepairt
- Entwicklungsrechner: Arch Linux mit CSR USB-Bluetooth-Adapter (`0a12:0001`)

### Vorhandene Ressourcen

Andere haben teile dieses Protokolls schon angefasst — allerdings nur für Remote-Auslösung, **nicht für GPS**:

- [Ian Douglas Scott — Canon DSLR Bluetooth Remote Protocol](https://iandouglasscott.com/2018/07/04/canon-dslr-bluetooth-remote-protocol/) — erste öffentliche Analyse des Canon-Pairing-Protokolls
- [pklaus/canoremote](https://github.com/pklaus/canoremote), [ids1024/cannon-bluetooth-remote](https://github.com/ids1024/cannon-bluetooth-remote), [maxmacstn/ESP32-Canon-BLE-Remote](https://github.com/maxmacstn/ESP32-Canon-BLE-Remote)

**Keines davon implementiert GPS-Übertragung** — das war offenbar noch niemand öffentlich angegangen.

### Dekompilat der Canon Camera Connect App

APKs von der Kamera-App wurden mit [**jadx**](https://github.com/skylot/jadx) dekompiliert und analysiert. Aus der Analyse ergaben sich:

- Canon-UUID-Basis `d8492fffa821` — alle proprietären Services/Characteristics folgen dem Muster `0000XXXX-0000-1000-0000-d8492fffa821`
- GATT Service `0004` = GPS Service
  - `00040001` Read/Notify = Status
  - `00040002` Write = Daten- und Kommando-Kanal
  - `00040003` Notify = Notifications
- Kommando-Byte-Tabelle (siehe [`PROTOCOL.md`](PROTOCOL.md))
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

Eine rudimentäre Kotlin/Compose-App — eine Activity, eine Liste der gebondeten Geräte, ein paar Buttons für `Connect` / `Enable GPS` / `Send Berlin`. Kein Foreground Service, keine echte Location, keine UI-Politur. Das Ziel war nur: **das Protokoll auf echter Hardware validieren**.

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

Siehe [`PROTOCOL.md`](PROTOCOL.md) für die aktualisierte technische Referenz.

## Phase 7 — Echter Auto-Start und Hintergrund-Leben (April 2026)

Nach Phase 6 war die App funktional komplett, aber der User musste sie nach jedem Handy-Neustart manuell öffnen und „Start" drücken. Damit lief die App faktisch genauso umständlich wie die Canon-App selbst — der Hauptnutzen der passiven Scan-First-Architektur verpuffte. Phase 7 hat das korrigiert, und dabei einige Android-14+-Fallstricke freigelegt, die wir in Phase 6 noch nicht ansteuern mussten.

### Strategieentscheidung: PendingIntent-Scan statt Dauer-Foreground-Service

Der naive Weg wäre gewesen: `BOOT_COMPLETED`-Receiver, der den bestehenden Foreground Service 24/7 anstarrt. Das hätte aber einerseits die UX verhunzt (Dauer-Notification auch wenn die Kamera eine Woche nicht bewegt wird) und andererseits die Batterie mit dauerhaft laufendem BLE-Scan belastet — 2–5 %/Tag im Leerlauf, selbst mit `SCAN_MODE_LOW_POWER`.

Stattdessen nutzen wir jetzt Androids **PendingIntent-basierten Offloaded-Scan**: `BluetoothLeScanner.startScan(filters, settings, pendingIntent)` registriert einen Filter im System, der im BT-Chip / BT-HAL läuft. Wenn ein Match kommt, broadcastet Android an den `ScanResultReceiver`, der kann dann gezielt den Foreground Service hochfahren — nur für echte Wake-Events.

Batterie-Impact im Leerlauf: <0,5 %/Tag. Keine 24/7-Notification. Die App-Prozess schläft die meiste Zeit, wir haben nur einen Manifest-Receiver der beim Match getriggert wird.

### Der Hardware-Filter: nur auf das, was wir verstehen

Der HW-Filter nutzt `setManufacturerData(0x01A9, data, mask)`. Erste Version war strict: alle 6 Bytes exakt matchen. Das war falsch:

- Byte 0–4 kannten wir nur empirisch auf genau einer R6m2 (`01 0b 33 f3 4b`) — unverifiziert ob das zwischen Firmware-Versionen oder Exemplaren konstant ist.
- In Byte 5 kannten wir nur zwei Werte: `0x02` (awake) und `0x05` (asleep). Die unterscheiden sich nur in den unteren 3 Bits (`010` vs `101`); was die oberen 5 Bits je machen könnten, wissen wir nicht.

Finale Filter-Maske: `data=[0,0,0,0,0,0x02]`, `mask=[0,0,0,0,0,0x07]`. Also ignoriere Bytes 0–4 komplett, und in Byte 5 matche nur die unteren 3 Bits. Das folgt direkt der Phase-6-Lesson: **nicht auf unverifizierte Felder bauen**. Wenn Canon irgendwann ein Flag-Bit dort oben setzt — kein Problem, wir matchen weiter.

MAC-Filter zunächst bewusst NICHT im HW-Filter: `setDeviceAddress(String, int)` mit explizitem Address-Type ist `@SystemApi`, die 1-Argument-Variante war in Phase 6 als pickig kommentiert worden. Die Annahme hat sich im Nachtest aber nicht bestätigen lassen: Canons MAC ist public (Murata-OUI im IEEE-Register), Android 12+ löst das mit dem 1-arg-`setDeviceAddress(mac)` sauber auf. Nach der Verifikation ist die MAC jetzt in beiden Scan-Pfaden Teil des HW-Filters — keine Weck-Broadcasts mehr für fremde Canon-Kameras, und der MAC-Check im Receiver-Code entfiel ersatzlos. Lesson: Annahmen aus vorigen Phasen nicht als Fakten übernehmen ohne Nachtest.

### Android 14+ Foreground-Service-Fallstricke

Erste Hürde nach dem Feature-Build: Service-Crash-Schleife. Stack-Trace zeigte:

```
SecurityException: Starting FGS with type location … and the app must be
in the eligible state/exemptions to access the foreground only permission
```

Auf Android 14+ darf ein FGS vom Typ `location` nur starten, wenn die App in einem „eligible state" ist (User-Interaktion, sichtbare Activity). Ein Scan-Broadcast ist das nicht. Der bestehende `location|connectedDevice`-Service aus Phase 6 war durch manuelles „Start"-Drücken immer eligible — Phase 7 hat diese Annahme gesprengt.

Erste Korrektur: FGS-Typ auf nur `connectedDevice` reduziert. Crash weg, Service startet. Aber: FusedLocationProvider liefert im Hintergrund keine Updates mehr, sobald MainActivity nicht sichtbar ist — die App gilt als „in background", ohne `location`-FGS-Typ kein Background-Location-Zugriff.

Lösung: `ACCESS_BACKGROUND_LOCATION` als explizite Exemption dazunehmen. Damit kann der FGS wieder `location|connectedDevice` sein, ohne eligibility-Check. Runtime-Flow dafür muss zweistufig sein (Android 11+): erst FINE/COARSE regulär, dann separater Request für Background, der den User in System-Settings auf „Immer zulassen" lenkt.

UI zeigt einen orangefarbenen Hinweis „Hintergrund-Ortung: ohne 'Immer zulassen' kommen Fixes nur wenn App offen ist" solange die Permission fehlt. Der Service selbst wählt den FGS-Typ zur Laufzeit — `hasBackgroundLocation()` true → mit `location`, sonst ohne.

### Edge-Detection im OS-Scan

`CALLBACK_TYPE_FIRST_MATCH` feuert nur an Zustandsübergängen des internen Match-Engines: `lost → found`. Solange ein Gerät als „found" gilt, kommt kein weiterer Broadcast. Nach `MATCH_MODE_AGGRESSIVE`-Timeout ohne Match fällt der State auf „lost". Erste neue Ad = neue Kante = neuer Broadcast.

Problem: wenn die Kamera während unserer aktiven GATT-Session + 30s-Reconnect-Grace durchgehend awake ist (keine Advertisements, weil connected), könnte der Scan-Engine die Session als „found, ongoing" sehen. Nach Service-Ende bleibt der State „found" → keine weitere FIRST_MATCH-Kante, obwohl die Kamera immer noch awake ist.

Fix: beim Handoff vom Service zurück in den Idle-Modus (`reconnectGiveup`) wird der Scan **re-armed** — `unregister` + `register` setzen den Edge-Detection-State zurück. Nächste awake-Ad ist garantiert eine neue Kante.

### 30-Minuten-Limit auf den internen Scan

Android drosselt jeden direkten `ScanCallback`-Scan nach ~30 Minuten automatisch auf `SCAN_MODE_OPPORTUNISTIC` — lautlos, keine Warnung. In unserer Architektur ist das normal egal: der interne Scan läuft nur in kurzen Fenstern (initial bei Manual-Start, 30s Reconnect-Grace). Aber: wenn ein User „besonders clever" sein will und bei aktivem Auto-Start zusätzlich manuell „Start" drückt weil er denkt das beschleunigt was — dann läuft der interne Scan statt des OS-Scans, und nach 30 Min still gedrosselt.

Fix: Hard-Cap von 2 Minuten auf den internen Scan. Danach automatisch Handoff: wenn OS-Scan registriert → re-arm + exit, sonst nur exit (User merkt am fehlenden Notification dass nichts mehr läuft, keine stille Verstockung).

### Ein leerer ScanResult — und was er über PendingIntent-Scans verrät

Als die ersten Broadcasts reinkamen, zeigte unser Debug-Log ein verstörendes Bild:

```
result addr=XX:XX:XX:XX:XX:XX rssi=0 mfg01A9=null b5=n/a
```

MAC stimmte. Aber `scanRecord` komplett leer — kein RSSI, kein Mfg-Data. Der anfängliche Receiver-Code hatte byte 5 nochmal als Defense-in-Depth gecheckt — das matchte natürlich nicht, die Broadcasts wurden verworfen.

Erklärung: Android liefert bei PendingIntent-basierten Offloaded-Scans oft eine **verschlankte ScanResult** in den Intent-Extras. Der BT-HAL hat die komplette Ad ausgewertet und gegen den HW-Filter gematched, aber für die Broadcast-Zustellung wird nur das Device-Objekt (MAC) erhalten. `rssi=0` ist ein dead giveaway.

Konsequenz-Entscheidung (User hat mich da zurechtgewiesen): **zwei unterschiedliche Filter in HW und Code sind Code-Smell**. Der HW-Filter hat das awake-Matching schon gemacht, sonst käme gar kein Broadcast. Der Receiver-Code filtert ausschließlich MAC — das Datum ist in `ScanResult.device.address` verlässlich da, unabhängig vom verschlankten `scanRecord`. Ein Filter pro Stage, klare Rollen.

### Interner Scan bekam einen HW-Filter spendiert

Der interne Service-Scan aus Phase 6 lief `startScan(emptyList(), …)` — kein HW-Filter, alle Ads weltweit kamen rein und wurden im Code gefiltert. Historisch durch einen Kommentar begründet dass `setDeviceAddress` pickig sei (war aber ein MAC-Filter-Problem, nicht `setManufacturerData`).

Phase-7-Cleanup: HW-Filter auf Canon-Company-ID (`0x01A9`, alle Ads dieser Company, beide Power-States) im internen Scan. Fremdbeacons werden schon im Chip verworfen. Power-State-Decoding (awake/asleep für UI-Anzeige + Staleness-Watchdog) bleibt im Code, aber auf low-3-Bit-Logik umgestellt — konsistent mit der HW-Filter-Philosophie.

Die gemeinsamen Konstanten wanderten in eine neue `CanonAd.kt` — `COMPANY_ID`, `POWER_BYTE_INDEX`, `AWAKE_MFG_DATA/MASK`, `powerStateFromByte()`. Beide Scan-Pfade importieren dieselbe Quelle der Wahrheit.

### Stand April 2026 (Ende Phase 7)

App ist jetzt wirklich Alltag-fertig:

- Scan-Registrierung überlebt Boot + App-Update (BootReceiver auf BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, MY_PACKAGE_REPLACED)
- Auto-Start-Toggle in der UI, persistiert in SharedPreferences
- Battery-Optimization-Exemption-Prompt + Background-Location-Prompt mit klaren UI-Warnungen
- Connect nur noch wenn Kamera awake (HW-gefiltert), keine CPU-Arbeit für schlafende oder fremde BLE-Geräte
- 30s Reconnect-Grace mit schnellem Re-Connect, dann Handoff an OS-Scan
- 2min Timeout auf den internen Scan, nie in Android-30-Min-Drosselung
- GPS-Updates funktionieren mit geschlossener Activity, durch `connectedDevice|location`-FGS + BACKGROUND_LOCATION

Die Phase-7-Lessons sind unten im erweiterten Lessons-Learned-Abschnitt dokumentiert.

## Phase 8 — Vereinfachung auf einen Schalter (April 2026)

Nach Phase 7 war die App zwar alltagstauglich, aber intern schleppte sie Mechanik mit, die im Rückblick eindeutig über das Ziel hinausschoss. User-Feedback nach ein paar Tagen Nutzung: *„zu viele Timer und Systeme. Ich will nur einen Schalter."*

### Bestandsaufnahme dessen was zu viel war

Drei Scanner-ähnliche Kontexte liefen parallel:

1. `CanonScanRegistrar` — OS-offloaded PendingIntent-Scan
2. `GpsTrackingService` interner `ScanCallback` — während aktiver Session + 30s Reconnect-Grace + 2min Hardcap
3. Staleness-Watchdog — Handler-Runnable alle 2s, kippt `cameraPower` auf `UNSEEN` nach 8s

Vier Timeouts mit unterschiedlichen Semantiken: `AD_STALENESS_THRESHOLD_MS=8s`, `STALENESS_CHECK_INTERVAL_MS=2s`, `RECONNECT_GRACE_MS=30s`, `INTERNAL_SCAN_MAX_MS=2min`. Drei Handoff-Pfade zwischen Service und OS-Scan: `scanTimeout` (nach 2min), `reconnectGiveup` (nach 30s), `awakeHint` als Intent-Extra für den Fast-Path-Start.

Dazu die UI: Start/Stop-Buttons plus Auto-Start-Toggle, also zwei User-Controls für denselben semantischen Zustand. Ein scrollender Log-Panel unten, der primär der Bring-up-Phase diente und sich danach als Clutter entpuppte.

### Das Zielmodell

*Ein* User-Schalter `trackingEnabled`. *Ein* Scanner-Owner zu jedem Zeitpunkt, klar an eine Android-Lebenszeit gebunden:

- **Activity sichtbar** → `FgScanner` (Activity-owned object) läuft. Zeigt alle Power-States live. Triggert FGS bei `POWER_ON` wenn Schalter on.
- **Activity unsichtbar + Schalter on** → `CanonScanRegistrar` (OS-offloaded) ist armed. System weckt den FGS über `ScanResultReceiver`.
- **Schalter off, alles egal** → nichts läuft passiv.

Der FGS wird aus einem Dauer-Scanner-Hoster zu einem reinen GATT-Session-Owner: start → connect → stream → disconnect → exit. Keine eigene Scan-Logik mehr.

### Entscheidungen mit User im Interview

Vor dem Umbau wurden die heiklen Punkte explizit abgestimmt:

- **FGS-Scope?** → nur während GATT-Session. *Nicht* Dauer-FGS bei Schalter on (keine Dauer-Notification).
- **Schalter off + App offen?** → Scanner läuft trotzdem, zeigt Ads. Nur GPS-Transmit bleibt aus.
- **GATT-Disconnect während Session?** → sofort exit, kein Grace-Fenster. Reconnect nur über ad-getriggerten frischen Start. „Codemässig am klarsten" (User).
- **Activity-Lifecycle?** → zunächst `onStart/onStop` vorgeschlagen; User hat mich korrigiert auf `onResume/onPause`. Begründung: „Live-Scanner soll nicht versehentlich lange leben wenn er nicht sichtbar ist, er braucht mehr Batterie und kann ggf. undetektiert gedrosselt werden."
- **Prefs-Key?** → Rename `autoStartEnabled → trackingEnabled` mit einmaliger Migration im Getter. Bestehende Installs verlieren ihren Opt-in nicht.
- **Log-Panel?** → weg. Debug-Info nur noch via `adb logcat`.
- **Debug-UI-Felder (RSSI, Fix-Rate, Write-Errors, Ad-Freshness)?** → vorerst beibehalten für die Verifikationsphase. UI-Polish-Pass kommt später, wenn die Architektur in Praxis solide läuft.
- **Scan-Restart-Intervall?** → 20 min, gegen Android's 30-min-Drosselung. Throttling ist nicht detektierbar (kein Callback, keine API).

### Umsetzung

`GpsTrackingService` schrumpfte von 525 auf 271 Zeilen. Weg: `ScanCallback`, `BluetoothLeScanner`-Feld, `scanning`-Flag, `handleAdvertisement`, `markContact`, `markAdvertisement`, `staleWatchdog` + `watchdogAnchorMs`, `scanTimeout` + `INTERNAL_SCAN_MAX_MS`, `reconnectGiveup` + `RECONNECT_GRACE_MS`, `AD_STALENESS_THRESHOLD_MS` + `STALENESS_CHECK_INTERVAL_MS`, `EXTRA_AWAKE_AD_HINT` + `startForAwakeAd`. Was blieb: `startForeground` mit richtigem FGS-Typ, `FusedLocationProvider`-Loop, RSSI-Poll (Debug), `CanonGattClient`-Anbindung, Notification-Updates.

Neu: `FgScanner.kt` als `object` mit `start(ctx)`/`stop()`, HW-Filter `MAC + 0x01A9` (keine byte5-Einschränkung weil UI alle Power-States zeigen soll), 20-min Handler-Restart. Bei `POWER_ON && trackingEnabled && !serviceRunning` → `GpsTrackingService.start(ctx)`.

`MainActivity` bekam ein `DisposableEffect` mit `LifecycleEventObserver`, das bei `ON_RESUME` den OS-Scan abmeldet + `FgScanner` startet, bei `ON_PAUSE` umgekehrt. Start/Stop-Buttons weg. Log-Panel weg. Ein Switch in einer einzigen Card mit zwei Zustandslabels („on / verbindet & streamt GPS sobald die Kamera angeht" vs. „off / zeigt nur Ads, kein GPS-Transmit"). Die Akku-Opt- und Background-Location-Prompts wurden beibehalten.

Ad-Staleness wird ab jetzt **in der UI** aus `TrackingState.lastAdvertAt` abgeleitet (`effectivePower` im Compose-Scope): wenn `connState == IDLE` und seit >8s keine Ad, zeige `UNSEEN`. Der 1-Hz-Ticker recomposed eh pro Sekunde, die Derivation kostet nichts. Kein separater Watchdog-Timer mehr.

`CanonGattClient` verlor den `onLog: (String) -> Unit`-Callback, der nur zum UI-Log beitrug — alle Log-Zeilen gehen weiter durch `Log.i(TAG, …)`, sind also in `adb logcat` unverändert sichtbar.

### Der Bug, den die Vereinfachung schuf, und der Fix in der UI

Sobald die erste Version lief, sofort User-Feedback: *„wenn die Kamera Battery-Pull oder Out-of-Range geht, zeigt die App immer noch 'power on' an."*

Stimmt. Der alte Staleness-Watchdog hatte `cameraPower` aktiv auf `UNSEEN` gekippt — ohne ihn bleibt der letzte Wert ewig stehen. Plan-konforme Lösung: UI-seitige Derivation mit `effectivePower`, Konstante `AD_STALENESS_MS = 8_000L`, recompute über den 1-Sekunden-Ticker. Kein neuer Timer, keine neue State. Acht Zeilen Code.

### Review-Runde mit parallelen Agenten

Nach dem ersten Durchlauf wurde der Diff an vier unabhängige Review-Instanzen gereicht — drei Claude-Subagenten (Code-Reuse / Quality / Efficiency) und Codex via MCP, mit YAGNI/DRY/SOLID/KISS-Brille. Ergebnis:

- **Hard-Gate fehlte** (Codex): Ein OS-offloaded PendingIntent, der schon unterwegs ist wenn der User Schalter-off drückt, kann den FGS trotzdem starten. Fix: `Prefs.trackingEnabled`-Check an beiden Entry-Points (`FGS.startTracking` und `ScanResultReceiver.onReceive`), nicht als Code-Smell sondern als bewusstes Schutz-Gate.
- **FgScanner.restartRunnable konnte doppelt posten** (Quality, Confidence 82): `onScanFailed` setzt `running=false` ohne das pending restart zu canceln. Wenn `start()` danach erneut läuft (z.B. aus dem Permission-Launcher-Callback), würden zwei 20-min-Loops parallel fahren. Fix: `removeCallbacks(restartRunnable)` in `start()` *vor* `postDelayed`.
- **Compose-Ticker lief forever** (Efficiency + Codex): `LaunchedEffect(Unit) { while(true) ... }` ist an die Composition gebunden, nicht an den Activity-Lifecycle. Im Hintergrund tickt er weiter solange die Composition lebt. Fix: `lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { while(true) ... }`.
- **Leaky `MainActivity.isVisible`** (Codex + Quality): Der Service las ein statisches `@Volatile`-Flag direkt aus der Activity-Klasse. Cleaner in `TrackingState.appVisible` verschoben; `@Volatile` entfiel (Zugriff ist main-thread-only in beiden Richtungen).
- **DRY: Preflight-Duplikat** (Reuse + Codex Nit): `FgScanner` und `CanonScanRegistrar` wiederholten je ~6 Zeilen `hasPerm → BluetoothManager → adapter.isEnabled → adapter.bluetoothLeScanner`. In `Bluetooth.readyScanner(ctx): BluetoothLeScanner?` extrahiert — Callsites schrumpfen auf eine Zeile.
- **Dead code** (Codex Nit): unused `Arrangement`-Import, unused `val serviceRunning by collectAsState()` in `MainActivity`, veraltete `CanonAd.kt`-Klassendoku die noch den abgeschafften „in-service scan" erwähnt. Alle gefixt.

Bewusst übersprungen: RSSI-Poll streichen (User-Entscheidung: Debug bleibt), ScanFilter-Builder extrahieren (zwei Call-Sites, fundamental unterschiedliche Filter-Semantik — awake-only vs. all-states), Notification-PendingIntent-Cache (6 Rebuild/min sind vernachlässigbar), `stopTracking`-cameraPower-Reset (die 8-s-Staleness deckt das ab).

### Stand nach Phase 8

Die App bleibt funktional identisch zu Phase 7: Boot-Auto-Start, Auto-Connect bei Kamera-Einschalten, EXIF-Output-Identität mit der Canon-App, <0,5 %/Tag Batterie im Leerlauf. *Was sich geändert hat ist die Innenseite* — 417 Zeilen weg, 225 dazu (netto −192), klare Ownership-Regeln, ein Switch, eine Staleness-Quelle, ein Lifecycle-bound Ticker.

## Phase 9 — BLE-Feature-Exploration und Shutter-Auslöser (April 2026)

„BLE-Daten-Exploration + Feature-Ausbau" war der nächste Punkt. Konkret stand die Frage im Raum welche der Canon-Characteristics im Smart-Pairing-Modus tatsächlich was tun, und ob sich ein Shutter-Write auf `00030030` (aus furble Issue #189 bekannt) in unsere GPS-Session einbauen lässt.

### Phase A — passive Exploration (und Abbruch)

Erster Schritt: neue READs auf `0001000b` (PAIRING_RESPONSE), `00020001` (HANDOVER_STATE), `00030001/11/21/31` (Remote-State-Chars) und `00010005` (PAIRING_REQUEST) während der Subscribe-Phase, plus best-effort NOTIFY-Subscribes auf `00020004/5/6`. Ziel: Initialwerte sehen und beobachten welche Chars Events pushen.

Ergebnis der Reads (alle statisch zwischen Sessions):

| Char | Wert |
|------|------|
| `00040001` STATUS | `03 13 00` |
| `00040003` NOTIFY | `02 00` (ready-Byte der letzten Session) |
| `00020001` HANDOVER_STATE | `7f 00 00 00` |
| `00030001` REMOTE_STATUS | `01 01 01` |
| `00030011` REMOTE_ZOOM | `01` |
| `00030021` REMOTE_EXPOSURE | `3f 00 00 00` |
| `00030031` REMOTE_APERTURE | `01 01 01` |
| `00010005` PAIRING_REQUEST | `01` |
| `0001000b` PAIRING_RESPONSE | `07 00 00 00` |

Zusätzlicher Fund: nach dem Handover-`0x0a`-Write pusht die Kamera eine 20-Byte-NOTIFY auf `00020002`: `7f1010040201a8c0020692c17bea903430013c`. Byte 8–13 (`02:06:92:c1:7b:ea`) ist die Phone-MAC aus Canons Sicht, Rest ist der WiFi-Handover-Handshake.

Beim Live-Test mit allen Kamera-Knöpfen (Shutter halb, Shutter voll, Modus-Rad, Blende, ISO, Zoom-Ring): **null NOTIFY-Traffic** auf den Remote-Chars. Also sind die Chars im Smart-Pairing-Modus für körperliche Kamera-Bedienung stumm.

Die Phase-A-Reads verdoppelten außerdem die Subscribe-Phase-Dauer von ~800 ms auf ~1600 ms. Die Sessions blieben in mehreren Tests auf SUBSCRIBING hängen — ob ursächlich durch die Op-Menge oder durch eine separat existierende BT-Instabilität, liess sich im HCI-Snoop nicht eindeutig zuordnen. Phase A wurde rausgenommen, die gewonnenen Daten blieben in der Doku.

### HCI-Snoop gegen Canon Camera Connect

Um das Shutter-Protokoll wirklich zu verstehen (statt aus furble zu raten), wurde die Canon-App im Smart-Pairing-Modus per `adb bugreport`-btsnoop beobachtet während der User Fotos/Videos/Display-Navigation durchspielte. Handle-zu-UUID-Mapping aus dem gleichen Snoop rekonstruiert.

Writes auf unbekannte Handles gefunden:

| Handle | UUID | Semantik |
|--------|------|----------|
| `0x0033` | `00030010` | 1-Byte-Writes (`01`/`02`/`03`) — Zoom-CMD oder Review-Navigation |
| `0x0038` | `00030020` | 4-Byte-Writes (`80000080`/`80000040`) — Exposure-/Display-CMD |
| `0x003d` | `00030030` | **2-Byte-Press/Release-Codes — Shutter-Char** |

Der 2-Byte-Codespace auf `00030030` zeigte im Snoop mehrere Werte in press/release-Paaren: `0001/0002`, `0010/0011`. Hypothese damals: half-press / full-press.

### Dekompilat-Runde: Canon's eigene Labels

Der Explore-Agent hat das jadx-Dekompilat der Canon-App gezielt nach `writeCharacteristic`-Aufrufen mit der Shutter-Char durchsucht. Fundort: `C0500A.java` Methode `E(int i5)` mit `Log.d`-Strings, Byte-Konstanten in `com.canon.eos.N.java`. Die Byte→Label-Zuordnung kommt direkt aus dem Canon-Code (Log-Strings nicht minifiziert):

```
0x0001 → AF_REL_ON
0x0002 → AF_REL_OFF
0x0003 → AF_ON
0x0004 → AF_OFF
0x0005 → REL_ON
0x0006 → REL_OFF
0x0010 → MOVIE_START
0x0011 → MOVIE_STOP
0x0020 → ZOOM_TELE
0x0021 → ZOOM_TELE_STOP
0x0022 → ZOOM_WIDE
0x0023 → ZOOM_WIDE_STOP
```

Die Labels sind Canons Benennung, nicht vom Decompiler geraten. Die frühere „half-press"-Hypothese für `0001/0002` passt schon mal nicht zum Canon-Label `AF_REL_ON/OFF` — das suggeriert AF + Release kombiniert, nicht Halb-Drücker. Was die Kamera-Firmware bei den anderen Codes tatsächlich macht haben wir nicht verifiziert. Für das Projekt relevant: `0001/0002` direkt hintereinander löst empirisch ein Foto aus, wenn der Autofokus scharfstellen konnte. Alle anderen Codes blieben ungetestet.

### Implementierung

Minimalster Einstieg: ein Button „Auslösen" in der UI, verfügbar wenn `gpsState == READY_TO_RECEIVE`. Enqueued zwei WRITE_TYPE_DEFAULT-Ops auf `shutterChar` mit den Bytes `00 01` und `00 02`. Kein eigener State, kein Focus-vs-Shutter-Dual, kein Video-Toggle — der User kann die Kamera manuell in Photo-Mode stellen, alles andere ist Folge-Feature.

`CanonGattClient.triggerShutter()` + `GpsTrackingService.ACTION_SHUTTER` + Compose-Button. Schmaler Diff, isolierbar.

### Beobachtungen nach dem ersten Live-Test

- Shutter-Press bei scharfgestelltem Motiv → Foto auf SD-Karte, keine NOTIFYs auf subscribed Chars (1 Datenpunkt).
- Shutter-Press bei fehlgeschlagenem Autofokus (nichts Fokussierbares) → kein Foto, **aber** NOTIFY `0002 0313` (REMOTE_EVENTS) und `0031 010101` (REMOTE_APERTURE) ~540 ms nach dem zweiten ACK. In zwei Datenpunkten identisches Muster.
- Die frühe Phase-A-Beobachtung „Remote-Chars sind im Smart-Pairing komplett stumm" stimmte nur solange wir nichts schrieben. BLE-Writes auf die Shutter-Char lösen NOTIFY-Traffic aus; körperliche Kamera-Knöpfe (Fokus-Halb-Drücken etc.) weiter nicht.

### Offen geblieben: GATT-Session-Cycling

Parallel zur Shutter-Arbeit zeigte sich ein Stabilitätsproblem: Sessions brechen manchmal exakt ~2 s nach dem letzten erfolgreichen Paketwechsel mit `status=8` ab (HCI-Connection-Timeout / LL-Response-Timeout), gelegentlich `status=61` (HCI 0x3D, Connection-Establish-Failure). Reconnect läuft sauber, Session ~5–6 s, Abbruch, Reconnect, … im Zyklus.

Nachgeprüft und *nicht* gefunden: keine Korrelation mit Shutter-Button (auch plain-main-Build ohne Phase-C-Diff zeigt das Cycling nach einer Weile), keine FgScanner-Ad-Flood im kritischen Fenster, kein Canon-App-Prozess mehr aktiv, keine orphaned Scan-Registrierung nach `pm clear`. Der Supervision-Timeout steht auf 200 (2 s), gesetzt per `onConnectionUpdated`-Event ~500 ms nach `GPS_SESSION_ACTIVE` — Initiator (Kamera vs. Phone-Stack) aus dem HCI-Log nicht ableitbar.

Keine Hypothese die ich belegen kann. Dokumentiert als offener Punkt für die nächste Runde.

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
- **MAC-OID und BLE-Company-ID sind zwei verschiedene Registries.** Canons R6m2 hat eine MAC deren OID laut IEEE an **Murata Manufacturing** vergeben ist (der Hersteller des BLE-Moduls), während die Advertisement-Company-ID `0x01A9` laut Bluetooth SIG an **Canon Inc.** vergeben ist. Es ist kein Widerspruch — die Hardware kommt von Murata, das Protokoll ist Canons. Scanner-Tools zeigen beides gleichzeitig an; leicht verwechselbar.
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
- **PendingIntent-Scan statt Foreground-Dauerscan für Idle-Beobachtung.** Android hat die API `startScan(filters, settings, pendingIntent)` genau für Beacon-/Companion-Apps gebaut: der Scan läuft im System, broadcastet nur beim Match, der App-Prozess darf schlafen. Keine 30-Min-Drosselung, kein Dauer-FGS, kein Dauer-Notification. Die klassische Foreground-Service-mit-ScanCallback-Lösung ist für „immer an, wartet passiv" der falsche Hammer.
- **Ein Filter pro Stage, nicht zwei.** Bei zwei Scan-Pfaden (HW + Code) konsequent aufteilen: HW-Filter entscheidet *was* durchgelassen wird, Code-Filter entscheidet *welches* Gerät wir meinen. Dasselbe Kriterium in beiden Stages prüfen ist Code-Smell — und wenn die Daten in einer Stage nicht zuverlässig verfügbar sind (wie der leere `scanRecord` im PendingIntent-Broadcast), führt Duplikation zu stillen Fehlern.
- **Android 14+ FGS-Typen sind Access-Tokens, keine Tags.** Der FGS-Typ `location` darf nur gesetzt werden wenn die App eligible ist (User-Interaktion oder Background-Location-Permission). Ein Broadcast-Trigger ist nicht eligible. Konsequenz: `connectedDevice`-only reicht für Scan-wake-GATT, aber für Background-Location braucht man entweder sichtbare Activity *oder* `ACCESS_BACKGROUND_LOCATION` als Exemption. In Cross-Check: FGS-Typ bestimmt was die App während des FGS *darf*, nicht was sie ist.
- **`CALLBACK_TYPE_FIRST_MATCH` ist edge-triggered — die Kante kann verlorengehen.** Wenn ein Device während einer aktiven Verbindung nicht advertisen kann, fällt sein Match-State beim OS auf „lost", erste Ad nach Disconnect = neue Kante = neuer Broadcast. Wenn es aber durchgehend advertiset (z.B. während wir im Reconnect-Grace-Scan sind), kann es als dauerhaft „found" gelten — keine weitere Kante mehr. Beim Handoff zurück in den Idle-Modus deshalb `unregister` + `register`, um den Edge-Detection-State zu resetten.
- **Scan-Callback-Varianten haben unterschiedliche Haltbarkeit.** Direkte `ScanCallback`s werden nach ~30 Minuten auf `SCAN_MODE_OPPORTUNISTIC` gedrosselt. PendingIntent-basierte nicht. Wenn der interne Scan in einer App länger als wenige Minuten laufen darf, in Richtung stummer Nicht-Funktion. Immer Hard-Timeout setzen und zurück auf PendingIntent-Pfad handen.
- **PendingIntent-Scan liefert reduzierte ScanResults.** Im Broadcast sind `rssi`, `scanRecord.bytes`, `getManufacturerSpecificData()` oft alle null oder leer — nur `device.address` ist verlässlich da. Wer auf detaillierte Payload-Daten im Receiver angewiesen ist, hat ein Problem. Dem HW-Filter vertrauen oder auf direkten `ScanCallback` umsteigen.
- **Bash-Pipes schlucken Exit-Codes.** `gradle | tail && adb install` läuft auch bei BUILD FAILED weiter, weil `tail` immer 0 zurückgibt, wenn es lesen konnte. Ergebnis: alter APK bleibt auf dem Gerät, Änderungen scheinen nicht zu greifen, Debug-Runde für Nichts. Entweder `set -o pipefail` oder `gradle > /tmp/x.log 2>&1 && adb install …; tail /tmp/x.log` — Exit-Code muss direkt vom Builder kommen.
- **Timer sind der Default-Ausweg aus Ownership-Unklarheit.** Phase-7-Architektur hatte drei Timer (`AD_STALENESS`, `RECONNECT_GRACE`, `INTERNAL_SCAN_MAX`), weil jeweils eine Zuständigkeits-Frage nicht sauber beantwortet war — „wer merkt dass die Ad fehlt", „wer wacht darüber ob reconnect klappt", „wer verhindert Drosselung". Sobald *ein* Owner pro Lifecycle-Phase festgelegt ist (Activity-Scanner vs. OS-Scan vs. FGS-GATT-only), werden die Timer redundant: der jeweilige Owner sieht das Event oder er sieht es nicht, kein Drittsystem muss mitkontrollieren. **Leitsatz:** jeder Timer im Code ist eine Frage „wer ist hier zuständig" die noch nicht entschieden war.
- **UI-Derivation statt Hintergrund-Watchdog.** Ad-Staleness als `UNSEEN`-Zustand war in Phase 7 ein 2s-Handler-Runnable, der `TrackingState.cameraPower` kippte. In Phase 8 ist es eine `when`-Klausel im Compose-Scope, die `effectivePower` aus `cameraPower` + `lastAdvertAt` + `connState` + `nowTick` ableitet. Die UI tickt eh pro Sekunde. Vorteile: kein lebender Timer wenn UI nicht sichtbar, kein State zum synchron halten, kein Race zwischen „Timer kippt Power" und „neue Ad setzt Power zurück". **Prinzip:** derive in the UI was du aus primary state berechnen kannst, persistiere es nicht.
- **`LaunchedEffect(Unit) { while(true) { ... delay(X) } }` ist nicht lifecycle-scoped.** Die Composition hält den Job am Leben auch wenn die Activity in `onPause` ist — nur `onDestroyCompose` (faktisch selten) beendet ihn. Für echte Lifecycle-Bindung: `LaunchedEffect(lifecycleOwner) { lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { while(true) { ... delay(X) } } }`. Vor allem relevant für endlose Ticker oder Poll-Loops — ein vergessener Wake-up pro Sekunde im Hintergrund ist auf Mobile messbar.
- **Verschiedene Contexte fürs selbe „wann bin ich sichtbar"-Flag.** Phase 8 hatte kurzzeitig `MainActivity.isVisible` als `@Volatile var` im Companion — der Service griff direkt in die Activity-Klasse. Sauberer: ein geteiltes Flag im State-Holder (`TrackingState.appVisible`). Allgemeiner: **wenn ein Service sich für den UI-Lebenszyklus interessiert, gehört die Kopplung über ein shared state object, nicht über direkten Klassen-Zugriff.** Das vermeidet zirkuläre Abhängigkeiten und macht Testing trivial.
- **Hard-Gates gegen in-flight asynchrone Trigger.** Ein `PendingIntent` der beim BT-Stack schon in der Queue liegt, wird auch dann noch zugestellt wenn dein Code ihn „abgemeldet" hat. Gleiches gilt für noch nicht konsumierte Intent-Broadcasts. **Jeder Entry-Point ins FGS** sollte den aktuellen Prefs-Zustand prüfen, nicht nur dem Aufrufer vertrauen — der Aufrufer könnte aus einer veralteten Welt kommen. Zwei Zeilen Defensive Code, verhindern sonst subtile „warum baut die App jetzt eine Session auf obwohl ich Tracking aus hatte"-Bugs.
- **Parallele Review-Agenten finden andere Bugs als sequenzielle.** Vier unabhängige Reviews (drei Claude-Subagenten, einer Codex via MCP) fanden je *eigene* Probleme — der Reuse-Agent die DRY-Chancen, der Quality-Agent den `restartRunnable`-Double-Post, der Efficiency-Agent den Compose-Ticker-Leak, Codex das fehlende Hard-Gate und die leaky `isVisible`. Keiner davon hätte alleine alle fünf gefunden. **Refactor-Reviews sind embarrassingly parallel** — verschiedene Brillen lesen denselben Diff und priorisieren unterschiedlich. Kostet ein paar Dollar Tokens und spart einen Tag Bug-Hunt.

## Phase 10 — Onboarding, Kamera-Auswahl, UI-Verbesserungen (April 2026)

Die App war bisher nur für den Entwickler benutzbar: `findBondedCanon()` nahm blind das erste gebondete "EOS"-Gerät, Permissions wurden als undifferenzierter Block abgefragt, es gab keinen Erst-Start-Flow. Phase 10 macht die App für andere benutzbar.

### Onboarding-Flow

Screen-State-Machine statt Navigation-Library (App hat eine Activity):

```
enum class Screen { PERMISSION, DEVICE_PICKER, MAIN }
```

Bei jedem `onResume` wird der Screen neu bestimmt: fehlende Permissions → `PERMISSION`, kein Gerät gewählt → `DEVICE_PICKER`, sonst → `MAIN`. Kommt der User aus den Settings zurück und hat eine Permission entzogen, landet er automatisch auf dem Permission-Screen.

Fünf Permission-Gruppen werden sequenziell abgefragt, jeweils mit eigenem Erklärungsscreen und "Erlauben"-Button:

1. **Bluetooth** (CONNECT + SCAN) — "Die App kommuniziert per Bluetooth LE mit deiner Canon-Kamera."
2. **Standort** (FINE + COARSE) — "GPS-Koordinaten werden an die Kamera gesendet. Android benötigt Standortzugriff für BLE-Scans."
3. **Hintergrund-Standort** (BACKGROUND_LOCATION, separat wegen Android 11+) — "Damit GPS auch bei geschlossener App gestreamt wird."
4. **Benachrichtigungen** (POST_NOTIFICATIONS, API 33+) — "Zeigt eine Benachrichtigung während GPS gestreamt wird."
5. **Akku-Optimierung** (Intent, kein Runtime-Permission) — "Ohne diese Ausnahme kann Android die App drosseln."

`hasAllPerms()` leitet sich direkt aus `permGroups()` ab — eine einzige Quelle der Wahrheit, kein Drift-Risiko.

Bei "Don't ask again" erscheint ein Hinweis mit Button zu den App-Einstellungen.

### Kamera-Auswahl

`findBondedCanon()` (blind erstes "EOS"-Gerät) wurde ersetzt durch drei Funktionen in `Bluetooth.kt`:

- `findSelectedDevice()` — liest persistierte MAC aus `Prefs.selectedDeviceMac`, sucht in Bonded-Devices
- `findAllBondedCanon()` — alle gebondeten Canon-EOS-Geräte für den Picker
- `resolveSelectedDevice()` — Auto-Select: wenn genau 1 EOS gebondet → automatisch persistieren; bei 0 oder 2+ → null (Picker muss ran)

MAC wird in `Prefs.selectedDeviceMac` persistiert. Alle Scanner/Service-Call-Sites (`FgScanner`, `CanonScanRegistrar`, `GpsTrackingService`) nutzen `findSelectedDevice()`.

Der Device-Picker zeigt alle gebondeten EOS-Kameras mit Name + MAC. Aktuell ausgewählte Kamera ist mit farbigem Punkt markiert. Bei leerer Liste: Hinweis + Button "Bluetooth-Einstellungen öffnen". "Aktualisieren"-Button für Neuladen nach Pairing.

Kamera-Wechsel: Camera-Card im Main-Screen ist tappbar (mit "ändern"-Label). Tap stoppt laufende GATT-Session, entfernt OS-Scan-Registrierung, öffnet den Picker. Back-Button im Picker führt zurück zum Main-Screen (nur wenn bereits eine Kamera persistiert ist).

### UI-Verbesserungen

- **Tracking-Default auf `true`** — App ist bei Erstinstallation sofort betriebsbereit
- **Tracking-Card vereinfacht** — nur "Aktiv"/"Aus" (grün/grau) + Switch, keine mehrzeilige Beschreibung
- **"adv X ago"-Debug-Info entfernt** — war unklar für Nicht-Entwickler
- **Shutter-Button redesigned** — immer sichtbar an fester Position, großer weißer Kreis (Kamera-Auslöser-Look), ausgegraut wenn nicht connected
- **Battery-Opt- und Background-Location-Warnungen** aus der Tracking-Card entfernt — beides wird im Onboarding-Flow abgedeckt
- **Alle Screens scrollbar** — `verticalScroll` auf PermissionFlow, DevicePicker und MainScreen gegen Abschneiden bei kleinen Displays oder großer Schrift

### Review-Runde (6 unabhängige Agenten)

Drei Claude-Subagenten (Reuse / Quality / Efficiency) plus drei Codex-Agenten (ebenfalls Reuse / Quality / Efficiency) über MCP. Neun Findings gefixt:

1. **`hasAllPerms` duplizierte Permission-Liste aus `permGroups`** — abgeleitet statt dupliziert (5 Agenten)
2. **`resolveSelectedDevice` hatte Side-Effect im Router** — Schreib-Logik explizit vor Screen-Bestimmung (4 Agenten)
3. **Dead `refreshKey` in DevicePicker** — entfernt (3 Agenten)
4. **Doppelte `onDone()`-Pfade in PermissionFlow** — redundanten `LaunchedEffect` entfernt (2 Agenten)
5. **Misalignment bei isSelected-Indicator** — immer Platzhalter rendern (1 Agent)
6. **Unnötiger innerer Column im MainScreen-Header** — entfernt (1 Agent)
7. **Bug: FgScanner startete nicht nach Picker-Roundtrip** — `FgScanner.start(ctx)` im `onBack`-Handler (1 Codex-Agent)
8. **BootReceiver fand bei Cold-Start keine Kamera** — `resolveSelectedDevice` vor `register()` (1 Codex-Agent)
9. **Kein Scroll auf PermissionFlow/DevicePicker/MainScreen** — `verticalScroll` ergänzt (1 Codex-Agent)

### Stand nach Phase 10

Die App ist jetzt für andere Nutzer benutzbar: sauberer Onboarding-Flow, jede Permission erklärt, Kamera-Auswahl mit Auto-Select und manuellem Wechsel, Tracking standardmäßig an. Technisch: Screen-State-Machine re-evaluiert bei jedem Resume, eine einzige Quelle der Wahrheit für Permission-Gruppen, persistierte MAC-Auswahl über alle Scan/Service-Pfade konsistent.

---

## Phase 11 — Code-Review + Refactoring (19. April 2026)

### Auslöser

Umfassendes Code-Review der gesamten App: 4 spezialisierte Claude-Agenten (SOLID/DRY/KISS/YAGNI, Bug/Race-Conditions, Android-spezifisch, Code-Qualität) plus 3 Codex-Agenten (MCP). 22 Findings konsolidiert, 19 davon gefixt in 13 Tasks.

### GATT-State-Machine-Fixes (HIGH)

**Doppelter `stopTracking()`-Aufruf:** `stopTracking()` → `gatt?.stopAndDisconnect()` startete eine asynchrone GATT-Teardown-Sequenz, deren Disconnect-Callback erneut `stopTracking()` aufrief. Fix: Guard via `TrackingState.serviceRunning.value` als erstes Statement in `stopTracking()`.

**NO_RESPONSE double-complete:** `pump()` markierte GPS-Writes (WRITE_TYPE_NO_RESPONSE) als sofort fertig und pumpte den nächsten Op, aber Android feuerte trotzdem `onCharacteristicWrite`. Ein später Callback konnte `opInFlight` fälschlich auf `false` setzen und im STOPPING-State einen vorzeitigen Disconnect auslösen. Fix: Zähler `noResponseSelfCompleted`, den `onCharacteristicWrite` dekrementiert und dann skippt.

**Kein Timeout für REQUESTING_GPS:** Wenn der Kickoff erfolgte aber die Ready-Indication nie kam, blieb die Connection ewig in REQUESTING_GPS. Fix: `Handler.postDelayed`-Timeout: 10s für REQUESTING_GPS, 5s für STOPPING. Cancelled bei erfolgreicher State-Transition oder Disconnect.

**`onConnectionStateChange` ignorierte Status:** Ein `STATE_CONNECTED` mit non-success `status` wurde als erfolgreich behandelt. Fix: `status != GATT_SUCCESS` → `g.close()` + `state = IDLE`.

**Write-Dispatch-Failure im STOPPING-State:** Wenn der Stop-GPS-Write fehlschlug und die Queue leer war, blieb `state` auf STOPPING. Fix: In `pump()` bei Dispatch-Failure + STOPPING + leere Queue → sofort `doDisconnect()`.

### Lifecycle-Fixes (MEDIUM)

**FgScanner nicht gestartet nach Screen-Wechsel:** Beim Übergang von Permission/DevicePicker zu MainScreen startete FgScanner nicht, weil ON_RESUME schon gefeuert hatte. Fix: `DisposableEffect`-Body prüft beim Setup ob Lifecycle bereits RESUMED ist und führt die Scanner-Logik sofort aus.

**`resolveSelectedDevice()` im Composable-Body:** Side-Effect (Prefs-Write) bei jeder Recomposition. Fix: In `remember { ... }` Block verschoben — wird nur bei erster Komposition ausgeführt.

**Permission-Checks bei jedem Recompose:** `groups.filter { !it.isGranted(ctx) }` rief `checkSelfPermission` bei jeder Recomposition. Fix: `remember(refreshKey) { ... }`.

### Security-Hardening (LOW)

**BootReceiver:** Akzeptierte beliebige Broadcast-Actions (exported receiver). Fix: Action-Filter auf `BOOT_COMPLETED` und `MY_PACKAGE_REPLACED`.

**ScanResultReceiver:** Ein verspäteter PendingIntent von einer alten Registrierung konnte den Service für die falsche Kamera starten. Fix: MAC-Check gegen `Prefs.selectedDeviceMac`.

### Code-Organisation

**MainActivity.kt aufgeteilt:** 779 Zeilen → 4 Dateien:
- `MainActivity.kt` (~100 Z.) — Activity + Screen-Router
- `MainScreen.kt` (~390 Z.) — Hauptbildschirm + UI-Composables + Farb/Format-Helfer
- `PermissionFlow.kt` (~200 Z.) — Onboarding-Flow + Permission-Gruppen
- `DevicePicker.kt` (~150 Z.) — Kamera-Auswahl

Visibility: `private` → `internal` für cross-file Composables/Funktionen.

### DRY / Cleanup

- **Scan-Filter-Builder extrahiert:** `CanonAd.scanFilter(mac, awakeOnly)` — gemeinsam genutzt von `FgScanner` und `CanonScanRegistrar`
- **Tote UUID-Konstanten entfernt:** `PAIRING_REQUEST`, `PAIRING_RESPONSE`, `HANDOVER_STATE`
- **`log()`-Wrapper entfernt:** Alle Aufrufe durch direktes `Log.i(TAG, ...)` ersetzt
- **UI-Labels auf Deutsch vereinheitlicht:** „Connection" → „Verbindung", „GPS Session" → „GPS-Sitzung", „CAMERA" → „KAMERA", Key-Value-Labels großgeschrieben
- **`@SuppressLint("MissingPermission")`** in FgScanner von Klassen- auf Methodenebene verschoben
- **`findAllBondedCanon` robuster:** Filtert jetzt auf `"EOS"` oder `"Canon"` im BT-Namen, plus bereits konfigurierte Kamera (via `selectedDeviceMac`) wird immer einbezogen

### Tests + Build

- **11 Unit-Tests** für `CanonGpsFrame.encode()`: N/S/E/W-Bits, Float-LE-Encoding, NaN-Altitude, Referenzframe (Berlin Brandenburger Tor). Neues Testverzeichnis `src/test/`, JUnit 4.13.2 als Dependency.
- **`proguard-rules.pro` vorbereitet:** Keep-Regeln für Manifest-Komponenten, BLE-Callbacks, Play Services. `isMinifyEnabled` bleibt `false` (kein R8 ohne Testsuite).

### Nicht gefixt (mit Begründung)

- `trackingEnabled` Default `true` — bewusste Design-Entscheidung, dokumentiert
- Keine `@Preview`-Funktionen — Aufwand/Nutzen für Ein-Personen-Projekt zu gering
- R8 nicht aktiviert — ohne vollständige Testsuite riskant

### Stand nach Phase 11

Codebasis ist jetzt sauber strukturiert (15 Dateien statt monolithischer MainActivity), GATT-State-Machine ist robust gegen Hänger und Doppel-Teardowns, Security-Basics sind abgedeckt, und der GPS-Frame-Encoder hat eine verifizierte Testsuite. Alle 22 Review-Findings sind bearbeitet (19 gefixt, 3 bewusst nicht gefixt mit Begründung).

### GPS-Fix-Details in der UI

GPS-Sitzung-Card zeigt jetzt unter den Koordinaten eine kompakte Subzeile mit Fix-Metadaten aus dem `FusedLocationProvider`:

```
Letzt.    49,789685, 9,942761
          vor 0:05 · ±12 m · 245 m · 3 km/h
```

Felder: Alter des Fixes, horizontale Genauigkeit (`loc.accuracy`), Höhe ü. NN (`loc.altitude`), Geschwindigkeit (`loc.speed` → km/h). Satellitenzahl wurde evaluiert aber verworfen — `FusedLocationProvider` liefert sie nicht zuverlässig (`extras.getInt("satellites")` ist fast immer 0 oder absent).

Das "Letzt."-Label ist `alignTop = true` damit es bei der zweizeiligen Value oben steht statt mittig.

## Phase 12 — Schnellerer Handshake + Sofort-Fix (19. April 2026)

### Ausgangsproblem

Beim Session-Cycling (bekannter offener Punkt: ~2s GPS-Session, dann `status=8` Disconnect, 7–11s Pause, Reconnect, Wiederholung) war die Wahrscheinlichkeit hoch, ein Foto ohne GPS zu schießen. In der kurzen 2s-Session musste erst der ~1.5s Handshake durch, dann 5–10s auf den ersten FusedLocationProvider-Callback warten — oft kam der Fix erst nach dem nächsten Disconnect. Durch schnelleren Handshake und Sofort-Fix aus dem Location-Cache sinkt die Wahrscheinlichkeit, dass ein Foto in eine GPS-Lücke fällt.

### Sofort-Fix aus Location-Cache

Neue Methode `sendLastLocationIfFresh()` in `GpsTrackingService`: bei `READY_TO_RECEIVE` wird `fused.lastLocation` abgefragt. Wenn der System-Cache einen Fix hat der ≤60s alt ist, wird er sofort gesendet — noch bevor der erste reguläre Location-Callback kommt. Kein eigenes Caching nötig, der FusedLocationProvider hat einen systemweiten Cache.

### GATT-Handshake-Optimierung

Beim Analysieren des Handshake-Logs fielen drei Zeitfresser auf:

**1. `initialNotifyByte`-Fallback entfernt.** Dieser Fallback las beim Connect den initialen Wert von GPS_NOTIFY (`00040003`), und wenn er `0x02` war (= "ready" aus vorheriger Session gecacht), wurde nach dem letzten Kickoff-Write sofort `READY_TO_RECEIVE` gesetzt — ohne auf die echte Indication zu warten. Problem: die echte Indication kam zuverlässig ~13ms nach dem Write-Callback. Der Fallback feuerte vor der Indication (Race Condition) und verursachte ein doppeltes `READY_TO_RECEIVE`. Die Annahme "Kamera schickt keine frische Indication wenn sie schon ready war" war falsch — sie schickt immer eine. Der 10s-Timeout (`REQUESTING_GPS`) ist der korrekte Fallback für den Fall dass die Indication wirklich ausbleibt.

**2. Initiale Reads entfernt.** STATUS-Read (`00040001`) und NOTIFY-Read (`00040003`) nach Service Discovery kosteten ~120ms. STATUS persistiert über Power-Off und war nie ein zuverlässiges Ready-Signal (dokumentiert seit Phase 5). NOTIFY-Read war nur Input für den entfernten Fallback. Beide Werte wurden nur geloggt, keine Logik hing dran.

**3. 7 von 8 CCCD-Subscriptions entfernt.** Canon's App subscribed 8 Channels (GPS_NOTIFY, HANDOVER_DATA, HANDOVER_NOTIFY, 5× REMOTE_*). Jede Subscription ist ein BLE-Roundtrip (~90ms). Für GPS-Fixes und Shutter-Writes ist nur die GPS_NOTIFY-Subscription nötig — die anderen empfangen eingehende Notifications die wir nicht verarbeiten. Zeitersparnis: ~630ms.

### Ergebnis

| Metrik | Vorher | Nachher |
|--------|--------|---------|
| CCCD-Subscriptions | 8 (~720ms) | 1 (~90ms) |
| Initiale Reads | 2 (~120ms) | 0 |
| Connect → erster GPS-Fix | ~1.5s + 5–10s Wartezeit | ~550ms + sofort aus Cache |
| `READY_TO_RECEIVE`-Events pro Connect | 2 (Race) | 1 |

### Entfernte Komponenten (Referenz für Rollback)

Falls sich etwas als problematisch herausstellt:

| Komponente | Was sie tat | UUID / Ort | Warum entfernt |
|---|---|---|---|
| `initialNotifyByte` (Feld + Fallback) | Cached initialen NOTIFY-Wert, setzte READY_TO_RECEIVE im Write-Callback wenn Byte=2 | `CanonGattClient.kt` | Race Condition: feuerte vor echter Indication |
| STATUS-Read | Las `00040001` nach Service Discovery, loggte Byte | `00040001` | Rein diagnostisch, persistiert über Power-Off |
| NOTIFY-Read | Las `00040003` nach Service Discovery, fütterte `initialNotifyByte` | `00040003` | Input für entfernten Fallback |
| CCCD HANDOVER_DATA | Subscription für Notifications auf `00020002` | `00020002` NOTIFY `0x0100` | Keine eingehenden Notifications verarbeitet |
| CCCD HANDOVER_NOTIFY | Subscription für Indications auf `00020003` | `00020003` INDICATE `0x0200` | Keine eingehenden Notifications verarbeitet |
| CCCD REMOTE_STATUS | Subscription auf `00030001` | `00030001` NOTIFY `0x0100` | Für GPS+Shutter nicht nötig |
| CCCD REMOTE_EVENTS | Subscription auf `00030002` | `00030002` NOTIFY `0x0100` | Für GPS+Shutter nicht nötig |
| CCCD REMOTE_ZOOM | Subscription auf `00030011` | `00030011` NOTIFY `0x0100` | Für GPS+Shutter nicht nötig |
| CCCD REMOTE_EXPOSURE | Subscription auf `00030021` | `00030021` NOTIFY `0x0100` | Für GPS+Shutter nicht nötig |
| CCCD REMOTE_APERTURE | Subscription auf `00030031` | `00030031` NOTIFY `0x0100` | Für GPS+Shutter nicht nötig |

Ebenfalls entfernte UUID-Konstanten: `GPS_STATUS`, `HANDOVER_NOTIFY`, `REMOTE_STATUS`, `REMOTE_EVENTS`, `REMOTE_ZOOM`, `REMOTE_EXPOSURE`, `REMOTE_APERTURE`. Felder: `statusChar`, `handoverNotifyChar`.

## Phase 13 — Modernisierung & Strictness (25. April 2026)

### Build-System

- **Version Catalog** eingeführt (`gradle/libs.versions.toml`): alle Dependency-Versionen zentralisiert, `build.gradle.kts` nutzt `alias(libs.plugins.*)` und `libs.*` statt hardcoded Strings
- **SDK-Update**: compileSdk/targetSdk 36 → 37, minSdk 33 → 34, Java 17 → 21
- **Dependency-Updates**: AGP 9.1.0 → 9.2.0, Kotlin 2.3.20 → 2.3.21, Compose BOM 2026.03.00 → 2026.04.01, core-ktx → 1.18.0, activity-compose → 1.13.0, lifecycle-runtime-ktx → lifecycle-runtime-compose 2.10.0
- **Gradle** configuration-cache + caching aktiviert

### Striktere Compiler/Lint-Config

Kotlin-Compiler: `allWarningsAsErrors`, `progressiveMode`, `-Wextra`, `-Xjsr305=strict`.

Android Lint: `checkAllWarnings`, `checkReleaseBuilds`, `checkTestSources`, `checkGeneratedSources`, `warningsAsErrors`, `abortOnError`. Ergebnis: 51 Lint-Fehler gefunden und behoben.

### R8 Log-Stripping

`-assumenosideeffects` in `proguard-rules.pro` entfernt `Log.v/d/i/w` komplett aus dem Release-APK. Nur `Log.e` bleibt. `LogConditional`-Lint-Check disabled (redundant da R8 übernimmt).

### SyntheticAccessor-Fixes

35 `SyntheticAccessor`-Lint-Fehler: inner anonymous objects (GATT-Callback, ScanCallback, Runnable, LocationCallback) greifen auf `private` Members der äußeren Klasse zu → Kotlin erzeugt synthetische Accessor-Methoden. Fix: `private` → `internal` in CanonGattClient, FgScanner, GpsTrackingService. R8 optimiert die Accessors im Release ohnehin weg, der Lint-Check ist trotzdem sinnvoll als Bytecode-Hygiene.

### API-36-Bug: BluetoothGattConnectionSettings

`BluetoothGattConnectionSettings.Builder()` ist in den SDK-37-Stubs als public deklariert, aber auf dem Pixel 9a (API 36) auf der Hidden-API-Blocklist (`domain=platform, api=blocked`). Crash: `NoSuchMethodError`. Lint's `NewApi`-Check fängt das nicht, weil die Stubs die API als ab API 36 verfügbar annotieren — Inkonsistenz zwischen Compile-Stubs und Runtime.

Fix: Fallback auf die traditionelle `connectGatt(context, autoConnect, callback, transport, phy, handler)` Überladung (seit API 26). Die ist deprecated zugunsten der kaputten neuen API, daher `@Suppress("DEPRECATION")`.

### minSdk-34-Vereinfachungen

- `Build.VERSION.SDK_INT >= 34` Check in GpsTrackingService entfernt (immer true)
- `ContextCompat.startForegroundService` → `ctx.startForegroundService` (Compat-Wrapper war für API < 26)
- Obsoleten Kommentar über FLAG_MUTABLE "required on API 31+" entfernt

### Code-Review (6 Agenten parallel)

Drei Claude-Agenten (Code Reuse, Quality, Efficiency) und drei Codex-Agenten parallel gestartet. Konsens-Findings:

| Fix | Quelle |
|-----|--------|
| `shutterReady`-Duplikat → `gpsActive` wiederverwendet | Quality |
| Misleading KDoc auf `bluetoothAdapter()` entfernt | Quality |
| ON_RESUME-Setup-Duplikation in MainScreen → lokale `onResume()` | Quality |
| RSSI-Poll startet erst bei `GPS_SESSION_ACTIVE` statt bei `connect()` | Efficiency |
| `hasAllPerms()` nur 1× pro `currentScreen()`-Aufruf (enthält blocking `future.get()`) | Efficiency |
| KeyValueRow Lambda `value` → `content` (Compose-Konvention) | Lint |

*Letzte Aktualisierung: 2026-04-25 (Phase 13)*
