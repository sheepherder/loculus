package de.schaefer.eosgps

/**
 * Canon advertisement decoding constants — shared between the OS-offloaded
 * scan ([CanonScanRegistrar]) and the in-service foreground scan
 * ([GpsTrackingService]). Keep a single source of truth so the two scan
 * paths stay consistent.
 *
 * The Canon-specific payload under company id `0x01A9` is 6 bytes. Only
 * byte 5 is semantically verified (power state via HCI snoop). Bytes 0–4
 * are observed constants on our R6m2 but not validated across firmware
 * revisions or camera bodies, so we never match against them.
 *
 * Byte 5, known values:
 *   0x02 (binary `0b00000010`) — camera on, connectable
 *   0x05 (binary `0b00000101`) — camera in BLE standby
 * We only trust the low three bits (`& 0x07`): matching the high bits
 * would break silently if Canon ever sets a flag bit up there.
 */
object CanonAd {
    /** Bluetooth SIG company identifier assigned to Canon Inc. */
    const val COMPANY_ID = 0x01A9

    /** Index of the power-state byte within the 6-byte Canon mfg payload. */
    const val POWER_BYTE_INDEX = 5

    /** Mask for the semantically meaningful bits of the power-state byte. */
    private const val POWER_BITS_MASK = 0x07
    private const val POWER_BITS_AWAKE = 0x02
    private const val POWER_BITS_ASLEEP = 0x05

    /** Canon HW-filter data for `ScanFilter.setManufacturerData(..., data, mask)`. */
    val AWAKE_MFG_DATA = byteArrayOf(0, 0, 0, 0, 0, POWER_BITS_AWAKE.toByte())
    val AWAKE_MFG_MASK = byteArrayOf(0, 0, 0, 0, 0, POWER_BITS_MASK.toByte())

    fun powerStateFromByte(b: Byte): CameraPowerState = when (b.toInt() and POWER_BITS_MASK) {
        POWER_BITS_AWAKE -> CameraPowerState.AWAKE
        POWER_BITS_ASLEEP -> CameraPowerState.ASLEEP
        else -> CameraPowerState.ASLEEP
    }
}
