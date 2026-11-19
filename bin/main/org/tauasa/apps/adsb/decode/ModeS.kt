package org.tauasa.apps.adsb.decode

/**
 * Frame-level Mode S handling: hex parsing, parity checking and the header
 * fields that are common to every downlink format.
 */
object ModeS {

    /** Mode S CRC-24 generator, x^24 + x^23 + ... (0x1FFF409 with the top bit implicit). */
    private const val GENERATOR = 0xFFF409

    const val SHORT_FRAME_BYTES = 7   // 56-bit frames: DF 0, 4, 5, 11
    const val LONG_FRAME_BYTES = 14   // 112-bit frames: DF 16, 17, 18, 19, 20, 21, 24

    /**
     * Parse an AVR-style hex payload into bytes. Returns null for anything that
     * is not a well-formed 56- or 112-bit frame.
     */
    fun parseHex(hex: String): ByteArray? {
        val clean = hex.trim().trimEnd(';')
        if (clean.length != SHORT_FRAME_BYTES * 2 && clean.length != LONG_FRAME_BYTES * 2) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * CRC-24 over the whole frame, parity field included. A frame with intact
     * parity leaves a remainder of zero, so this doubles as the validity check.
     */
    fun crc(frame: ByteArray): Int {
        var remainder = 0
        for (byte in frame) {
            remainder = remainder xor ((byte.toInt() and 0xFF) shl 16)
            repeat(8) {
                remainder = if (remainder and 0x800000 != 0) {
                    (remainder shl 1) xor GENERATOR
                } else {
                    remainder shl 1
                }
                remainder = remainder and 0xFFFFFF
            }
        }
        return remainder
    }

    /** True when the frame's parity checks out. */
    fun isValid(frame: ByteArray): Boolean = crc(frame) == 0

    /** Downlink format, frame bits 1-5. */
    fun downlinkFormat(frame: ByteArray): Int = frame.bits(1, 5)

    /** Transponder address, frame bits 9-32. Only meaningful for DF 11, 17 and 18. */
    fun icao(frame: ByteArray): Int = frame.bits(9, 24)

    /** Capability / control field, frame bits 6-8. */
    fun capability(frame: ByteArray): Int = frame.bits(6, 3)

    /** Extended squitter type code, ME bits 1-5 (frame bits 33-37). */
    fun typeCode(frame: ByteArray): Int = frame.bits(33, 5)

    fun formatIcao(icao: Int): String = String.format("%06X", icao)
}
