package de.schaefer.eosgps

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Canon EOS BLE GPS data frame — the real binary format, reverse-engineered by
 * capturing the Canon Camera Connect traffic with Android's HCI snoop log and
 * comparing with d4.C0500A.G(Location) in the decompiled app.
 *
 * 20 bytes total, written to 00040002 with WRITE_TYPE_NO_RESPONSE:
 *
 *   offset  size  content
 *   0       1     command byte 0x04
 *   1       1     N/S indicator ('N'=0x4E or 'S'=0x53)
 *   2       4     abs(latitude) as IEEE 754 float32, LITTLE-ENDIAN
 *   6       1     E/W indicator ('E'=0x45 or 'W'=0x57)
 *   7       4     abs(longitude) as float32, LITTLE-ENDIAN
 *   11      1     altitude sign ('+'=0x2B, '-'=0x2D, or 0x00 if NaN)
 *   12      4     abs(altitude) as float32, LITTLE-ENDIAN (0.0 if NaN)
 *   16      4     unix timestamp (seconds) as int32, LITTLE-ENDIAN
 *
 * Byte order was confirmed against a live Canon Camera Connect capture.
 * The decompiled code used Java ByteBuffer defaults (big-endian), which misled
 * us; Canon must set the order at runtime via a path jadx elided.
 *
 * NOT NMEA text. Canon switched to this compact binary format for BLE; it fits
 * in a single default-MTU packet so no MTU negotiation is needed.
 */
object CanonGpsFrame {
    fun encode(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        unixSeconds: Long,
    ): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x04)

        buf.put(if (latitude < 0) 'S'.code.toByte() else 'N'.code.toByte())
        buf.putFloat(abs(latitude).toFloat())

        buf.put(if (longitude < 0) 'W'.code.toByte() else 'E'.code.toByte())
        buf.putFloat(abs(longitude).toFloat())

        if (altitude.isNaN()) {
            buf.put(0x00)
            buf.putFloat(0.0f)
        } else {
            buf.put(if (altitude < 0) '-'.code.toByte() else '+'.code.toByte())
            buf.putFloat(abs(altitude).toFloat())
        }

        buf.putInt(unixSeconds.toInt())
        return buf.array()
    }
}
