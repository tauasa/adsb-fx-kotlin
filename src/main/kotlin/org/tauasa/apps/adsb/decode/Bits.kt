package org.tauasa.apps.adsb.decode

/** Unsigned byte at [index]. */
internal fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

/**
 * Extract [length] bits starting at [start], where [start] is a **1-based** bit
 * position within the frame, MSB first.
 *
 * The ADS-B specification numbers frame bits from 1, and the ME (message extended
 * squitter) field begins at frame bit 33. Keeping the same numbering as the spec
 * means field offsets can be read straight off the tables in DO-260B without a
 * translation step, which is where most hand-rolled decoders go wrong.
 */
internal fun ByteArray.bits(start: Int, length: Int): Int {
    require(length in 1..32) { "length must be 1..32, was $length" }
    require(start >= 1 && start + length - 1 <= size * 8) { "bit range out of frame" }
    var value = 0
    for (offset in 0 until length) {
        val bit = start + offset - 1
        value = (value shl 1) or ((u(bit / 8) shr (7 - bit % 8)) and 1)
    }
    return value
}

/** Bit [start] as a boolean. */
internal fun ByteArray.flag(start: Int): Boolean = bits(start, 1) == 1
