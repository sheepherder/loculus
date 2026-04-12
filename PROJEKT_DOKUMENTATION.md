# Canon EOS R6 Mark II - Automatisches GPS-Geotagging

## Projektübersicht

**Ziel:** Eine Lösung entwickeln, die automatisch GPS-Koordinaten an die Canon EOS R6 Mark II sendet, sobald die Kamera eingeschaltet und per Bluetooth erreichbar ist - ohne manuelles Starten der Canon Camera Connect App.

**Status:** Reverse Engineering abgeschlossen. Android-PoC-App funktioniert auf Pixel 9a — GPS-Übertragung zur Canon EOS R6 Mark II vollständig validiert (GPS-Icon leuchtet hell nach Write).

**Datum:** 2026-04-12

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

### GPS-Kommandos

Auf Characteristic `00040002` können folgende Kommandos gesendet werden:

| Byte(s) | Write-Type | Bedeutung | Quelle im Dekompilat |
|---------|-----------|-----------|----------------------|
| `0x01` | DEFAULT | GPS deaktivieren | `C0275o.I()` mit i5=1 |
| `0x02` | DEFAULT | GPS von Smartphone anfordern | `C0275o.I()` mit i5=2 |
| `0x03` | DEFAULT | GPS stoppen | `C0275o.I()` mit i5=3 |
| `0x04 <19 Byte binary>` | **NO_RESPONSE** | GPS-Fix senden (20 Byte binary format, siehe unten) | `d4.C0500A.G(Location)` |
| `0x05` | DEFAULT | Aktuelle GPS-Quelle abfragen (Response via NOTIFY) | `C0298u.a()` auto-probe |
| `0x06 0xNN 0x00*6` (8 Byte!) | DEFAULT | GPS-Quelle setzen: `0x00`=disable, `0x01`=externer Empfänger, `0x04`=Smartphone | `Z3/j.java:181-187` |

**Write-Type-Regeln:**
- Kommandos (`0x01`, `0x02`, `0x03`, `0x05`, `0x06 0xNN...`) → `WRITE_TYPE_DEFAULT` (mit Response)
- GPS-Fix (`0x04 + 19 Byte`) → `WRITE_TYPE_NO_RESPONSE` (keine Bestätigung)

**Wichtig zum Source-Set-Kommando:** Canon allociert `ByteBuffer.allocate(8)` und schreibt nur die ersten 2 Bytes — der Rest bleibt 0. Effektiv werden also **8 Byte** gesendet: `[0x06, src, 0, 0, 0, 0, 0, 0]`. Ob die Kamera mit nur 2 Byte glücklich ist, wurde nicht getestet — besser Canons Format spiegeln.

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

### Pairing-Protokoll

Canon verwendet ein proprietäres Pairing über GATT (nicht Standard Bluetooth Pairing):

1. BLE-Verbindung herstellen (ohne System-Pairing)
2. Notifications auf `0001000b` aktivieren
3. Pairing-Request senden: `0x03` + Gerätename (ASCII) auf `00010005`
4. Auf Kamera bestätigen
5. Response `0x02` = Erfolg

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

Kotlin/Compose-App auf Pixel 9a, nutzt die bestehende System-Pairing-Beziehung zwischen Pixel und Canon.

**Status (2026-04-12):**
- GATT Connect auf gebondete Canon: ✅
- Service-Discovery (Canon GPS Service `0004`): ✅
- Initial-State via READ 00040001 + READ 00040003: ✅
- CCCD-INDICATION-Subscribe auf 00040003 (`0x0200`): ✅
- Source-Query + Indikations-Response: ✅
- GPS-Frame-Write (20 Byte binary LE, WRITE_NO_RESPONSE): ✅
- Foreground Service + FusedLocationProvider Auto-Loop: ✅
- 60+ Sekunden kontinuierliche Session ohne Firmware-Crash: ✅
- Graceful Stop mit `{3}` + Disconnect: ✅
- **EXIF in Canon-Fotos identisch zu Canon-App-Output verifiziert**: ✅

**Build/Install:**
```bash
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n de.schaefer.eosgps/.MainActivity
```

**Toolchain (April 2026):** AGP 9.1.0, Kotlin 2.3.20, Gradle 9.4.1, Compose BOM 2026.03.00, compileSdk 36, minSdk 31.

