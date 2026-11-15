package org.tauasa.apps.adsb

import org.tauasa.apps.adsb.decode.AdsbDecoder
import org.tauasa.apps.adsb.decode.AdsbMessage
import org.tauasa.apps.adsb.decode.Cpr
import org.tauasa.apps.adsb.decode.ModeS
import org.tauasa.apps.adsb.decode.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reference frames widely published in ADS-B decoding literature. If these stop
 * passing, something in the bit offsets has drifted.
 */
class AdsbDecoderTest {

    private fun frame(hex: String) = requireNotNull(ModeS.parseHex(hex)) { "bad test vector: $hex" }

    @Test
    fun `parity checks out on a good frame`() {
        assertTrue(ModeS.isValid(frame("8D4840D6202CC371C32CE0576098")))
    }

    @Test
    fun `parity fails after a single flipped bit`() {
        val corrupted = frame("8D4840D6202CC371C32CE0576098")
        corrupted[6] = (corrupted[6].toInt() xor 0x08).toByte()
        assertFalse(ModeS.isValid(corrupted))
        assertNull(AdsbDecoder.decode(corrupted))
    }

    @Test
    fun `identification message yields callsign`() {
        val message = AdsbDecoder.decode(frame("8D4840D6202CC371C32CE0576098"))
            as AdsbMessage.Identification
        assertEquals(0x4840D6, message.icao)
        assertEquals("KLM1023", message.callsign)
    }

    @Test
    fun `airborne position reports altitude`() {
        val message = AdsbDecoder.decode(frame("8D40621D58C382D690C8AC2863A7"))
            as AdsbMessage.AirbornePosition
        assertEquals(38000, message.altitudeFeet)
    }

    @Test
    fun `matched cpr pair resolves to a position`() {
        val even = AdsbDecoder.decode(frame("8D40621D58C382D690C8AC2863A7"), 2_000L)
            as AdsbMessage.AirbornePosition
        val odd = AdsbDecoder.decode(frame("8D40621D58C386435CC412692AD6"), 1_000L)
            as AdsbMessage.AirbornePosition
        assertFalse(even.cpr.odd)
        assertTrue(odd.cpr.odd)

        val fix = requireNotNull(Cpr.global(even.cpr, odd.cpr))
        assertEquals(52.2572, fix.latitude, 1e-4)
        assertEquals(3.91937, fix.longitude, 1e-4)
    }

    @Test
    fun `local cpr agrees with the global solution`() {
        val even = AdsbDecoder.decode(frame("8D40621D58C382D690C8AC2863A7"))
            as AdsbMessage.AirbornePosition
        val fix = Cpr.local(Position(52.258, 3.918), even.cpr)
        assertEquals(52.2572, fix.latitude, 1e-3)
        assertEquals(3.91937, fix.longitude, 1e-3)
    }

    @Test
    fun `velocity message yields ground speed track and vertical rate`() {
        val message = AdsbDecoder.decode(frame("8D485020994409940838175B284F"))
            as AdsbMessage.Velocity
        assertEquals(159.20, requireNotNull(message.groundSpeedKnots), 0.05)
        assertEquals(182.88, requireNotNull(message.trackDegrees), 0.05)
        assertEquals(-832, message.verticalRateFpm)
    }

    @Test
    fun `longitude zone count matches known boundaries`() {
        assertEquals(59, Cpr.longitudeZones(0.0))
        assertEquals(2, Cpr.longitudeZones(87.0))
        assertEquals(1, Cpr.longitudeZones(89.0))
    }

    @Test
    fun `frames with unhandled downlink formats are ignored`() {
        assertNull(AdsbDecoder.decode(frame("02E19838B12E39")))
    }
}
