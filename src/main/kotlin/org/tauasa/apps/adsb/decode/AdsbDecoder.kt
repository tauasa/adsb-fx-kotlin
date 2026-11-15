package org.tauasa.apps.adsb.decode

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turns a validated Mode S frame into an [AdsbMessage].
 *
 * Stateless by design: everything that needs history — CPR pairing, track
 * building, staleness — lives in the tracker, so this can run on the receive
 * thread without locking.
 */
object AdsbDecoder {

    // Index 32 is the pad character, written as a space so that trailing
    // padding disappears with a plain trim().
    private const val ID_CHARSET =
        "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"

    /**
     * Decode a frame. Returns null for frames that fail parity, use a downlink
     * format this app does not handle, or carry a type code with no decoder yet.
     */
    fun decode(frame: ByteArray, receivedAtMillis: Long = System.currentTimeMillis()): AdsbMessage? {
        if (!ModeS.isValid(frame)) return null

        return when (ModeS.downlinkFormat(frame)) {
            11 -> {
                if (frame.size != ModeS.SHORT_FRAME_BYTES) null
                else AdsbMessage.Acquisition(ModeS.icao(frame), receivedAtMillis)
            }
            // 17 is a Mode S transponder; 18 is TIS-B / ADS-R, same ME layout.
            17, 18 -> {
                if (frame.size != ModeS.LONG_FRAME_BYTES) null
                else decodeExtendedSquitter(frame, receivedAtMillis)
            }
            else -> null
        }
    }

    private fun decodeExtendedSquitter(frame: ByteArray, now: Long): AdsbMessage? {
        val icao = ModeS.icao(frame)
        return when (val tc = ModeS.typeCode(frame)) {
            in 1..4 -> identification(frame, icao, now, tc)
            in 5..8 -> surfacePosition(frame, icao, now)
            in 9..18 -> airbornePosition(frame, icao, now, barometric = true)
            19 -> velocity(frame, icao, now)
            in 20..22 -> airbornePosition(frame, icao, now, barometric = false)
            else -> null
        }
    }

    private fun identification(frame: ByteArray, icao: Int, now: Long, tc: Int): AdsbMessage {
        val builder = StringBuilder(8)
        // Eight 6-bit characters, ME bits 9-56.
        for (i in 0 until 8) {
            builder.append(ID_CHARSET[frame.bits(41 + i * 6, 6)])
        }
        val callsign = builder.toString().replace("#", "").trim()
        return AdsbMessage.Identification(
            icao = icao,
            receivedAtMillis = now,
            callsign = callsign,
            category = wakeCategory(tc, frame.bits(38, 3)),
        )
    }

    private fun airbornePosition(
        frame: ByteArray,
        icao: Int,
        now: Long,
        barometric: Boolean,
    ): AdsbMessage {
        val cpr = CprFrame(
            latEncoded = frame.bits(55, 17),
            lonEncoded = frame.bits(72, 17),
            odd = frame.flag(54),
            timestampMillis = now,
            surface = false,
        )
        return AdsbMessage.AirbornePosition(
            icao = icao,
            receivedAtMillis = now,
            cpr = cpr,
            altitudeFeet = decodeAltitude(frame.bits(41, 12)),
            barometric = barometric,
        )
    }

    private fun surfacePosition(frame: ByteArray, icao: Int, now: Long): AdsbMessage {
        val cpr = CprFrame(
            latEncoded = frame.bits(55, 17),
            lonEncoded = frame.bits(72, 17),
            odd = frame.flag(54),
            timestampMillis = now,
            surface = true,
        )
        val trackValid = frame.flag(45)
        return AdsbMessage.SurfacePosition(
            icao = icao,
            receivedAtMillis = now,
            cpr = cpr,
            groundSpeedKnots = decodeMovement(frame.bits(38, 7)),
            trackDegrees = if (trackValid) frame.bits(46, 7) * 360.0 / 128.0 else null,
        )
    }

    private fun velocity(frame: ByteArray, icao: Int, now: Long): AdsbMessage? {
        val subtype = frame.bits(38, 3)
        if (subtype !in 1..4) return null
        val supersonic = subtype == 2 || subtype == 4
        val multiplier = if (supersonic) 4 else 1

        var groundSpeed: Double? = null
        var track: Double? = null
        var airspeed: Double? = null
        var heading: Double? = null

        if (subtype == 1 || subtype == 2) {
            val eastWest = frame.bits(47, 10)
            val northSouth = frame.bits(58, 10)
            // Zero means "no data"; 1 means zero velocity, hence the offset.
            if (eastWest != 0 && northSouth != 0) {
                val vx = (eastWest - 1) * multiplier * if (frame.flag(46)) -1 else 1
                val vy = (northSouth - 1) * multiplier * if (frame.flag(57)) -1 else 1
                groundSpeed = hypot(vx.toDouble(), vy.toDouble())
                track = normalizeAngle(Math.toDegrees(atan2(vx.toDouble(), vy.toDouble())))
            }
        } else {
            if (frame.flag(46)) {
                heading = frame.bits(47, 10) * 360.0 / 1024.0
            }
            val speed = frame.bits(58, 10)
            if (speed != 0) airspeed = ((speed - 1) * multiplier).toDouble()
        }

        val rawVerticalRate = frame.bits(70, 9)
        val verticalRate = if (rawVerticalRate == 0) {
            null
        } else {
            (rawVerticalRate - 1) * 64 * if (frame.flag(69)) -1 else 1
        }

        return AdsbMessage.Velocity(
            icao = icao,
            receivedAtMillis = now,
            groundSpeedKnots = groundSpeed,
            trackDegrees = track,
            airspeedKnots = airspeed,
            headingDegrees = heading,
            verticalRateFpm = verticalRate,
            supersonic = supersonic,
        )
    }

    /**
     * 12-bit altitude field. With the Q bit set the encoding is a plain 25 ft
     * counter; with it clear the field is Gillham (Gray) coded in 100 ft steps,
     * which is only seen above FL500 and is not decoded here.
     */
    internal fun decodeAltitude(raw: Int): Int? {
        if (raw == 0) return null
        val qBitSet = (raw shr 4) and 1 == 1
        if (!qBitSet) return null
        val n = ((raw and 0xFE0) shr 1) or (raw and 0x0F)
        return n * 25 - 1000
    }

    /** 7-bit surface movement field, a piecewise ground speed encoding in knots. */
    internal fun decodeMovement(raw: Int): Double? = when (raw) {
        0 -> null
        1 -> 0.0
        in 2..8 -> 0.125 + (raw - 2) * 0.125
        in 9..12 -> 1.0 + (raw - 9) * 0.25
        in 13..38 -> 2.0 + (raw - 13) * 0.5
        in 39..93 -> 15.0 + (raw - 39) * 1.0
        in 94..108 -> 70.0 + (raw - 94) * 2.0
        in 109..123 -> 100.0 + (raw - 109) * 5.0
        124 -> 175.0
        else -> null
    }

    private fun normalizeAngle(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
}
