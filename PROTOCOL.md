# Canon EOS BLE Protocol Reference

Reverse-engineered protocol documentation for Bluetooth LE communication with Canon EOS cameras. Verified on the Canon EOS R6 Mark II; likely applicable to other Canon EOS models with BLE GPS support.

All findings were derived from HCI snoop log analysis and on-device testing.

## UUID Scheme

Canon uses a proprietary UUID base: `d8492fffa821`

All Canon-specific services and characteristics follow the pattern:
```
0000XXXX-0000-1000-0000-d8492fffa821
```

## GATT Services

| Service | UUID | Function |
|---------|------|----------|
| 0001 | `00010000-0000-1000-0000-d8492fffa821` | Pairing / Connection |
| 0002 | `00020000-0000-1000-0000-d8492fffa821` | Camera Info / Handover |
| 0003 | `00030000-0000-1000-0000-d8492fffa821` | Remote Control |
| **0004** | `00040000-0000-1000-0000-d8492fffa821` | **GPS Service** |
| 0008 | `00080000-0000-1000-0000-d8492fffa821` | Livestream |

### Pairing Service (0001) — Characteristics

| Characteristic | UUID | Properties | Function |
|----------------|------|------------|----------|
| Request | `00010005-…` | Write | Send pairing request |
| Data | `0001000a-…` | Write | Send device info |
| Response | `0001000b-…` | Notify | Receive pairing status |
| Name | `00010006-…` | Read/Write | Device name |

### GPS Service (0004) — Characteristics

| Characteristic | UUID | Properties | Function |
|----------------|------|------------|----------|
| Status | `00040001-…` | Read | GPS status |
| **Data** | `00040002-…` | **Write** | **GPS data + commands** |
| Notify | `00040003-…` | Notify | GPS indications |

---

## Advertisement-Based Power State Detection

Canon EOS cameras advertise continuously in both active and standby mode. The power state can be determined passively — no connection required, no risk of waking a sleeping camera.

**Manufacturer Specific Data** under Company ID `0x01A9` (Bluetooth SIG: Canon Inc.):

```
Byte 0..4   constant  01 0b 33 f3 4b   (protocol version / model family,
                                         unverified across models)
Byte 5      power state encoded in bits 1-2
```

### Power State Decoding (Byte 5)

The power state is encoded in **bits 1-2** of byte 5. Extract with: `(byte5 >> 1) & 0x03`

| Bits 1-2 | Value | Raw byte 5 (example) | State | Meaning |
|----------|-------|---------------------|-------|---------|
| `0b00` | 0 | `0x00` or `0x01` | AUTO_POWER_OFF | Camera auto-idled; connecting would wake it |
| `0b01` | 1 | `0x02` or `0x03` | **POWER_ON** | Camera fully powered — safe to connect |
| `0b10` | 2 | `0x04` or `0x05` | POWER_SW_OFF | Main power switch off (deep standby) |
| `0b11` | 3 | `0x06` or `0x07` | (unknown) | Not observed; treat as no state change |

Bit 0 of byte 5 is a separate flag named `flagA` in Canon's own code (`C0231d.java`). Its purpose is unknown — Canon's app reads it but does not act on it visibly. Observed values: `0` when POWER_ON (`0x02`), `1` when POWER_SW_OFF (`0x05`). We ignore it for power-state decoding. Bits 3-7 are unverified.

**Only connect when bits 1-2 = `0b01` (POWER_ON).** Connecting to AUTO_POWER_OFF cameras would wake them unnecessarily. POWER_SW_OFF cameras accept connections but cannot stream GPS.

### Hardware Scan Filter

To filter for awake cameras at the BLE scan level (no app wakeup for sleeping cameras):

```
Manufacturer data:  [0x00, 0x00, 0x00, 0x00, 0x00, 0x02]   (byte 5 = 0x02)
Mask:               [0x00, 0x00, 0x00, 0x00, 0x00, 0x06]   (check only bits 1-2)
```

The mask `0x06` ensures only bits 1-2 of byte 5 are matched, ignoring bytes 0-4 entirely (they appear constant on R6m2 but are unverified across models).

