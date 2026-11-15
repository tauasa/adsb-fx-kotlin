package org.tauasa.apps.adsb.model

import org.tauasa.apps.adsb.decode.Position
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Distances here are nautical miles, because that is what the rest of aviation uses. */
object Geo {

    private const val EARTH_RADIUS_NM = 3440.065

    fun distanceNm(from: Position, to: Position): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_NM * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial great-circle bearing from [from] to [to], degrees true. */
    fun bearingDegrees(from: Position, to: Position): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
