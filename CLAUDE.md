# Loculus — Canon EOS GPS

Android-App die per BLE GPS-Koordinaten an Canon-EOS-Kameras streamt.

## Build

```sh
cd android
./gradlew assembleRelease    # oder assembleDebug
```

APK: `android/app/build/outputs/apk/release/loculus-0.1.0-release.apk`

## Architektur

Single-Module Kotlin/Compose App. Kein DI, kein Repository-Pattern.

- **CanonScanRegistrar** — OS-offloaded BLE-Scan (PendingIntent), weckt App bei Kamera-POWER_ON
- **FgScanner** — Foreground-Scan (Activity-Lifecycle), zeigt Live-Status in UI
- **GpsTrackingService** — FGS, ownt die GATT-Session und streamt GPS-Fixes
- **CanonGattClient** — GATT State Machine mit Op-Queue
- **CanonGpsFrame** — 20-Byte Binary GPS Frame Encoder (reverse-engineered)
- **TrackingState** — Singleton mit MutableStateFlow, Bridge zwischen Service und UI

## Lint & Compiler

- `allWarningsAsErrors`, `progressiveMode`, `-Wextra`, `-Xjsr305=strict`
- Lint: `checkAllWarnings`, `warningsAsErrors`, `abortOnError`
- `LogConditional` disabled — R8 strippt Log.v/d/i/w via proguard-rules.pro
- SyntheticAccessor-Fixes: betroffene Members sind `internal` statt `private`

## Gerät

Pixel 9a, Android 16, API 36. `BluetoothGattConnectionSettings` ist auf API 36 hidden (trotz compileSdk 37) — daher deprecated `connectGatt`-Overload mit `@Suppress("DEPRECATION")`.