### Additional Signals

Advertising rate (redundant but stable):
- POWER_ON: ~200 ms (5-6 ads/s)
- AUTO_POWER_OFF / POWER_SW_OFF: ~3 s

### Notes

The camera's MAC address falls in Murata's OUI range (IEEE registry — Murata manufactures the BLE module), while the advertisement Company ID `0x01A9` belongs to Canon Inc. (Bluetooth SIG registry — Canon owns the protocol). Both registries address different layers; this is normal.

Android API: `scanResult.scanRecord.getManufacturerSpecificData(0x01A9)` returns the 6 bytes *after* the Company ID.

**This finding is novel** — no other public project (as of April 2026) documents Canon advertisement power-state detection. furble implements the equivalent for Sony cameras but not Canon.

---

## Connect and Kickoff Sequence

Verified via HCI snoop of the official Canon Camera Connect app, reproduced multiple times on the R6 Mark II. This is the **minimal** sequence required for GPS streaming (the official app performs additional steps that are unnecessary):

1. **CCCD Subscribe**: `00040003` (GPS NOTIFY) with value `0x0200` (INDICATE).

2. **Write `0x0a` to `00020002`** (Handover Data, `WRITE_TYPE_DEFAULT`). Camera responds with a notification on the same characteristic (~20-byte payload, presumably a capability handshake).

3. **Write `0x05 0x00 0x00 0x00 0x00 0x00 0x00 0x00` to `00040002`** (GPS Data, source query, 8 bytes). Camera responds via indication on `00040003` with `0x05 0xNN` where `0xNN` is the active GPS source (see source enum below).

4. **Write `0x01` to `0001000a`** (Pairing Data, `WRITE_TYPE_DEFAULT`). This is the actual trigger for the ready indication. Side effect: the **Bluetooth icon** on the camera display turns blue.

5. **Indication `0x02 0x00` on `00040003`** (GPS NOTIFY) = camera is ready to receive GPS frames.

6. From here: **`0x04` + 19-byte GPS fix on `00040002`** with `WRITE_TYPE_NO_RESPONSE`. Canon's app sends at ~15 s intervals.

### Steps the official app performs but that are unnecessary

The official app subscribes to 8 CCCDs and reads STATUS + NOTIFY initially. We verified that for GPS + shutter, only the GPS_NOTIFY subscription is needed. Omitting the extra steps saves ~750 ms in handshake time (connect → first fix: ~550 ms instead of ~1.5 s).

Unnecessary steps:
- Initial reads of STATUS (`00040001`) and NOTIFY (`00040003`) — values are purely diagnostic
- 7 additional CCCD subscriptions: Handover Data/Notify (`00020002`/`00020003`), Remote Status/Events/Zoom/Exposure/Aperture (`00030001`/`00030002`/`00030011`/`00030021`/`00030031`)

### Handover Service Kickoff (`0x0a` on `00020002`)

Not documented in any other public project (as of April 2026). Likely only required when an app inherits an existing Canon Camera Connect bond rather than performing a fresh pairing.

The camera responds with a ~20-byte notification on the same characteristic (`00020002`). The response payload contains WiFi handover data, including the phone's MAC address as seen by the camera (bytes 8-13, reversed). The response content is not used by Loculus — only the write itself is needed to advance the kickoff sequence.

---

## GPS Commands on `00040002`

| Byte(s) | Write Type | Meaning |
|---------|-----------|---------|
| `0x01` | DEFAULT | Disable GPS |
| `0x02` | DEFAULT | Request GPS from smartphone |
| `0x03` | DEFAULT | Stop GPS (session end, before disconnect) |
| `0x04` + 19 bytes | **NO_RESPONSE** | Send GPS fix (20-byte binary, see below) |
| `0x05` + 7× `0x00` | DEFAULT | Source query (part of kickoff) |
| `0x06 0xNN` + 6× `0x00` | DEFAULT | Set GPS source: `0x00`=disable, `0x01`=external, `0x04`=smartphone |

**Write type rules:**
- Command writes → `WRITE_TYPE_DEFAULT` (with response)
- GPS fix (`0x04` + 19 bytes) → `WRITE_TYPE_NO_RESPONSE` (fire and forget)

