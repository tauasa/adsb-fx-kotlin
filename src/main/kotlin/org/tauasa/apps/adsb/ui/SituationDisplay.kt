package org.tauasa.apps.adsb.ui

import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.TextAlignment
import org.tauasa.apps.adsb.decode.Position
import org.tauasa.apps.adsb.model.Aircraft
import org.tauasa.apps.adsb.model.AircraftTracker
import org.tauasa.apps.adsb.model.Geo
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Plan position display centred on the receiver.
 *
 * Deliberately not a sweeping radar scope — ADS-B is a continuous broadcast,
 * not a rotating antenna, and pretending otherwise would misrepresent what the
 * data is. Instead each target carries the two things a controller reads first:
 * a leader line showing where it will be in one minute, and a data block giving
 * callsign, flight level and groundspeed.
 */
class SituationDisplay(private val tracker: AircraftTracker) : Pane() {

    val rangeNm = SimpleDoubleProperty(120.0)
    val selected = SimpleObjectProperty<Aircraft?>(null)

    private val canvas = Canvas()
    private val hitBoxes = ArrayList<Triple<Aircraft, Double, Double>>()

    private val dataFont: Font = Font.font(MONO_FAMILY, 10.5)
    private val labelFont: Font = Font.font(MONO_FAMILY, 9.5)

    init {
        children.add(canvas)
        canvas.widthProperty().bind(widthProperty())
        canvas.heightProperty().bind(heightProperty())
        widthProperty().addListener { _, _, _ -> draw() }
        heightProperty().addListener { _, _, _ -> draw() }
        rangeNm.addListener { _, _, _ -> draw() }
        selected.addListener { _, _, _ -> draw() }
        canvas.setOnMouseClicked { event -> selected.set(nearestTo(event.x, event.y)) }
    }

    private fun nearestTo(x: Double, y: Double): Aircraft? =
        hitBoxes.minByOrNull { hypot(it.second - x, it.third - y) }
            ?.takeIf { hypot(it.second - x, it.third - y) < 18.0 }
            ?.first

    fun draw() {
        val g = canvas.graphicsContext2D
        val width = canvas.width
        val height = canvas.height
        if (width <= 0 || height <= 0) return

        g.fill = Palette.INK
        g.fillRect(0.0, 0.0, width, height)

        val home = tracker.receiverPosition
        if (home == null) {
            drawEmptyState(g, width, height)
            return
        }

        val centreX = width / 2
        val centreY = height / 2
        val radius = min(width, height) / 2 - MARGIN
        if (radius <= 20) return

        drawRangeRings(g, centreX, centreY, radius)
        drawCompass(g, centreX, centreY, radius)

        hitBoxes.clear()
        val pixelsPerNm = radius / rangeNm.get()
        for (target in tracker.aircraft) {
            val fix = target.position ?: continue
            val distance = Geo.distanceNm(home, fix)
            if (distance > rangeNm.get()) continue
            val bearing = Math.toRadians(Geo.bearingDegrees(home, fix))
            val x = centreX + sin(bearing) * distance * pixelsPerNm
            val y = centreY - cos(bearing) * distance * pixelsPerNm
            drawTrail(g, target, home, centreX, centreY, pixelsPerNm)
            drawTarget(g, target, x, y, pixelsPerNm)
            hitBoxes.add(Triple(target, x, y))
        }
    }

    private fun drawEmptyState(g: GraphicsContext, width: Double, height: Double) {
        g.fill = Palette.DIM
        g.font = Font.font(MONO_FAMILY, 12.0)
        g.textAlign = TextAlignment.CENTER
        g.fillText("Enter the receiver's latitude and longitude to plot targets.", width / 2, height / 2)
        g.textAlign = TextAlignment.LEFT
    }

    private fun drawRangeRings(g: GraphicsContext, cx: Double, cy: Double, radius: Double) {
        g.lineWidth = 1.0
        g.font = labelFont
        for (step in 1..RING_COUNT) {
            val r = radius * step / RING_COUNT
            g.stroke = if (step == RING_COUNT) Palette.RULE else Palette.GRID
            g.strokeOval(cx - r, cy - r, r * 2, r * 2)
            val label = "${(rangeNm.get() * step / RING_COUNT).roundToInt()}"
            g.fill = Palette.DIM
            g.fillText(label, cx + 4, cy - r - 3)
        }
        // Receiver mark: a small cross rather than a dot, so it never reads as traffic.
        g.stroke = Palette.TRAIL
        g.strokeLine(cx - 5, cy, cx + 5, cy)
        g.strokeLine(cx, cy - 5, cx, cy + 5)
    }

