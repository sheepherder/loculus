package de.schaefer.eosgps

/**
 * Canon advertisement decoding — single source of truth for both the
 * Activity-owned live scan ([FgScanner]) and the OS-offloaded scan
 * ([CanonScanRegistrar]).
 *
 * The Canon-specific payload under company id `0x01A9` is 6 bytes. We only
 * interpret byte 5 bits 1-2 (the power-state enum, decoded per the
 * decompiled Canon app's `C0231d.java:77-78` + `C0239f.j`). Byte 5 bit 0
 * ("flagA") has no exposed semantics in Canon's code — we ignore it. Bytes
 * 0-4 are observed-constant on our R6m2 but not semantically verified.
 */
object CanonAd {
    /** Bluetooth SIG company identifier assigned to Canon Inc. */
    const val COMPANY_ID = 0x01A9

    /** Index of the power-state byte within the 6-byte Canon mfg payload. */
    const val POWER_BYTE_INDEX = 5

    private const val POWER_BITS_MASK = 0x06   // bits 1-2 only
    private const val POWER_BITS_ON = 0x02     // (1 << 1) = POWER_ON

    /** HW-filter match + mask for "camera is POWER_ON". */
    val AWAKE_MFG_DATA = byteArrayOf(0, 0, 0, 0, 0, POWER_BITS_ON.toByte())
    val AWAKE_MFG_MASK = byteArrayOf(0, 0, 0, 0, 0, POWER_BITS_MASK.toByte())

    /**
     * Returns null when bits 1-2 = `3`. Canon's app treats that as "no
     * change" and keeps the previous state; we propagate that by letting
     * the caller skip the update.
     */
    fun powerStateFromByte(b: Byte): CameraPowerState? = when ((b.toInt() shr 1) and 0x03) {
        0 -> CameraPowerState.AUTO_POWER_OFF
        1 -> CameraPowerState.POWER_ON
        2 -> CameraPowerState.POWER_SW_OFF
        else -> null
    }
}