### GPS Source Enum

The source query (`0x05`) and source set (`0x06`) commands use these values:

| Value | Meaning |
|-------|---------|
| `0x00` | Disabled |
| `0x01` | External GPS receiver |
| `0x02` | Built-in GPS (cameras with GPS module) |
| `0x03` | Built-in GPS off |
| `0x04` | **Smartphone** (the mode we use) |

### GPS Indication States on `00040003`

Indications received on the GPS NOTIFY characteristic during and after the kickoff sequence:

| Byte 0 | State | Meaning |
|--------|-------|---------|
| `0x01` | STATE_1 | Intermediate (observed during transitions) |
| `0x02` | **READY_TO_RECEIVE** | Camera ready for GPS frames — start streaming |
| `0x03` | STATE_3 | Intermediate (observed during stop/teardown) |
| `0x05` | Source info | Byte 1 contains the active GPS source (see enum above) |

Only `0x02` (READY_TO_RECEIVE) is the signal to begin sending GPS data.

### GPS Status Characteristic (`00040001`)

Read-only. Observed value: `03 13 00` (3 bytes). Bit 1 of byte 0 (`0x02`) indicates "GPS source was last set to smartphone" — but this value **persists across power cycles and standby**. It is not a ready signal. Only a fresh indication on `00040003` is authoritative.

---

## GPS Data Format (20-Byte Binary Frame)

**Not NMEA.** Canon transmits GPS fixes as a compact 20-byte binary frame that fits in a single BLE PDU at the default MTU (23 bytes). No MTU negotiation required.

```
Offset  Size  Content
------  ----  ------------------------------------------------
0       1     Command byte 0x04
1       1     N/S indicator ('N'=0x4E or 'S'=0x53)
2       4     abs(latitude) as IEEE 754 float32 LITTLE-ENDIAN
6       1     E/W indicator ('E'=0x45 or 'W'=0x57)
7       4     abs(longitude) as float32 LITTLE-ENDIAN
11      1     Altitude sign ('+'=0x2B, '-'=0x2D, or 0x00 if NaN)
12      4     abs(altitude) as float32 LITTLE-ENDIAN (0.0 if NaN)
16      4     Unix timestamp in seconds as int32 LITTLE-ENDIAN
------
20 bytes total
```

**Byte order is critical**: Canon uses LITTLE-ENDIAN. Verified via HCI snoop logs.

### Example

Brandenburg Gate, Berlin (52.516275°N, 13.377704°E, 34 m, t=1712926800):
```
04 4E 42 52 21 3A 45 41 55 30 7E 2B 42 08 00 00 66 18 C3 50
│  │  └─────┬──────┘ │  └─────┬──────┘ │  └─────┬──────┘ └─────┬──────┘
│  │       lat       │      lon        │      alt        unix time
│  N                 E                  +
Cmd
```

**Warning:** Early attempts with NMEA text frames (~140 bytes per update) crashed the camera firmware — the bytes after the `0x04` command byte were interpreted as binary payload, and invalid values locked up the GPS state machine (busy-lock requiring reboot). Always use the binary format.

Implementation: see `app/src/main/java/de/schaefer/eosgps/CanonGpsFrame.kt`.

---

## Remote Shutter Protocol (`00030030`)

Writes to characteristic `00030030` (Service 0003) with `WRITE_TYPE_DEFAULT` (acknowledged). Always 2 bytes: byte 0 = `0x00`, byte 1 encodes the action.

**Empirically verified:**

| Bytes | Effect |
|-------|--------|
| `00 01` + `00 02` in quick succession | If autofocus locks: photo saved to SD card. Otherwise nothing happens. |

