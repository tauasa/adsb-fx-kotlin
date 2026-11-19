package org.tauasa.apps.adsb.model

import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.tauasa.apps.adsb.decode.AdsbMessage
import org.tauasa.apps.adsb.decode.Cpr
import org.tauasa.apps.adsb.decode.CprFrame
import org.tauasa.apps.adsb.decode.Position
import kotlin.math.roundToInt

/**
 * Holds the current picture: every aircraft heard recently, with positions
 * resolved from CPR pairs.
 *
 * FX-thread confined. [apply] is called from the UI pump, never from a source.
 */
class AircraftTracker(
    private val staleAfterMillis: Long = 60_000L,
) {

    /** Receiver position, used for range/bearing and as a CPR reference. */
    var receiverPosition: Position? = null

    val aircraft: ObservableList<Aircraft> = FXCollections.observableArrayList()
    private val byIcao = HashMap<Int, Aircraft>()

    var positionsResolved: Long = 0
        private set

    fun apply(message: AdsbMessage) {
        val target = byIcao.getOrPut(message.icao) {
            Aircraft(message.icao).also { aircraft.add(it) }
        }
        target.lastMessageMillis = message.receivedAtMillis
        target.messageCount.set(target.messageCount.get() + 1)

        when (message) {
            is AdsbMessage.Acquisition -> Unit

            is AdsbMessage.Identification -> {
                target.callsign.set(message.callsign)
                target.category.set(message.category)
            }

            is AdsbMessage.AirbornePosition -> {
                message.altitudeFeet?.let { target.altitudeFeet.set(it) }
                target.onGround.set(false)
                resolve(target, message.cpr)
            }

            is AdsbMessage.SurfacePosition -> {
                target.onGround.set(true)
                target.altitudeFeet.set(0)
                message.groundSpeedKnots?.let { target.groundSpeedKnots.set(it.roundToInt()) }
                message.trackDegrees?.let { target.trackDegrees.set(it.roundToInt()) }
                resolve(target, message.cpr)
            }

            is AdsbMessage.Velocity -> {
                message.groundSpeedKnots?.let { target.groundSpeedKnots.set(it.roundToInt()) }
                message.trackDegrees?.let { target.trackDegrees.set(it.roundToInt()) }
                message.headingDegrees?.let {
                    if (target.trackDegrees.get() == null) target.trackDegrees.set(it.roundToInt())
                }
                message.verticalRateFpm?.let { target.verticalRateFpm.set(it) }
            }
        }
    }

    private fun resolve(target: Aircraft, frame: CprFrame) {
        if (frame.odd) target.oddFrame = frame else target.evenFrame = frame

        val even = target.evenFrame
        val odd = target.oddFrame
        val paired = even != null && odd != null &&
            kotlin.math.abs(even.timestampMillis - odd.timestampMillis) <= Cpr.MAX_PAIR_AGE_MILLIS

        val previous = target.position
        val previousIsFresh = previous != null &&
            frame.timestampMillis - target.lastPositionMillis < POSITION_REFERENCE_VALIDITY_MILLIS

        val fix = when {
            paired -> Cpr.global(even!!, odd!!)

            // Local decoding needs a reference inside the aircraft's own latitude
            // zone. Its last fix qualifies. The receiver's position deliberately
            // does not: a wrong-zone local solve lands near whatever reference it
            // was given, so seeding from the receiver produces plausible-looking
            // positions that no range check can catch. Every aircraft's first fix
            // therefore comes from a matched pair, which costs a second or two.
            previousIsFresh -> Cpr.local(previous!!, frame)

            else -> null
        } ?: return

        if (!plausible(fix, if (previousIsFresh) previous else null)) return

        target.recordPosition(fix, frame.timestampMillis)
        positionsResolved++
        receiverPosition?.let { home ->
            target.distanceNm.set(Geo.distanceNm(home, fix))
            target.bearingDegrees.set(Geo.bearingDegrees(home, fix))
        }
    }

    /**
     * Reject fixes that cannot be real: outside the coordinate system, an
     * impossible jump from the previous fix, or beyond any range a 1090 MHz
     * receiver can hear.
     */
    private fun plausible(fix: Position, previous: Position?): Boolean {
        if (fix.latitude.isNaN() || fix.longitude.isNaN()) return false
        if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return false
        if (previous != null && Geo.distanceNm(previous, fix) > MAX_POSITION_JUMP_NM) return false
        val home = receiverPosition ?: return true
        return Geo.distanceNm(home, fix) <= MAX_PLAUSIBLE_RANGE_NM
    }

    /** Refresh derived per-tick state and drop contacts that have gone quiet. */
    fun tick(nowMillis: Long) {
        val expired = ArrayList<Aircraft>()
        for (target in aircraft) {
            val age = nowMillis - target.lastMessageMillis
            if (age > staleAfterMillis) {
                expired.add(target)
            } else {
                target.ageSeconds.set((age / 1000L).toInt())
            }
        }
        if (expired.isNotEmpty()) {
            aircraft.removeAll(expired)
            expired.forEach { byIcao.remove(it.icao) }
        }
    }

    fun clear() {
        aircraft.clear()
        byIcao.clear()
        positionsResolved = 0
    }

    val withPositionCount: Int
        get() = aircraft.count { it.position != null }

    companion object {
        const val MAX_PLAUSIBLE_RANGE_NM = 300.0

        /** A fix is usable as its own CPR reference for this long. */
        private const val POSITION_REFERENCE_VALIDITY_MILLIS = 60_000L

        /**
         * Furthest a locally decoded fix may sit from the previous one. A CPR zone
         * error displaces a position by a whole zone — six degrees, several hundred
         * miles — so anything past this is a decode fault rather than a fast aeroplane.
         */
        private const val MAX_POSITION_JUMP_NM = 60.0
    }
}
