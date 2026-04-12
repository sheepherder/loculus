package de.schaefer.eosgps

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object Nmea {
    private val timeFmt = DateTimeFormatter.ofPattern("HHmmss")
    private val dateFmt = DateTimeFormatter.ofPattern("ddMMyy")

    fun sentences(
        latitude: Double,
        longitude: Double,
        altitude: Double = 0.0,
        speedMs: Double = 0.0,
        bearing: Double = 0.0,
        now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)
    ): String {
        val t = now.format(timeFmt) + ".000"
        val d = now.format(dateFmt)
        val (latStr, latDir) = toNmea(latitude, true)
        val (lonStr, lonDir) = toNmea(longitude, false)
        val knots = speedMs * 1.94384

        val gga = "GPGGA,$t,$latStr,$latDir,$lonStr,$lonDir,1,6,0,%.1f,M,0,M,,".format(altitude)
        val rmc = "GPRMC,$t,A,$latStr,$latDir,$lonStr,$lonDir,%.1f,%.1f,$d,0,A".format(knots, bearing)

        return "\$$gga*${checksum(gga)}\r\n\$$rmc*${checksum(rmc)}\r\n"
    }

    private fun toNmea(deg: Double, isLat: Boolean): Pair<String, String> {
        val dir = when {
            isLat && deg < 0 -> "S"
            isLat -> "N"
            deg < 0 -> "W"
            else -> "E"
        }
        val a = abs(deg)
        val whole = a.toInt()
        val minutes = (a - whole) * 60.0
        val s = if (isLat) "%02d%08.5f".format(whole, minutes)
                else "%03d%08.5f".format(whole, minutes)
        return s to dir
    }

    private fun checksum(body: String): String {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return "%02X".format(cs)
    }
}