    private fun drawCompass(g: GraphicsContext, cx: Double, cy: Double, radius: Double) {
        g.font = labelFont
        for (degrees in 0 until 360 step 10) {
            val angle = Math.toRadians(degrees.toDouble())
            val major = degrees % 30 == 0
            val inner = radius - if (major) 9.0 else 5.0
            g.stroke = if (major) Palette.RULE else Palette.GRID
            g.strokeLine(
                cx + sin(angle) * inner,
                cy - cos(angle) * inner,
                cx + sin(angle) * radius,
                cy - cos(angle) * radius,
            )
            if (major) {
                g.fill = Palette.DIM
                g.textAlign = TextAlignment.CENTER
                g.fillText(
                    String.format("%03d", degrees),
                    cx + sin(angle) * (radius - 20),
                    cy - cos(angle) * (radius - 20) + 3,
                )
                g.textAlign = TextAlignment.LEFT
            }
        }
    }

    private fun drawTrail(
        g: GraphicsContext,
        target: Aircraft,
        home: Position,
        cx: Double,
        cy: Double,
        pixelsPerNm: Double,
    ) {
        if (target.trail.size < 2) return
        g.stroke = Palette.TRAIL.deriveColor(0.0, 1.0, 1.0, 0.45)
        g.lineWidth = 1.0
        var previous: Pair<Double, Double>? = null
        for (fix in target.trail) {
            val distance = Geo.distanceNm(home, fix)
            val bearing = Math.toRadians(Geo.bearingDegrees(home, fix))
            val point = Pair(
                cx + sin(bearing) * distance * pixelsPerNm,
                cy - cos(bearing) * distance * pixelsPerNm,
            )
            previous?.let { g.strokeLine(it.first, it.second, point.first, point.second) }
            previous = point
        }
    }

    private fun drawTarget(
        g: GraphicsContext,
        target: Aircraft,
        x: Double,
        y: Double,
        pixelsPerNm: Double,
    ) {
        val tint: Color = Palette.altitudeTint(target.altitudeFeet.get())
        val isSelected = selected.get() === target
        val track = target.trackDegrees.get()

        // Leader line: one minute of travel at present groundspeed. Length is
        // the information, so it is drawn before the symbol sits on top of it.
        val speed = target.groundSpeedKnots.get()
        if (track != null && speed != null && speed > 0) {
            val angle = Math.toRadians(track.toDouble())
            val leaderNm = speed / 60.0
            g.stroke = tint.deriveColor(0.0, 1.0, 1.0, 0.55)
            g.lineWidth = 1.0
            g.strokeLine(x, y, x + sin(angle) * leaderNm * pixelsPerNm, y - cos(angle) * leaderNm * pixelsPerNm)
        }

        g.fill = tint
        g.stroke = tint
        if (track != null) {
            drawChevron(g, x, y, track.toDouble())
        } else {
            g.fillOval(x - 2.5, y - 2.5, 5.0, 5.0)
        }

        if (isSelected) {
            g.stroke = Palette.AMBER
            g.lineWidth = 1.2
            g.strokeOval(x - 11, y - 11, 22.0, 22.0)
        }

        drawDataBlock(g, target, x, y, tint, isSelected)
    }

    private fun drawChevron(g: GraphicsContext, x: Double, y: Double, headingDegrees: Double) {
        val angle = Math.toRadians(headingDegrees)
        val nose = 7.0
        val tail = 4.5
        val spread = Math.toRadians(140.0)
        val xs = doubleArrayOf(
            x + sin(angle) * nose,
            x + sin(angle + spread) * tail,
            x + sin(angle - spread) * tail,
        )
        val ys = doubleArrayOf(
            y - cos(angle) * nose,
            y - cos(angle + spread) * tail,
            y - cos(angle - spread) * tail,
        )
        g.fillPolygon(xs, ys, 3)
    }

    private fun drawDataBlock(
        g: GraphicsContext,
        target: Aircraft,
        x: Double,
        y: Double,
        tint: Color,
        isSelected: Boolean,
    ) {
        g.font = dataFont
        g.fill = if (isSelected) Palette.AMBER else tint.deriveColor(0.0, 1.0, 1.0, 0.9)
        g.fillText(target.label, x + 10, y - 4)

        val flightLevel = target.altitudeFeet.get()?.let { String.format("%03d", it / 100) } ?: "---"
        val speed = target.groundSpeedKnots.get()?.let { String.format("%02d", it / 10) } ?: "--"
        val climb = target.verticalRateFpm.get()?.let {
            when {
                it > 200 -> "↑"
                it < -200 -> "↓"
                else -> " "
            }
        } ?: " "
        g.fill = Palette.DIM
        g.fillText("$flightLevel$climb $speed", x + 10, y + 7)
    }

    private companion object {
        const val MARGIN = 26.0
        const val RING_COUNT = 4
        const val MONO_FAMILY = "Monospaced"
    }
}
