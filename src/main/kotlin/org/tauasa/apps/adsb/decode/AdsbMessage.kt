package org.tauasa.apps.adsb.decode

/** A decoded downlink message, keyed by the transmitting aircraft's address. */
sealed interface AdsbMessage {

    val icao: Int
    val receivedAtMillis: Long

    /** DF 11 all-call reply — carries no payload, but proves the aircraft is in range. */
    data class Acquisition(
        override val icao: Int,
        override val receivedAtMillis: Long,
    ) : AdsbMessage

    /** TC 1-4: callsign and wake vortex category. */
    data class Identification(
        override val icao: Int,
        override val receivedAtMillis: Long,
        val callsign: String,
        val category: String,
    ) : AdsbMessage

    /** TC 9-18 and 20-22: airborne position, half of a CPR pair. */
    data class AirbornePosition(
        override val icao: Int,
        override val receivedAtMillis: Long,
        val cpr: CprFrame,
        val altitudeFeet: Int?,
        val barometric: Boolean,
    ) : AdsbMessage

    /** TC 5-8: surface position and movement. */
    data class SurfacePosition(
        override val icao: Int,
        override val receivedAtMillis: Long,
        val cpr: CprFrame,
        val groundSpeedKnots: Double?,
        val trackDegrees: Double?,
    ) : AdsbMessage

    /** TC 19: airborne velocity, either ground referenced or airspeed. */
    data class Velocity(
        override val icao: Int,
        override val receivedAtMillis: Long,
        val groundSpeedKnots: Double?,
        val trackDegrees: Double?,
        val airspeedKnots: Double?,
        val headingDegrees: Double?,
        val verticalRateFpm: Int?,
        val supersonic: Boolean,
    ) : AdsbMessage
}

/** Wake vortex categories, indexed by type code then category subfield. */
internal fun wakeCategory(typeCode: Int, category: Int): String = when {
    category == 0 -> "—"
    typeCode == 2 && category == 1 -> "Surface emergency vehicle"
    typeCode == 2 && category == 3 -> "Surface service vehicle"
    typeCode == 2 && category >= 4 -> "Ground obstruction"
    typeCode == 3 && category == 1 -> "Glider"
    typeCode == 3 && category == 2 -> "Lighter-than-air"
    typeCode == 3 && category == 3 -> "Parachutist"
    typeCode == 3 && category == 4 -> "Ultralight"
    typeCode == 3 && category == 6 -> "UAV"
    typeCode == 3 && category == 7 -> "Space vehicle"
    typeCode == 4 && category == 1 -> "Light"
    typeCode == 4 && category == 2 -> "Medium 1"
    typeCode == 4 && category == 3 -> "Medium 2"
    typeCode == 4 && category == 4 -> "High vortex"
    typeCode == 4 && category == 5 -> "Heavy"
    typeCode == 4 && category == 6 -> "High performance"
    typeCode == 4 && category == 7 -> "Rotorcraft"
    else -> "—"
}