**Offen:**
- [ ] Auto-Reconnect wenn Kamera aus/an geht (jetzt: manueller Restart)
- [ ] `{2}`-Path testen gegen eine frisch zurückgesetzte Kamera (bisher war READY_TO_RECEIVE schon aus vorheriger Canon-App-Session gesetzt)
- [ ] Optional: Pairing-Service-Handshake machen damit BT-Icon auf Kamera leuchtet (kosmetisch)
- [ ] Optional: auch Speed/Bearing aus Location übertragen — Format noch nicht bekannt

---

## Geplante Architektur

### Für Android (Zielplattform)

```
┌────────────────────────────────────────────────────────────┐
│                     CanonGPSSync App                        │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ┌──────────────────┐    ┌────────────────────────────┐   │
│  │ Foreground       │    │ BLE Scanner                │   │
│  │ Service          │───▶│ - Scan for Canon cameras   │   │
│  │ (mit Notification)│   │ - Filter: d8492fffa821     │   │
│  └──────────────────┘    └────────────────────────────┘   │
│           │                          │                     │
│           ▼                          ▼                     │
│  ┌──────────────────┐    ┌────────────────────────────┐   │
│  │ Location Provider │    │ BLE Connection Manager    │   │
│  │ - GPS updates     │───▶│ - GATT connect            │   │
│  │ - Fused Location  │    │ - Service discovery       │   │
│  └──────────────────┘    │ - Write characteristics   │   │
│                          └────────────────────────────┘   │
│                                      │                     │
│                                      ▼                     │
│                          ┌────────────────────────────┐   │
│                          │ NMEA Generator             │   │
│                          │ - GPGGA + GPRMC            │   │
│                          │ - Checksum calculation     │   │
│                          └────────────────────────────┘   │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### Ablauf

1. **App startet als Foreground Service** (überlebt Battery Optimization)
2. **Periodischer BLE-Scan** nach Canon Service UUID
3. **Kamera erkannt** → GATT-Verbindung herstellen
4. **GPS aktivieren** → Write `0x06 0x04` auf `00040002`
5. **GPS-Daten senden** → NMEA-Sentences auf `00040002` (alle 1-10 Sekunden)
6. **Verbindung verloren** → Zurück zu Schritt 2

### Wichtig für Android

- Die App kann die **bestehenden Pairing-Credentials** der Canon Camera Connect App nutzen
- Das Bluetooth-Pairing wird vom **Android-System verwaltet**, nicht von einzelnen Apps
- Kein erneutes Pairing nötig, wenn Kamera bereits mit dem Handy gepairt ist

---

## Nächste Schritte

### Kurzfristig (nach neuem BT-Adapter)

1. [ ] Neuen Bluetooth-Adapter kaufen und installieren
2. [ ] `python canon_gps_poc.py pair` testen
3. [ ] `python canon_gps_poc.py send` testen
4. [ ] Protokoll mit nRF Connect App auf Handy verifizieren (BLE Sniffer)

### Mittelfristig

5. [ ] Python-Script auf Termux (Android) testen
6. [ ] Android-App mit Kotlin/Jetpack Compose entwickeln
7. [ ] Foreground Service implementieren
8. [ ] Automatische Reconnection implementieren

### Optional

- [ ] ESP32-basierte Hardware-Lösung (unabhängig vom Handy)
- [ ] iOS-App (falls gewünscht)

---

## Externe Ressourcen

### Dokumentation & Reverse Engineering

- [Ian Douglas Scott - Canon Bluetooth Protocol](https://iandouglasscott.com/2018/07/04/canon-dslr-bluetooth-remote-protocol/)
  - Reverse Engineering des Canon BLE Remote-Protokolls
  - Basis für Pairing-Verständnis

- [blackdot.be - GPS Tracking for Photography](https://www.blackdot.be/2025/02/photography-gps-tracking/)
  - Beschreibt das gleiche Problem
  - Workarounds mit GPX-Logging

### Open Source Projekte

| Projekt | Beschreibung | Link |
|---------|--------------|------|
| canoremote | Python BLE Remote für Canon | [pklaus/canoremote](https://github.com/pklaus/canoremote) |
| cannon-bluetooth-remote | Python BR-E1 Emulator | [ids1024/cannon-bluetooth-remote](https://github.com/ids1024/cannon-bluetooth-remote) |
| ESP32-Canon-BLE-Remote | ESP32-basierte Lösung | [maxmacstn/ESP32-Canon-BLE-Remote](https://github.com/maxmacstn/ESP32-Canon-BLE-Remote) |

**Hinweis:** Keines dieser Projekte implementiert GPS-Übertragung, nur Remote-Auslösung.

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

*Letzte Aktualisierung: 2026-04-12*
