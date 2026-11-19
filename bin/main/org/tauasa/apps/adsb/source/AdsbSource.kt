package org.tauasa.apps.adsb.source

import org.tauasa.apps.adsb.decode.ModeS

/** Receives raw Mode S frames off the source thread. */
typealias FrameSink = (ByteArray) -> Unit

/** Lifecycle notifications, delivered off the FX thread. */
sealed interface SourceEvent {
    data class Connected(val detail: String) : SourceEvent
    data class Disconnected(val detail: String) : SourceEvent
    data class Failed(val detail: String) : SourceEvent
}

/**
 * A supply of Mode S frames.
 *
 * Implementations own their threads and must tolerate [close] being called at
 * any point, including mid-connect.
 */
interface AdsbSource : AutoCloseable {
    val description: String
    val running: Boolean
    fun start(onFrame: FrameSink, onEvent: (SourceEvent) -> Unit)
}

/**
 * The AVR text format that dump1090, readsb and friends emit on port 30002 and
 * with `--raw`: a hex frame wrapped in `*...;`, or `@...;` when a 12-digit
 * receiver timestamp is prefixed.
 */
object AvrLine {
    fun parse(line: String): ByteArray? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return when (trimmed[0]) {
            '*' -> ModeS.parseHex(trimmed.substring(1))
            '@' -> if (trimmed.length > 13) ModeS.parseHex(trimmed.substring(13)) else null
            else -> null
        }
    }
}
