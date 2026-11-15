package org.tauasa.apps.adsb

import org.tauasa.apps.adsb.decode.AdsbDecoder
import org.tauasa.apps.adsb.decode.AdsbMessage
import org.tauasa.apps.adsb.decode.ModeS
import org.tauasa.apps.adsb.decode.Position
import org.tauasa.apps.adsb.source.SimulatedSource
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulator writes frames with the same bit layout the decoder reads, so a
 * round trip catches an error on either side. It caught a parity bug the first
 * time it ran.
 */
class SimulatedSourceTest {

    @Test
    fun `every simulated frame passes parity and decodes`() {
        val frames = ConcurrentLinkedQueue<ByteArray>()
        val source = SimulatedSource(Position(38.74, -121.22), aircraftCount = 6)

        source.use {
            it.start(onFrame = { frame -> frames.add(frame.copyOf()) }, onEvent = {})
            Thread.sleep(1_500)
        }

        val collected = frames.toList()
        assertTrue(collected.size >= 12, "expected a steady stream, got ${collected.size} frames")
        assertTrue(collected.all { ModeS.isValid(it) }, "some frames failed parity")

        val decoded = collected.mapNotNull { AdsbDecoder.decode(it) }
        assertEquals(collected.size, decoded.size, "some frames did not decode")
        assertTrue(decoded.any { it is AdsbMessage.AirbornePosition })
        assertTrue(decoded.any { it is AdsbMessage.Velocity })
        assertEquals(6, decoded.map { it.icao }.distinct().size)
    }
}
