package org.tauasa.apps.adsb.source

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Launches dump1090 against the dongle and reads AVR frames from its stdout.
 *
 * This is the "own the whole pipeline" option: nothing has to be running
 * beforehand, and the process dies with the app. The trade-off against the TCP
 * source is that only one program can hold the USB device at a time, so this
 * conflicts with an already-running receiver.
 */
class Dump1090ProcessSource(
    private val executable: String = "dump1090",
    private val deviceIndex: Int = 0,
    private val gain: String? = null,
    private val ppmCorrection: Int? = null,
    private val extraArguments: List<String> = emptyList(),
) : AdsbSource {

    override val description: String = "$executable (device $deviceIndex)"

    private val active = AtomicBoolean(false)
    private var process: Process? = null
    private var reader: Thread? = null
    private var stderrPump: Thread? = null

    override val running: Boolean get() = active.get() && process?.isAlive == true

    fun command(): List<String> = buildList {
        add(executable)
        add("--raw")
        add("--device-index"); add(deviceIndex.toString())
        if (gain != null) { add("--gain"); add(gain) }
        if (ppmCorrection != null) { add("--ppm"); add(ppmCorrection.toString()) }
        addAll(extraArguments)
    }

    override fun start(onFrame: FrameSink, onEvent: (SourceEvent) -> Unit) {
        if (!active.compareAndSet(false, true)) return
        val started = try {
            ProcessBuilder(command()).start()
        } catch (e: Exception) {
            active.set(false)
            onEvent(
                SourceEvent.Failed(
                    "Could not start $executable — check it is installed and on PATH (${e.message})"
                )
            )
            return
        }
        process = started
        onEvent(SourceEvent.Connected(command().joinToString(" ")))

        reader = Thread({
            BufferedReader(InputStreamReader(started.inputStream)).use { input ->
                while (active.get()) {
                    val line = input.readLine() ?: break
                    AvrLine.parse(line)?.let(onFrame)
                }
            }
            if (active.get()) {
                // The stream can close before the process does, so ask for the
                // exit code rather than assuming one is available.
                val code = runCatching { started.waitFor() }.getOrNull()
                onEvent(SourceEvent.Disconnected("$executable stopped${code?.let { " (exit $it)" } ?: ""}"))
                active.set(false)
            }
        }, "adsb-process-reader").apply { isDaemon = true; start() }

        // dump1090 reports tuner errors on stderr; surfacing the last line is
        // the difference between "no aircraft" and "no dongle".
        stderrPump = Thread({
            BufferedReader(InputStreamReader(started.errorStream)).use { input ->
                while (active.get()) {
                    val line = input.readLine() ?: break
                    if (line.isNotBlank()) onEvent(SourceEvent.Failed(line.trim()))
                }
            }
        }, "adsb-process-stderr").apply { isDaemon = true; start() }
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        reader = null
        stderrPump = null
    }
}
