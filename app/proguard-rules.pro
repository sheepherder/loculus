# Manifest-referenced components
-keep class de.schaefer.eosgps.MainActivity
-keep class de.schaefer.eosgps.GpsTrackingService
-keep class de.schaefer.eosgps.BootReceiver
-keep class de.schaefer.eosgps.ScanResultReceiver

# BLE callbacks (invoked by reflection from the Android BLE stack)
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }

# Google Play Services Location
-keep class com.google.android.gms.location.** { *; }

# Strip all Log calls below ERROR in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
