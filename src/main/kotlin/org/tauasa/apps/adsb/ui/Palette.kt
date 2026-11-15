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
    val RULE: Color = Color.web("#232D3A")
    val GRID: Color = Color.web("#1D2733")
    val TEXT: Color = Color.web("#C3CCD8")
    val DIM: Color = Color.web("#63717F")
    val AMBER: Color = Color.web("#F0A93B")
    val TRAIL: Color = Color.web("#4E9DB8")
    val ALERT: Color = Color.web("#D9553B")

    private val LOW_ALTITUDE: Color = Color.web("#F0A93B")
    private val HIGH_ALTITUDE: Color = Color.web("#D8E4EE")

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