**Known command codes** (labels from Canon's own debug logging):

| Bytes | Label |
|-------|-------|
| `00 01` / `00 02` | AF_REL_ON / AF_REL_OFF |
| `00 03` / `00 04` | AF_ON / AF_OFF |
| `00 05` / `00 06` | REL_ON / REL_OFF |
| `00 10` / `00 11` | MOVIE_START / MOVIE_STOP |
| `00 20` / `00 21` | ZOOM_TELE / ZOOM_TELE_STOP |
| `00 22` / `00 23` | ZOOM_WIDE / ZOOM_WIDE_STOP |

Only AF_REL_ON/OFF has been tested on actual hardware. The other codes are unverified.

Commands are sent in pairs with variable timing between writes (~100 ms observed). The pair `00 01` → `00 02` triggers a single photo capture.

**Observed notifications on shutter write:** on failed AF, ~540 ms after the second ACK: `00030002` (Remote Events) = `0x0313`, `00030031` (Remote Aperture) = `0x010101`. On successful AF + photo: no notifications observed (small sample size).

---

## Pairing Protocol (Initial Pairing)

Canon uses a proprietary GATT-level pairing (not standard Bluetooth SMP pairing):

1. Establish BLE connection (no system-level pairing)
2. Enable notifications on `0001000b`
3. Send pairing request: `0x03` + device name (ASCII) to `00010005`
4. User confirms on camera display
5. Response `0x02` = success

**Important:** Canon's GATT pairing does **not** install SMP encryption keys in the Android bond store. The bond record exists but without encryption keys. Consequence: encrypted standard characteristics like DIS (`0x180a`) cannot be read — Android's automatic encryption escalation fails with HCI 0x3D ("MAC Connection Failed").

For apps that reuse an existing bond (established via Canon Camera Connect), the pairing protocol is not needed.

---

## Graceful Teardown

1. Write `0x03` to `00040002` (GPS stop)
2. `gatt.disconnect()` + `gatt.close()`

---

## Critical Pitfalls (All Resolved)

1. **LITTLE-ENDIAN** in the GPS frame (decompilation suggested BE, wire format is LE)
2. **INDICATION not NOTIFICATION** for `00040003` CCCD (value `0x0200`, not `0x0100`)
3. **Source query is 8 bytes** (`[0x05, 0, 0, 0, 0, 0, 0, 0]`) — not just `0x05`
4. **Pairing `0x01` write is mandatory** — without it, no ready indication and the BT icon stays gray
5. **STATUS (`00040001`) is not a ready signal** — bit 1 persists across power cycles. Only a fresh indication on `00040003` is authoritative
6. **Advertisement power byte as wake trigger** — no connect, no wake; only bits 1-2 of byte 5 decide
7. **GPS frames require `WRITE_TYPE_NO_RESPONSE`** — using `WRITE_TYPE_DEFAULT` (expecting ACK) causes a hang; the camera never sends a response for `0x04` data writes
8. **NMEA text crashes the camera** — the GPS characteristic expects 20-byte binary frames. Sending NMEA text after the `0x04` command byte corrupts the camera's GPS state machine, causing a busy-lock that requires a reboot

---

## Prior Art and References

- [Ian Douglas Scott — Canon DSLR Bluetooth Remote Protocol (2018)](https://iandouglasscott.com/2018/07/04/canon-dslr-bluetooth-remote-protocol/) — first public analysis of Canon BLE pairing + shutter. No GPS, no advertisements.
- [gkoh/furble](https://github.com/gkoh/furble) ([Issue #189](https://github.com/gkoh/furble/issues/189)) — ESP32 Canon remote with GPS support (2025). Documents 20-byte GPS frame, INDICATE CCCDs, `0x01` pairing trigger. Tested on R6 (not R6m2). No advertisement power-state detection for Canon.
- [blackdot.be — GPS Tracking for Photography (2025)](https://www.blackdot.be/2025/02/photography-gps-tracking/) — describes the same use-case problem from a photographer's perspective.

### Novel Contributions (as of April 2026)

- **Advertisement-based power detection** for Canon cameras (byte 5 of manufacturer data under Company ID `0x01A9`) — enables passive scan-first architecture without risk of waking sleeping cameras.
- **Handover service kickoff** (`0x0a` write on `00020002`) — not documented in any public project. Required when inheriting an existing Canon Camera Connect bond.
- **R6 Mark II verification** — first documented R6m2 BLE GPS implementation outside the official Canon app.
