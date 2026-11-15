package org.tauasa.apps.adsb.source

import org.tauasa.apps.adsb.decode.Cpr
import org.tauasa.apps.adsb.decode.ModeS
import org.tauasa.apps.adsb.decode.Position
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates real, CRC-valid ADS-B frames for invented aircraft flying around a
 * centre point.
 *
 * This exists so the decoder, tracker and display can be exercised without a
 * dongle plugged in — and because a bug in the encoder shows up immediately as
 * a bug in the decoder, the pair keeps each other honest.
 */
class SimulatedSource(
    private val centre: Position,
    private val aircraftCount: Int = 12,
    private val seed: Long = 20260818L,
) : AdsbSource {

    override val description: String =
        "Simulated traffic near %.3f, %.3f".format(centre.latitude, centre.longitude)

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null

    override val running: Boolean get() = active.get()

    override fun start(onFrame: FrameSink, onEvent: (SourceEvent) -> Unit) {
        if (!active.compareAndSet(false, true)) return
        worker = Thread({ fly(onFrame, onEvent) }, "adsb-simulator").apply {
            isDaemon = true
            start()
        }
    }

    private fun fly(onFrame: FrameSink, onEvent: (SourceEvent) -> Unit) {
        val random = Random(seed)
        val fleet = List(aircraftCount) { index -> newAircraft(index, random) }
        onEvent(SourceEvent.Connected("$aircraftCount simulated aircraft"))

        var tick = 0L
        try {
            while (active.get()) {
                for (target in fleet) {
                    target.advance(STEP_SECONDS)
                    onFrame(Encoder.airbornePosition(target, odd = tick % 2 == 1L))
                    onFrame(Encoder.velocity(target))
                    if (tick % 10L == 0L) onFrame(Encoder.identification(target))
                }
                tick++
                Thread.sleep((STEP_SECONDS * 1000).toLong())
            }
        } catch (_: InterruptedException) {
            // shutting down
        }
        onEvent(SourceEvent.Disconnected("Simulation stopped"))
    }

    private fun newAircraft(index: Int, random: Random): SimAircraft {
        val bearing = random.nextDouble(0.0, 360.0)
        val rangeNm = random.nextDouble(8.0, 120.0)
        val latitude = centre.latitude + rangeNm / 60.0 * cos(Math.toRadians(bearing))
        val longitude = centre.longitude +
            rangeNm / 60.0 * sin(Math.toRadians(bearing)) / cos(Math.toRadians(centre.latitude))
        return SimAircraft(
            icao = 0xA00000 + random.nextInt(0x0FFFFF),
            callsign = CARRIERS[index % CARRIERS.size] + random.nextInt(100, 999),
            latitude = latitude,
            longitude = longitude,
            altitudeFeet = random.nextInt(4, 41) * 1000,
            groundSpeedKnots = random.nextDouble(210.0, 510.0),
            trackDegrees = random.nextDouble(0.0, 360.0),
            verticalRateFpm = listOf(0, 0, 0, 1216, -960, 640).random(random),
        )
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
    }

    private class SimAircraft(
        val icao: Int,
        val callsign: String,
        var latitude: Double,
        var longitude: Double,
        var altitudeFeet: Int,
        val groundSpeedKnots: Double,
        var trackDegrees: Double,
        val verticalRateFpm: Int,
    ) {
        fun advance(seconds: Double) {
            val distanceNm = groundSpeedKnots * seconds / 3600.0
            latitude += distanceNm / 60.0 * cos(Math.toRadians(trackDegrees))
            longitude += distanceNm / 60.0 * sin(Math.toRadians(trackDegrees)) /
                cos(Math.toRadians(latitude))
            trackDegrees = (trackDegrees + TURN_RATE_DEG_PER_SEC * seconds + 360.0) % 360.0
            altitudeFeet = (altitudeFeet + (verticalRateFpm * seconds / 60.0)).roundToInt()
                .coerceIn(1000, 45000)
        }
    }

    /** Builds valid DF17 frames. The inverse of the decoder, used only for simulation. */
    private object Encoder {

        private const val ID_CHARSET =
            "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"

        fun identification(a: SimAircraft): ByteArray {
            val frame = FrameWriter(a.icao, typeCode = 4)
            frame.put(38, 3, 3) // wake category: medium
            val padded = a.callsign.padEnd(8).take(8)
            padded.forEachIndexed { i, c ->
                frame.put(41 + i * 6, 6, max(ID_CHARSET.indexOf(c), 0))
            }
            return frame.finish()
        }

        fun airbornePosition(a: SimAircraft, odd: Boolean): ByteArray {
            val frame = FrameWriter(a.icao, typeCode = 11)
            frame.put(41, 12, encodeAltitude(a.altitudeFeet))
            frame.put(54, 1, if (odd) 1 else 0)
            val (latCpr, lonCpr) = encodeCpr(a.latitude, a.longitude, odd)
            frame.put(55, 17, latCpr)
            frame.put(72, 17, lonCpr)
            return frame.finish()
        }

        fun velocity(a: SimAircraft): ByteArray {
            val frame = FrameWriter(a.icao, typeCode = 19)
            frame.put(38, 3, 1) // subtype 1: ground referenced, subsonic
            val east = a.groundSpeedKnots * sin(Math.toRadians(a.trackDegrees))
            val north = a.groundSpeedKnots * cos(Math.toRadians(a.trackDegrees))
            frame.put(46, 1, if (east < 0) 1 else 0)
            frame.put(47, 10, (abs(east).roundToInt() + 1).coerceIn(1, 1023))
            frame.put(57, 1, if (north < 0) 1 else 0)
            frame.put(58, 10, (abs(north).roundToInt() + 1).coerceIn(1, 1023))
            frame.put(69, 1, if (a.verticalRateFpm < 0) 1 else 0)
            frame.put(70, 9, (abs(a.verticalRateFpm) / 64 + 1).coerceIn(1, 511))
            return frame.finish()
        }

        private fun encodeAltitude(feet: Int): Int {
            val n = ((feet + 1000) / 25).coerceIn(0, 2047)
            return ((n and 0x7F0) shl 1) or 0x10 or (n and 0x0F)
        }

        private fun encodeCpr(lat: Double, lon: Double, odd: Boolean): Pair<Int, Int> {
            val i = if (odd) 1 else 0
            val dLat = 360.0 / (60 - i)
            val yz = floor(131072.0 * (mod(lat, dLat) / dLat) + 0.5).toInt()
            val rlat = dLat * (yz / 131072.0 + floor(lat / dLat))
            val dLon = 360.0 / max(Cpr.longitudeZones(rlat) - i, 1)
            val xz = floor(131072.0 * (mod(lon, dLon) / dLon) + 0.5).toInt()
            return Pair(yz and 0x1FFFF, xz and 0x1FFFF)
        }

        private fun mod(a: Double, b: Double): Double = ((a % b) + b) % b
    }

    private class FrameWriter(icao: Int, typeCode: Int) {
        private val bytes = ByteArray(ModeS.LONG_FRAME_BYTES)

        init {
            put(1, 5, 17)      // DF17
            put(6, 3, 5)       // capability: airborne, level 2+
            put(9, 24, icao)
            put(33, 5, typeCode)
        }

        fun put(start: Int, length: Int, value: Int) {
            for (offset in 0 until length) {
                if ((value shr (length - 1 - offset)) and 1 == 1) {
                    val bit = start + offset - 1
                    bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
                }
            }
        }

        /**
         * Append parity so the frame checks out under [ModeS.crc].
         *
         * The parity is the CRC of the 88 data bits alone. Running it over the
         * zero-padded 14-byte frame gives a different remainder and produces
         * frames that every receiver quietly discards.
         */
        fun finish(): ByteArray {
            val parity = ModeS.crc(bytes.copyOfRange(0, 11))
            bytes[11] = ((parity shr 16) and 0xFF).toByte()
            bytes[12] = ((parity shr 8) and 0xFF).toByte()
            bytes[13] = (parity and 0xFF).toByte()
            return bytes
        }
    }

    private companion object {
        const val STEP_SECONDS = 0.5
        const val TURN_RATE_DEG_PER_SEC = 0.35
        val CARRIERS = listOf("UAL", "SWA", "DAL", "AAL", "ASA", "JBU", "SKW", "FDX")
    }
}
