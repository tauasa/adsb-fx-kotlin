package org.tauasa.apps.adsb.decode

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/** A geographic position in decimal degrees. */
data class Position(val latitude: Double, val longitude: Double)

/**
 * One half of a CPR pair: the raw 17-bit latitude and longitude encodings plus
 * the odd/even format flag and the time it arrived.
 */
data class CprFrame(
    val latEncoded: Int,
    val lonEncoded: Int,
    val odd: Boolean,
    val timestampMillis: Long,
    val surface: Boolean = false,
)

/**
 * Compact Position Reporting.
 *
 * ADS-B does not transmit a position outright. Each message carries a position
 * modulo a latitude zone, and the zone itself has to be recovered either from a
 * matched odd/even pair (global decoding, no prior knowledge needed) or from a
 * position already known to be within half a zone (local decoding, cheaper and
 * usable on a single message).
 */
object Cpr {

    private const val NZ = 15.0
    private const val TWO_POW_17 = 131072.0

    /** Longest gap between an odd and even frame that may be paired, per DO-260B. */
    const val MAX_PAIR_AGE_MILLIS = 10_000L

    /** Number of longitude zones at [lat]. */
    fun longitudeZones(lat: Double): Int {
        val l = abs(lat)
        if (l >= 90.0) return 1
        if (l > 87.0) return 1
        if (l == 87.0) return 2
        if (l == 0.0) return 59
        val a = 1 - cos(PI / (2 * NZ))
        val b = cos(PI / 180.0 * l).pow(2)
        return floor(2 * PI / acos(1 - a / b)).toInt()
    }

    /**
     * Recover an unambiguous position from a matched odd/even pair.
     *
     * Returns null when the two frames straddle a latitude zone boundary, which
     * makes the pair unusable — the aircraft has moved far enough between frames
     * that the zone count no longer agrees, so the result would be wrong rather
     * than merely imprecise.
     */
    fun global(even: CprFrame, odd: CprFrame): Position? {
        if (even.surface != odd.surface) return null
        val scale = if (even.surface) 90.0 else 360.0

        val latCprEven = even.latEncoded / TWO_POW_17
        val latCprOdd = odd.latEncoded / TWO_POW_17
        val dLatEven = scale / 60.0
        val dLatOdd = scale / 59.0

        val j = floor(59 * latCprEven - 60 * latCprOdd + 0.5)
        var latEven = dLatEven * (mod(j, 60.0) + latCprEven)
        var latOdd = dLatOdd * (mod(j, 59.0) + latCprOdd)
        if (!even.surface) {
            if (latEven >= 270.0) latEven -= 360.0
            if (latOdd >= 270.0) latOdd -= 360.0
        }

        if (longitudeZones(latEven) != longitudeZones(latOdd)) return null

        val useEven = even.timestampMillis >= odd.timestampMillis
        val latitude = if (useEven) latEven else latOdd
        val nl = longitudeZones(latitude)

        val lonCprEven = even.lonEncoded / TWO_POW_17
        val lonCprOdd = odd.lonEncoded / TWO_POW_17
        val m = floor(lonCprEven * (nl - 1) - lonCprOdd * nl + 0.5)
        val ni = max(nl - if (useEven) 0 else 1, 1)
        val dLon = scale / ni
        val lonCpr = if (useEven) lonCprEven else lonCprOdd
        val longitude = dLon * (mod(m, ni.toDouble()) + lonCpr)

        return Position(latitude, normalizeLongitude(longitude))
    }

    /**
     * Recover a position from a single frame using [reference] — either the
     * receiver's own position or the aircraft's last known fix. The reference
     * must be within half a zone (about 180 NM airborne) or the result silently
     * lands in the wrong zone, so callers should sanity-check the distance.
     */
    fun local(reference: Position, frame: CprFrame): Position {
        val scale = if (frame.surface) 90.0 else 360.0
        val i = if (frame.odd) 1 else 0

        val dLat = scale / (60 - i)
        val latCpr = frame.latEncoded / TWO_POW_17
        val j = floor(reference.latitude / dLat) +
            floor(0.5 + mod(reference.latitude, dLat) / dLat - latCpr)
        val latitude = dLat * (j + latCpr)

        val ni = max(longitudeZones(latitude) - i, 1)
        val dLon = scale / ni
        val lonCpr = frame.lonEncoded / TWO_POW_17
        val m = floor(reference.longitude / dLon) +
            floor(0.5 + mod(reference.longitude, dLon) / dLon - lonCpr)
        val longitude = dLon * (m + lonCpr)

        return Position(latitude, normalizeLongitude(longitude))
    }

    private fun mod(a: Double, b: Double): Double = ((a % b) + b) % b

    private fun normalizeLongitude(lon: Double): Double {
        var l = lon
        while (l >= 180.0) l -= 360.0
        while (l < -180.0) l += 360.0
        return l
    }
}
