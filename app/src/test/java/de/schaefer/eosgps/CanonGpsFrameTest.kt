package de.schaefer.eosgps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CanonGpsFrameTest {

    @Test
    fun `frame is always 20 bytes`() {
        val frame = CanonGpsFrame.encode(52.0, 13.0, 100.0, 1712926800L)
        assertEquals(20, frame.size)
    }

    @Test
    fun `first byte is command 0x04`() {
        val frame = CanonGpsFrame.encode(0.0, 0.0, 0.0, 0L)
        assertEquals(0x04.toByte(), frame[0])
    }

    @Test
    fun `positive latitude produces N indicator`() {
        val frame = CanonGpsFrame.encode(52.516275, 13.377704, 34.0, 0L)
        assertEquals('N'.code.toByte(), frame[1])
    }

    @Test
    fun `negative latitude produces S indicator`() {
        val frame = CanonGpsFrame.encode(-33.8688, 151.2093, 0.0, 0L)
        assertEquals('S'.code.toByte(), frame[1])
    }

    @Test
    fun `positive longitude produces E indicator`() {
        val frame = CanonGpsFrame.encode(52.516275, 13.377704, 34.0, 0L)
        assertEquals('E'.code.toByte(), frame[6])
    }

    @Test
    fun `negative longitude produces W indicator`() {
        val frame = CanonGpsFrame.encode(40.7128, -74.0060, 10.0, 0L)
        assertEquals('W'.code.toByte(), frame[6])
    }

    @Test
    fun `latitude is abs float32 little-endian`() {
        val frame = CanonGpsFrame.encode(-33.8688, 0.0, 0.0, 0L)
        val lat = ByteBuffer.wrap(frame, 2, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(33.8688f, lat, 0.001f)
    }

    @Test
    fun `longitude is abs float32 little-endian`() {
        val frame = CanonGpsFrame.encode(0.0, -74.006, 0.0, 0L)
        val lon = ByteBuffer.wrap(frame, 7, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(74.006f, lon, 0.001f)
    }

    @Test
    fun `positive altitude produces plus sign`() {
        val frame = CanonGpsFrame.encode(0.0, 0.0, 34.0, 0L)
        assertEquals('+'.code.toByte(), frame[11])
    }

    @Test
    fun `negative altitude produces minus sign`() {
        val frame = CanonGpsFrame.encode(0.0, 0.0, -10.0, 0L)
        assertEquals('-'.code.toByte(), frame[11])
        val alt = ByteBuffer.wrap(frame, 12, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(10.0f, alt, 0.001f)
    }

    @Test
    fun `NaN altitude produces zero sign and zero value`() {
        val frame = CanonGpsFrame.encode(0.0, 0.0, Double.NaN, 0L)
        assertEquals(0x00.toByte(), frame[11])
        val alt = ByteBuffer.wrap(frame, 12, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(0.0f, alt, 0.0f)
    }

    @Test
    fun `timestamp is int32 little-endian`() {
        val ts = 1712926800L
        val frame = CanonGpsFrame.encode(0.0, 0.0, 0.0, ts)
        val decoded = ByteBuffer.wrap(frame, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(ts.toInt(), decoded)
    }

    @Test
    fun `Berlin Brandenburger Tor reference frame`() {
        val frame = CanonGpsFrame.encode(52.516275, 13.377704, 34.0, 1712926800L)
        assertEquals(0x04.toByte(), frame[0])
        assertEquals('N'.code.toByte(), frame[1])
        assertEquals('E'.code.toByte(), frame[6])
        assertEquals('+'.code.toByte(), frame[11])

        val lat = ByteBuffer.wrap(frame, 2, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(52.516275f, lat, 0.001f)

        val lon = ByteBuffer.wrap(frame, 7, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(13.377704f, lon, 0.001f)

        val alt = ByteBuffer.wrap(frame, 12, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(34.0f, alt, 0.001f)

        val ts = ByteBuffer.wrap(frame, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(1712926800, ts)
    }
}
