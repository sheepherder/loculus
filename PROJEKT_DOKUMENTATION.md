# Canon EOS R6 Mark II - Automatisches GPS-Geotagging

## Projektübersicht

**Ziel:** Eine Lösung entwickeln, die automatisch GPS-Koordinaten an die Canon EOS R6 Mark II sendet, sobald die Kamera eingeschaltet und per Bluetooth erreichbar ist - ohne manuelles Starten der Canon Camera Connect App.

**Status:** Android-App produktiv. Zwei-stufige Scan-Architektur: OS-offloaded PendingIntent-Scan im Idle-Modus (kein Foreground-Service nötig, <0,5 %/Tag Batterie), direkter ScanCallback nur während aktiver Session und 30s Reconnect-Grace. Auto-Start nach Boot und App-Update. Auto-Reconnect bei Kamera-Einschalten / Rückkehr in Reichweite, keine Sensor-Weckgeräusche bei schlafender Kamera. GPS-Übertragung zur Canon EOS R6 Mark II vollständig validiert, EXIF-Output identisch zur Canon-App.

**Datum:** 2026-04-17

---

## Problem mit aktuellem Setup

### Bluetooth-Adapter
- **Modell:** Cambridge Silicon Radio (CSR) - USB ID `0a12:0001`
- **Problem:** Dieser Adapter ist bekannt für schlechte BLE-Unterstützung
- **Symptom:** `le-connection-abort-by-local` Fehler bei BLE-Verbindungsversuchen
- **Lösung:** Besseren USB-Bluetooth-Adapter kaufen

### Empfohlene Adapter
- **Intel AX200/AX210** basierte USB-Adapter
- **Realtek RTL8761B** basierte Adapter
- **TP-Link UB500** (verwendet Realtek Chip, ~15€)
- **ASUS USB-BT500** (Realtek RTL8761B, ~20€)

---

## Reverse Engineering Ergebnisse

### Canon BLE Protokoll

Die Canon Camera Connect App wurde dekompiliert und analysiert. Folgende Erkenntnisse:

#### UUID-Basis
Canon verwendet eine proprietäre UUID-Basis: `d8492fffa821`

Alle Canon-spezifischen Services/Characteristics folgen dem Muster:
```
0000XXXX-0000-1000-0000-d8492fffa821
```

#### GATT Services

| Service | UUID | Funktion |
|---------|------|----------|
| 0001 | `00010000-0000-1000-0000-d8492fffa821` | Pairing/Connection |
| 0002 | `00020000-0000-1000-0000-d8492fffa821` | Kamera-Info |
| 0003 | `00030000-0000-1000-0000-d8492fffa821` | Remote Control |
| **0004** | `00040000-0000-1000-0000-d8492fffa821` | **GPS Service** |
| 0008 | `00080000-0000-1000-0000-d8492fffa821` | Livestream |

#### Pairing Service (0001) - Characteristics

| Characteristic | UUID | Eigenschaften | Funktion |
|----------------|------|---------------|----------|
| Request | `00010005-0000-1000-0000-d8492fffa821` | Write | Pairing-Request senden |
| Data | `0001000a-0000-1000-0000-d8492fffa821` | Write | Geräte-Info senden |
| Response | `0001000b-0000-1000-0000-d8492fffa821` | Notify | Pairing-Status empfangen |
| Name | `00010006-0000-1000-0000-d8492fffa821` | Read/Write | Gerätename |

#### GPS Service (0004) - Characteristics

| Characteristic | UUID | Eigenschaften | Funktion |
|----------------|------|---------------|----------|
| Status | `00040001-0000-1000-0000-d8492fffa821` | Read | GPS-Status lesen |
| **Data** | `00040002-0000-1000-0000-d8492fffa821` | **Write** | **GPS-Daten senden** |
| Notify | `00040003-0000-1000-0000-d8492fffa821` | Notify | GPS-Notifications |

### Advertisement-basiertes Power-State-Signal

Die R6m2 advertiset im Standby-Modus mit einer **anderen Advertising-Rate und einem State-Byte** als im aktiven Modus. Beide Signale sind unabhängig voneinander beobachtbar durch reines Scannen — kein Connect, kein ATT-Write, kein Wake-Risiko.

