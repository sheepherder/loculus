# Loculus — Canon EOS GPS

Android app that streams GPS coordinates to Canon EOS cameras over BLE.

## Build

```sh
./gradlew assembleRelease    # or assembleDebug
```

APK: `app/build/outputs/apk/release/loculus-0.1.0-release.apk`

## Architecture

Single-module Kotlin/Compose app. No DI, no repository pattern.

- **CanonScanRegistrar** — OS-offloaded BLE scan (PendingIntent), wakes app on camera POWER_ON
- **FgScanner** — Foreground scan (activity lifecycle), shows live status in UI
- **GpsTrackingService** — FGS, owns the GATT session and streams GPS fixes
- **CanonGattClient** — GATT state machine with op queue
- **CanonGpsFrame** — 20-byte binary GPS frame encoder (reverse-engineered)
- **TrackingState** — Singleton with MutableStateFlow, bridge between service and UI

## Lint & Compiler

- `allWarningsAsErrors`, `progressiveMode`, `-Wextra`, `-Xjsr305=strict`
- Lint: `checkAllWarnings`, `warningsAsErrors`, `abortOnError`
- `LogConditional` disabled — R8 strips Log.v/d/i/w via proguard-rules.pro
- SyntheticAccessor fixes: affected members are `internal` instead of `private`

## Target Device

Pixel 9a, Android 16, API 36. `BluetoothGattConnectionSettings` is hidden on API 36 (despite compileSdk 37) — using deprecated `connectGatt` overload with `@Suppress("DEPRECATION")`.
