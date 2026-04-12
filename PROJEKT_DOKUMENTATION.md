# Canon EOS R6 Mark II - Automatisches GPS-Geotagging

## Projektübersicht

**Ziel:** Eine Lösung entwickeln, die automatisch GPS-Koordinaten an die Canon EOS R6 Mark II sendet, sobald die Kamera eingeschaltet und per Bluetooth erreichbar ist - ohne manuelles Starten der Canon Camera Connect App.

**Status:** Reverse Engineering abgeschlossen, Proof-of-Concept Script erstellt, Hardware-Problem mit BLE-Adapter identifiziert.

**Datum:** 2026-01-31

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

| Byte(s) | Bedeutung |
|---------|-----------|
| `0x01` | GPS deaktivieren |
| `0x02` | GPS von Smartphone anfordern |
| `0x03` | GPS stoppen |
| `0x05` | Aktuelle GPS-Einstellung abfragen |
| `0x06 0x00` | GPS-Quelle: Deaktiviert |
| `0x06 0x01` | GPS-Quelle: Externer GPS-Empfänger |
| `0x06 0x04` | GPS-Quelle: Smartphone |

### GPS-Datenformat

Die GPS-Daten werden als **Standard NMEA-0183 Sentences** übertragen:

```
$GPGGA,HHMMSS.000,DDMM.MMMMM,N,DDDMM.MMMMM,E,1,6,0,ALT,M,0,M,,*XX\r\n
$GPRMC,HHMMSS.000,A,DDMM.MMMMM,N,DDDMM.MMMMM,E,SPEED,BEARING,DDMMYY,0,A*XX\r\n
```

#### GPGGA Format (Position Fix)
```
$GPGGA,<time>,<lat>,<N/S>,<lon>,<E/W>,<quality>,<sats>,<hdop>,<alt>,M,<geoid>,M,,*<checksum>
```

- `time`: UTC Zeit als HHMMSS.000
- `lat`: Breitengrad als DDMM.MMMMM (Grad + Minuten)
- `lon`: Längengrad als DDDMM.MMMMM (Grad + Minuten)
- `quality`: 1 = GPS Fix
- `sats`: Anzahl Satelliten (z.B. 6)
- `alt`: Höhe in Metern
- `checksum`: XOR aller Bytes zwischen $ und *

#### GPRMC Format (Recommended Minimum)
```
$GPRMC,<time>,A,<lat>,<N/S>,<lon>,<E/W>,<speed>,<bearing>,<date>,<var>,A*<checksum>
```

- `A`: Status Valid
- `speed`: Geschwindigkeit in Knoten
- `bearing`: Kurs in Grad
- `date`: Datum als DDMMYY

#### Checksum-Berechnung
```python
def calculate_nmea_checksum(sentence: str) -> str:
    checksum = 0
    for char in sentence:
        checksum ^= ord(char)
    return f"{checksum:02X}"
```

#### Beispiel
Für Berlin Brandenburger Tor (52.516275°N, 13.377704°E, 34m):
```
$GPGGA,094837.000,5230.97650,N,01322.66224,E,1,6,0,34.0,M,0,M,,*63
$GPRMC,094837.000,A,5230.97650,N,01322.66224,E,0.0,0.0,310126,0,A*7E
```

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
- BLE-Connect: ❌ Scheitert am CSR-Adapter
- Pairing: ❌ Nicht getestet (Adapter-Problem)
- GPS-Senden: ❌ Nicht getestet (Adapter-Problem)

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

*Letzte Aktualisierung: 2026-01-31*