**Manufacturer Specific Data** unter Company-ID `0x01A9` (Bluetooth SIG assigned zu **Canon Inc.** — nicht zu verwechseln mit dem Murata-Eintrag der MAC-OID, siehe unten):

```
Byte 0..4   konstant  01 0b 33 f3 4b   (vermutlich Protokoll-Version + Modellfamilie-Code,
                                         unverifiziert ohne Vergleich mit anderem Modell)
Byte 5      variabel  0x02 = AWAKE (Kamera voll eingeschaltet)
                       0x05 = ASLEEP (Kamera in BLE-Standby)
                       anderes → konservativ als ASLEEP behandeln
```

**MAC-OID vs. Company-ID**: die R6m2-MAC `34:90:EA:…` liegt im **Murata**-OUI-Bereich (IEEE-Registry — Murata ist der Hersteller des BLE-Moduls), die Advertisement-Company-ID `0x01A9` gehört aber **Canon Inc.** (Bluetooth-SIG-Registry — Canon ist Protokoll-Eigner). Beides gleichzeitig im selben Gerät ist üblich und kein Widerspruch — die zwei Registries addressieren unterschiedliche Schichten.

Zusätzliche Signatur (redundant, aber stabil):
- Awake: Advertisement-Rate ~200ms (5–6 Ads/s)
- Asleep: Advertisement-Rate ~3s

**Konsequenz für die Logik**: wir connecten ausschließlich wenn ein frisches Advertisement Byte 5 = `0x02` zeigt. Schlafende Kameras bleiben unberührt.

Android-API: `ScanResult.scanRecord.getManufacturerSpecificData(0x01A9)` liefert die 6 Bytes *nach* der Company-ID.

### Connect- und Kickoff-Sequenz

Verifiziert via HCI-Snoop der offiziellen Canon Camera Connect App, mehrfach reproduziert auf R6m2:

