package org.tauasa.apps.adsb.model

import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.tauasa.apps.adsb.decode.CprFrame
import org.tauasa.apps.adsb.decode.ModeS
import org.tauasa.apps.adsb.decode.Position

/**
 * One tracked aircraft.
 *
 * All fields are JavaFX properties and are only ever touched on the FX
 * application thread — the receive thread hands decoded messages across a queue
 * rather than mutating this directly, which keeps the table bindings honest
 * without a single lock.
 */
class Aircraft(val icao: Int) {

    val address: String = ModeS.formatIcao(icao)

    val callsign = SimpleStringProperty("")
    val category = SimpleStringProperty("")
    val altitudeFeet = SimpleObjectProperty<Int?>(null)
    val groundSpeedKnots = SimpleObjectProperty<Int?>(null)
    val trackDegrees = SimpleObjectProperty<Int?>(null)
    val verticalRateFpm = SimpleObjectProperty<Int?>(null)
    val latitude = SimpleObjectProperty<Double?>(null)
    val longitude = SimpleObjectProperty<Double?>(null)
    val distanceNm = SimpleObjectProperty<Double?>(null)
    val bearingDegrees = SimpleObjectProperty<Double?>(null)
    val messageCount = SimpleIntegerProperty(0)
    val ageSeconds = SimpleIntegerProperty(0)
    val onGround = SimpleObjectProperty(false)

    var lastMessageMillis: Long = 0L
    var lastPositionMillis: Long = 0L

    /** Most recent even and odd CPR halves, held for pairing. */
    var evenFrame: CprFrame? = null
    var oddFrame: CprFrame? = null

    /** Recent fixes, newest last, for the trail on the situation display. */
    val trail: ArrayDeque<Position> = ArrayDeque()

    val position: Position?
        get() {
            val lat = latitude.get() ?: return null
            val lon = longitude.get() ?: return null
            return Position(lat, lon)
        }

    fun recordPosition(fix: Position, atMillis: Long) {
        latitude.set(fix.latitude)
        longitude.set(fix.longitude)
        lastPositionMillis = atMillis
        trail.addLast(fix)
        while (trail.size > MAX_TRAIL_POINTS) trail.removeFirst()
    }

    val label: String
        get() = callsign.get().ifBlank { address }

    companion object {
        const val MAX_TRAIL_POINTS = 60
    }
}
