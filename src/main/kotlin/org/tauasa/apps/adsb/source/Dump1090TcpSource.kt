package org.tauasa.apps.adsb.source

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads AVR-format frames from a dump1090 / readsb raw output port (30002 by
 * default). Reconnects on its own until closed, so pulling the dongle out and
 * plugging it back in does not require restarting the app.
 */
class Dump1090TcpSource(
    private val host: String = "127.0.0.1",
    private val port: Int = 30002,
    private val reconnectDelayMillis: Long = 3_000L,
) : AdsbSource {

    override val description: String = "dump1090 at $host:$port"

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile
    private var socket: Socket? = null

    override val running: Boolean get() = active.get()

    override fun start(onFrame: FrameSink, onEvent: (SourceEvent) -> Unit) {
        if (!active.compareAndSet(false, true)) return
        worker = Thread({ loop(onFrame, onEvent) }, "adsb-tcp-$host-$port").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(onFrame: FrameSink, onEvent: (SourceEvent) -> Unit) {
        while (active.get()) {
            try {
                Socket().use { s ->
                    socket = s
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
                    s.soTimeout = READ_TIMEOUT_MILLIS
                    onEvent(SourceEvent.Connected(description))
                    BufferedReader(InputStreamReader(s.getInputStream())).use { reader ->
                        while (active.get()) {
                            val line = reader.readLine() ?: break
                            AvrLine.parse(line)?.let(onFrame)
                        }
                    }
                }
                if (active.get()) onEvent(SourceEvent.Disconnected("$description closed the stream"))
            } catch (e: Exception) {
                if (active.get()) {
                    onEvent(SourceEvent.Failed(e.message ?: e.javaClass.simpleName))
                }
            } finally {
                socket = null
            }
            if (!active.get()) break
            try {
                Thread.sleep(reconnectDelayMillis)
            } catch (_: InterruptedException) {
                break // close() interrupted the wait
            }
        }
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        worker?.interrupt()
        worker = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 4_000
        const val READ_TIMEOUT_MILLIS = 0 // block indefinitely; dump1090 is chatty enough
    }
}