1. **CCCD-Subscribes** (8 Channels, in dieser Reihenfolge spielt's keine Rolle):

   | UUID | Char | CCCD |
   |------|------|------|
   | `00040003` | GPS NOTIFY | `02 00` INDICATE |
   | `00020002` | HANDOVER DATA | `01 00` NOTIFY |
   | `00020003` | HANDOVER NOTIFY | `02 00` INDICATE |
   | `00030001` | Remote STATUS | `01 00` NOTIFY |
   | `00030002` | Remote EVENTS | `01 00` NOTIFY |
   | `00030011` | Remote ZOOM | `01 00` NOTIFY |
   | `00030021` | Remote EXPOSURE | `01 00` NOTIFY |
   | `00030031` | Remote APERTURE | `01 00` NOTIFY |

2. **Write `0x0a` auf `00020002`** (HANDOVER DATA, WRITE_TYPE_DEFAULT). Kamera antwortet mit einer Notification auf derselben Char mit ~20-Byte-Payload (vermutlich Capability-Handshake).

3. **Write `0x05 00 00 00 00 00 00 00` auf `00040002`** (GPS DATA, Source-Query, 8 Byte). Kamera antwortet via Indication auf `00040003` mit `05 xx` wobei `xx` die aktive Source ist (`04` = Smartphone).

4. **Write `0x01` auf `0001000a`** (PAIRING DATA, WRITE_TYPE_DEFAULT). Dies ist der tatsächliche Trigger für die Ready-Indication. Nebeneffekt: das **BT-Symbol** auf dem Kamera-Display leuchtet blau.

5. **Indication `02 00` auf `00040003`** (GPS NOTIFY) = Kamera ist bereit GPS-Frames zu empfangen.

6. Ab jetzt: **`0x04 + 19 Byte GPS-Fix` auf `00040002`** mit WRITE_TYPE_NO_RESPONSE, Rate Canon-seitig ~15s, wir machen 10s.

**Initial-NOTIFY-Fallback**: Wenn wir bei einer schon warmen Kamera reconnecten (Kamera war in derselben Sitzung bereits ready), schickt sie keine frische `02 00`-Indication weil sich aus ihrer Sicht nichts geändert hat. Wir merken uns deshalb beim ersten Read von `00040003` das Byte 0 — ist es bereits `0x02` und nach dem Pairing-Write kommt keine Indication, nehmen wir das als Ready und gehen in `GPS_SESSION_ACTIVE`.

### GPS-Kommandos auf `00040002`

| Byte(s) | Write-Type | Bedeutung | Quelle im Dekompilat |
|---------|-----------|-----------|----------------------|
| `0x01` | DEFAULT | GPS deaktivieren | `C0275o.I()` mit i5=1 |
| `0x02` | DEFAULT | GPS von Smartphone anfordern | `C0275o.I()` mit i5=2 |
| `0x03` | DEFAULT | GPS stoppen (Session-Ende, vor Disconnect) | `C0275o.I()` mit i5=3 |
| `0x04 <19 Byte binary>` | **NO_RESPONSE** | GPS-Fix senden (20 Byte binary format, siehe unten) | `d4.C0500A.G(Location)` |
| `0x05 <7 Byte 0>` | DEFAULT | Source-Query (Teil des Kickoffs, siehe oben) | `C0298u.a()` |
| `0x06 0xNN 0x00*6` | DEFAULT | GPS-Quelle setzen: `0x00`=disable, `0x01`=externer Empfänger, `0x04`=Smartphone | `Z3/j.java:181-187` |

**Write-Type-Regeln:**
- Kommando-Writes → `WRITE_TYPE_DEFAULT` (mit Response)
- GPS-Fix (`0x04 + 19 Byte`) → `WRITE_TYPE_NO_RESPONSE` (keine Bestätigung)

### GPS-Datenformat

**Nicht NMEA.** Canon überträgt GPS-Fixes als **kompaktes 20-Byte-Binärframe**, das in eine einzige BLE-PDU der Standard-MTU (23 Byte) passt. Keine MTU-Verhandlung nötig.

```
Offset  Size   Inhalt
------  ----   ----------------------------------------------
0       1      Kommando-Byte 0x04
1       1      N/S-Indicator ('N'=0x4E oder 'S'=0x53)
2       4      abs(latitude)  als IEEE 754 float32 LITTLE-ENDIAN
6       1      E/W-Indicator ('E'=0x45 oder 'W'=0x57)
7       4      abs(longitude) als float32 LITTLE-ENDIAN
11      1      alt-Sign ('+'=0x2B, '-'=0x2D, oder 0x00 wenn NaN)
12      4      abs(altitude)  als float32 LITTLE-ENDIAN (0.0 wenn NaN)
16      4      Unix-Timestamp in Sekunden als int32 LITTLE-ENDIAN
------
20 Byte total
```

**Byte-Order kritisch**: Canon nutzt LITTLE-ENDIAN. Das Dekompilat verwendet `ByteBuffer.allocate(4).putFloat(...)` ohne explizite Order-Setzung — Java-Default ist Big-Endian, aber Canon konfiguriert die Order irgendwo über einen Pfad den jadx übersprungen hat. Verifiziert via HCI-Snoop-Log: die Bytes auf dem Wire sind LE.

Dieses Frame wird mit **WRITE_TYPE_NO_RESPONSE** auf die Data-Characteristic (`00040002`) geschrieben. Die Kamera schickt **keine Bestätigung** — fire and forget.

Quelle: dekompilierte Canon-App `d4.C0500A.G(Location)` (Methode musste mit `jadx --show-bad-code` re-dekompiliert werden, weil der Standard-Decompile den Methoden-Body übersprungen hat).

#### Beispiel
Für Berlin Brandenburger Tor (52.516275°N, 13.377704°E, 34m, t=1712926800):
```
04 4E 42 52 21 3A 45 41 55 30 7E 2B 42 08 00 00 66 18 C3 50
│  │  └─────┬──────┘ │  └─────┬──────┘ │  └─────┬──────┘ └─────┬──────┘
│  │       lat       │      lon        │      alt        unix time
│  N                 E                  +
Cmd
```

Encoder-Code: siehe `android/app/src/main/java/de/schaefer/eosgps/CanonGpsFrame.kt`.

**Warum das wichtig ist:** Unsere frühen Versuche mit NMEA-Text-Frames (~140 Byte pro Update) haben die Kamera-Firmware mehrfach gecrashed — die Bytes nach dem `0x04`-Kommando wurden vom Parser als binärer Payload interpretiert, und die falschen Werte brachten die GPS-State-Machine in einen Zustand, aus dem sie nicht mehr rauskam (Busy-Lock, Reboot). Siehe `ENTWICKLUNGSVERLAUF.md` für die Story.

### Pairing-Protokoll (Erst-Pairing)

Canon verwendet ein proprietäres Pairing über GATT (nicht Standard Bluetooth Pairing). Für das **initiale** Pairing (vom User über die Canon-App gemacht):

1. BLE-Verbindung herstellen (ohne System-Pairing)
2. Notifications auf `0001000b` aktivieren
3. Pairing-Request senden: `0x03` + Gerätename (ASCII) auf `00010005`
4. Auf Kamera bestätigen
5. Response `0x02` = Erfolg

**Wichtig**: dieses Canon-Pairing installiert **keine SMP-Keys** im Android-Bond-Store. Der Bond-Record existiert, aber ohne Encryption-Keys. Konsequenz: verschlüsselte Standard-Chars wie DIS (`0x180a`) lassen sich nicht lesen — Android's automatische Encryption-Escalation scheitert und bricht die Verbindung ab (HCI 0x3D, „MAC Connection Failed"). DIS bleibt für uns tabu; Modell/Firmware-Informationen sind über BLE nicht zugänglich.

Für die hier beschriebene Use-Case (unsere App nutzt existierende Bond-Beziehung) ist das Pairing-Protokoll nicht nötig — Canon-App hat die Bond bereits eingerichtet, wir erben sie.

---

## Kamera-Informationen

- **Modell:** Canon EOS R6 Mark II
- **Bluetooth-Name:** `EOSR6m2_7BC192`
- **MAC-Adresse:** `34:90:EA:7B:C1:93`
- **Bereits gepairt mit:** Pixel 9a (über Canon Camera Connect App)

---

## Entwickelte Software

### Dateien

```
/home/schaefer/code/eos-gps/
├── canon_gps_poc.py          # Python Proof-of-Concept Script
├── PROJEKT_DOKUMENTATION.md  # Diese Datei
├── ANALYSE_UND_PLAN.md       # Erste Analyse (kürzer)
└── apk/                      # Dekompilierte Canon App
    ├── CameraConnect-base.apk
    ├── CameraConnect-arm64.apk
    ├── CameraConnect-de.apk
    ├── CameraConnect-xxhdpi.apk
    └── CameraConnect-decompiled/  # jadx Output
```

### canon_gps_poc.py

Python-Script mit folgenden Funktionen:

```bash
python canon_gps_poc.py scan      # Nach Canon-Kameras scannen
python canon_gps_poc.py pair      # Canon-spezifisches Pairing durchführen
python canon_gps_poc.py connect   # Verbinden und Services auflisten
python canon_gps_poc.py send      # GPS-Testdaten senden
python canon_gps_poc.py test      # NMEA-Generierung offline testen
```

**Abhängigkeiten:**
```bash
# Arch Linux
sudo pacman -S python-bleak

# Andere Systeme
pip install bleak
```

**Status:**
- NMEA-Generierung: ✅ Funktioniert
- BLE-Scan: ✅ Funktioniert (findet Kamera)
- BLE-Connect: ❌ Scheitert am CSR-Adapter (Linux-Host)
- Pairing: ❌ Nicht getestet (Adapter-Problem)
- GPS-Senden: ❌ Nicht getestet (Adapter-Problem)

**Hinweis:** Das Python-Script wird aktuell nicht mehr weiterentwickelt — die Entwicklung läuft über die Android-App (`android/`), die auf dem Pixel 9a das CSR-Problem komplett umgeht und bereits funktioniert.

### Android-App (`android/`)

Kotlin/Compose-App auf Pixel 9a, nutzt die bestehende System-Pairing-Beziehung zwischen Pixel und Canon. **Scan-First-Architektur** — der Foreground Service scannt passiv auf Canon-Advertisements und connected nur wenn das Power-State-Byte awake meldet.

**Status (2026-04-17):**
- OS-offloaded PendingIntent-Scan für Idle-Detection (byte5 low-3-bits gefiltert im HW): ✅
- Interner ScanCallback nur während aktiver Session + 30s Reconnect-Grace (HW-Filter auf Company-ID): ✅
- Auto-Start nach Boot, Package-Update, und App-Update: ✅
- Power-State aus Advertisement parsen (Byte 5 unter Company-ID `0x01A9`, low-3-bit-Logik): ✅
- Auto-Connect bei Awake-Advertisement: ✅
- Canon-Kickoff-Sequenz (8 CCCDs + `0x0a`-Handover + Source-Query + `0x01`-Pairing): ✅
- BT-Icon auf Kamera leuchtet blau (durch Pairing-`01`-Write): ✅
- Initial-NOTIFY-Fallback für bereits-ready-Kameras: ✅
- GPS-Frame-Write (20 Byte binary LE, WRITE_NO_RESPONSE): ✅
- Graceful Stop mit `{3}` + Disconnect: ✅
- FusedLocationProvider Auto-Loop (10s-Rate), funktioniert auch bei geschlossener Activity durch FGS-Typ `location|connectedDevice` + `ACCESS_BACKGROUND_LOCATION` Exemption: ✅
- Auto-Reconnect bei Kamera-Einschalten / Out-of-Range / Battery-Pull-Recovery: ✅
- Re-Arm des OS-Scans beim Handoff (Edge-State-Reset): ✅
- 2-Min-Hard-Cap auf internen Scan (gegen Android-30-Min-Throttling): ✅
- Advertisement-Staleness-Watchdog → `UNSEEN` nach 8s Funkstille: ✅
- Live-UI: Power/Link/GPS-State, RSSI, Session-Uptime, Fix-Count + Rate, Error-Count, Auto-Start-Toggle, Battery-Opt- und Background-Location-Prompts: ✅
- EXIF in Canon-Fotos identisch zu Canon-App-Output verifiziert: ✅

**Architekturübersicht:**

```
┌──────────── IDLE (App-Prozess schläft) ─────────────────────┐
│                                                             │
│   BootReceiver ─► CanonScanRegistrar.register(ctx)         │
│                             │                               │
│                             ▼                               │
│   BluetoothLeScanner.startScan(filter, pendingIntent)       │
│   HW-Filter: Canon mfg id 0x01A9 + byte5 low3 = 010         │
│   Läuft im System-Prozess, kein FGS nötig, kein 30min-Limit │
│                             │                               │
│   ─── Kamera geht an → erste awake-Ad ───                   │
│                             │                               │
│                             ▼                               │
│   ScanResultReceiver.onReceive()                            │
│   Verifiziert MAC im Code, startForAwakeAd()                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────── AKTIV (GpsTrackingService FGS) ────────────────────┐
│                                                             │
│   startTracking(awakeHint=true)                             │
│   FGS-Typ: connectedDevice + (location wenn BG-Perm)        │
│   Interner BluetoothLeScanner (HW-Filter: Canon mfg id)     │
│   2-Min-Hard-Cap gegen Android-30-Min-Drosselung            │
│                             │                               │
│                             ▼                               │
│   handleAdvertisement() — MAC + byte5 → AWAKE state         │
│                             │                               │
│                             ▼                               │
│   CanonGattClient ── state machine ──► GPS_SESSION_ACTIVE   │
│                             │                               │
│                             ▼                               │
│   FusedLocation → sendGps(20B Frame, NO_RESPONSE)           │
│                                                             │
│   onGattDisconnected() → interner Scan (30s Reconnect-Grace)│
│     └─ Kamera kehrt zurück: sofortiger Reconnect            │
│     └─ Timeout: unregister + register (Edge-Reset) → exit   │
│                                                             │
│   staleness watchdog ──► UNSEEN nach 8s ohne Ad             │
└─────────────────────────────────────────────────────────────┘
```

**Filter-Matrix:**

|                    | HW-Filter                              | Code-Filter         |
|--------------------|----------------------------------------|---------------------|
| Idle (OS-Scan)     | MAC + Canon mfg id + byte5 low3 awake  | —                   |
| Aktiv (intern)     | MAC + Canon mfg id                     | byte5 → State       |

Idle-Stage bekommt nur Awake-Ads der eigenen Kamera, Service wird nur für echte Wake-Events aufgeweckt. Aktiver Stage sieht beide Power-States (awake/asleep) für UI und Staleness-Watchdog. Konstanten gemeinsam in `CanonAd.kt`. Die MAC-Filterung findet in beiden Pfaden in Hardware statt — `ScanFilter.setDeviceAddress(String)` funktioniert für Canons public MAC sauber, der ältere Phase-6-Verdacht dass die 1-arg-Variante silently suppress hätte, war ein Aberglaube.

**Build/Install:**
```bash
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n de.schaefer.eosgps/.MainActivity
```

**Toolchain (April 2026):** AGP 9.1.0, Kotlin 2.3.20, Gradle 9.4.1, Compose BOM 2026.03.00, compileSdk 36, minSdk 31.

**Kotlin-Dateien:**

```
android/app/src/main/java/de/schaefer/eosgps/
├── MainActivity.kt         Compose UI, Permission-Flow, Auto-Start-Toggle
├── GpsTrackingService.kt   Foreground Service, State-Machine
├── CanonGattClient.kt      GATT-Op-Queue, Canon-Kickoff
├── CanonGpsFrame.kt        20-Byte-Binär-Encoder
├── CanonAd.kt              Scan-Konstanten + powerStateFromByte()
├── CanonScanRegistrar.kt   OS-Scan-Registration mit HW-Filter + PendingIntent
├── ScanResultReceiver.kt   Broadcast-Receiver für HW-Filter-Matches
├── BootReceiver.kt         BOOT_COMPLETED / MY_PACKAGE_REPLACED → re-arm
├── Prefs.kt                SharedPreferences-Wrapper (autoStart, scanRegistered)
└── TrackingState.kt        StateFlows (Service ↔ UI)
```

**Bewusst nicht implementiert / offene TODOs:**
- [ ] Fernauslöser (`00030001` Write) — eigene Crash-Oberfläche, separates Feature
- [ ] Batterie / Shutter-Counter — nur via PTP-IP über WiFi, BLE liefert das nicht
- [ ] `{2}`-Path testen (frisch reset'd Kamera) — defensive Logik im Code, ungetestet
- [ ] DIS-Reads — bräuchte SMP-Key-Installation, HCI 0x3D Disconnect bei Encryption-Escalation
- [ ] Speed/Bearing im GPS-Frame — Canon speichert das nur lokal, nicht im 20-Byte-BLE-Format
- [ ] Setup-Flow / Multi-Camera — siehe `IDEEN_FUER_SPAETER.md`

---

## Externe Ressourcen

### Dokumentation & Reverse Engineering

- [Ian Douglas Scott — Canon Bluetooth Protocol (2018)](https://iandouglasscott.com/2018/07/04/canon-dslr-bluetooth-remote-protocol/)
  - Reverse Engineering des Canon-BLE-Remote-Protokolls auf EOS T7i
  - Dokumentiert das GATT-Pairing-Protokoll + Shutter-Service Bit-Flags
  - Basis für canoremote / ESP32-Canon-BLE-Remote / eos-remote-web
  - Nichts zu GPS, Advertisements oder R6m2-Quirks

- [gkoh/furble Issue #189 — Support Canon EOS GPS](https://github.com/gkoh/furble/issues/189)
  - HCI-Log-basierte Analyse des Canon-EOS-GPS-Protokolls (Jerroder/gkoh, 2025)
  - 20-Byte-Binärframe, INDICATE-CCCDs, `0x01`-Pairing-Trigger
  - Deckungsgleich mit unseren Phase-5-Ergebnissen, nur sechs Monate älter
  - Implementierung in `lib/furble/CanonEOSSmart.cpp` im selben Repo

- [blackdot.be — GPS Tracking for Photography (2025)](https://www.blackdot.be/2025/02/photography-gps-tracking/)
  - Beschreibt dasselbe Use-Case-Problem aus Fotografen-Sicht
  - Workarounds mit externem GPX-Logging (nicht BLE)

### Open Source Projekte

| Projekt | Sprache | GPS? | Relevanz |
|---------|---------|------|----------|
| [gkoh/furble](https://github.com/gkoh/furble) (Issue [#189](https://github.com/gkoh/furble/issues/189), PR #199) | C++/ESP32 | **Ja** | **Wichtigste Referenz für GPS-Protokoll.** Dokumentiert (Mai–Nov 2025) 20-Byte-GPS-Frame, INDICATE-CCCDs, `01`-Pairing-Trigger für Canon-EOS-Familie. Matcht Canons im Scan aber nur auf Service-UUID, kein Advertisement-Power-Byte. Focus/GPS-Constraint per Pairing-Mode dokumentiert. Kein R6m2-spezifisches Testing (nur R6). |
| [pklaus/canoremote](https://github.com/pklaus/canoremote) | Python | Nein | BLE-Remote (Shutter), MAC-per-CLI, kein Scan |
| [ids1024/cannon-bluetooth-remote](https://github.com/ids1024/cannon-bluetooth-remote) | Python | Nein | BR-E1-Emulator über `btgatt-client` |
| [maxmacstn/ESP32-Canon-BLE-Remote](https://github.com/maxmacstn/ESP32-Canon-BLE-Remote) | C++/ESP32 | Nein | Service-UUID-Match, kein Advertisement-Parsing |
| [RReverser/eos-remote-web](https://github.com/RReverser/eos-remote-web) | JS/WebBluetooth | Nein | Browser-basiert, nutzt Web-BT-`filters` |
| [iebyt/cbremote](https://github.com/iebyt/cbremote) | Java/Android | Nein | Frühe Android-Canon-Remote-App |

**Eigenbeitrag gegenüber dem Stand der Technik** (Stand April 2026):

- **Advertisement-basierte Power-Detection** auf Canon (Byte 5 der Mfg-Data unter `0x01A9`) — furble macht das Äquivalent für Sony, aber nicht Canon. Unser Fund erlaubt erstmals passive Scan-First-Architektur ohne Wake-Risiko bei schlafenden Kameras.
- **Handover-Service-Kickoff** (`0x0a`-Write auf `00020002`) — in keinem öffentlichen Projekt dokumentiert. Vermutlich nur bei App-Seiten nötig die eine bestehende Canon-App-Bond erben statt frisch zu pairen.
- **R6m2-spezifische Verifikation**: im furble-Thread hat `@Jerroder` auf R6 getestet und `@hijae` hat zu R6m2 nie zurückgemeldet. Wir sind die erste dokumentierte R6m2-BLE-GPS-Implementation außerhalb der offiziellen Canon-App.

### Tools

- **jadx** - APK Dekompiler (verwendet für Analyse)
- **bleak** - Python BLE Library
- **nRF Connect** - Android App für BLE-Debugging

---

## Dekompilierte App - Wichtige Dateien

Falls weitere Analyse nötig:

```
CameraConnect-decompiled/sources/
├── com/canon/eos/
│   ├── C0223b.java          # GATT Callback, Service Discovery
│   ├── C0275o.java          # BLE Camera, GPS Write
│   ├── C0298u.java          # GPS Service Handler
│   ├── C0239f.java          # BLE Scanner
│   └── EOSCore.java         # Core SDK
├── i4/
│   ├── j.java               # NMEA Generator (!)
│   ├── l.java               # GPS Log Parser
│   └── n.java               # GPS Tracking Manager
└── jp/co/canon/ic/cameraconnect/gps/
    ├── CCGpsBleActivity.java    # GPS BLE UI
    ├── CCGpsLogActivity.java    # GPS Logging UI
    └── CCGpsLogService.java     # GPS Background Service
```

---

## Kontakt & Notizen

- **Entwicklungsrechner:** jason (Arch Linux)
- **Kamera:** Canon EOS R6 Mark II
- **Handy:** Pixel 9a (für spätere Android-App)
- **Problem:** CSR Bluetooth-Adapter (`0a12:0001`) - muss ersetzt werden

---

*Letzte Aktualisierung: 2026-04-17*
