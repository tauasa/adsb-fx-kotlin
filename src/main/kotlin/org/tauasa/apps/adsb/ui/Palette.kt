package org.tauasa.apps.adsb.ui

import javafx.scene.paint.Color

/**
 * Instrument-panel palette: sodium amber on ink, the way night-lit avionics and
 * older ATC displays are lit, with a single cool accent for history trails so
 * that past and present read as different kinds of information.
 */
object Palette {

    val INK: Color = Color.web("#0C1016")
    val PANEL: Color = Color.web("#141A22")
    /** Range rings and compass ticks: bright enough to hold contrast against INK. */
    val RING: Color = Color.web("#4D6580")
    val RING_OUTER: Color = Color.web("#6F8BAA")
    val TEXT: Color = Color.web("#DCE3EC")
    val DIM: Color = Color.web("#B4C0CD")
    val AMBER: Color = Color.web("#F0A93B")
    val TRAIL: Color = Color.web("#6CC3E0")
    val ALERT: Color = Color.web("#D9553B")

    private val LOW_ALTITUDE: Color = Color.web("#FFBE55")
    private val HIGH_ALTITUDE: Color = Color.web("#F2F8FC")

    /**
     * Targets are tinted by altitude band — saturated amber down low, pale and
     * cool up high. It gives the display a vertical dimension without adding a
     * second symbol set.
     */
    fun altitudeTint(feet: Int?): Color {
        if (feet == null) return DIM
        val fraction = (feet / 45_000.0).coerceIn(0.0, 1.0)
        return LOW_ALTITUDE.interpolate(HIGH_ALTITUDE, fraction)
    }
}
