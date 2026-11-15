package org.tauasa.apps.adsb.ui

import javafx.animation.AnimationTimer
import javafx.css.PseudoClass
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.tauasa.apps.adsb.decode.AdsbDecoder
import org.tauasa.apps.adsb.decode.AdsbMessage
import org.tauasa.apps.adsb.decode.Position
import org.tauasa.apps.adsb.model.Aircraft
import org.tauasa.apps.adsb.model.AircraftTracker
import org.tauasa.apps.adsb.source.AdsbSource
import org.tauasa.apps.adsb.source.Dump1090ProcessSource
import org.tauasa.apps.adsb.source.Dump1090TcpSource
import org.tauasa.apps.adsb.source.SimulatedSource
import org.tauasa.apps.adsb.source.SourceEvent
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class AdsbApp : Application() {

    private enum class SourceKind(val display: String) {
        TCP("dump1090 over TCP"),
        PROCESS("Launch dump1090"),
        SIMULATED("Simulated traffic"),
        ;

        override fun toString(): String = display
    }

    private val tracker = AircraftTracker()
    private val inbound = ConcurrentLinkedQueue<AdsbMessage>()
    private val framesSeen = AtomicLong()
    private val framesDecoded = AtomicLong()

    private var source: AdsbSource? = null
    private var pump: AnimationTimer? = null

    private val sourceChoice = ChoiceBox<SourceKind>()
    private val hostField = TextField("127.0.0.1")
    private val portField = TextField("30002")
    private val executableField = TextField("dump1090")
    private val latitudeField = TextField()
    private val longitudeField = TextField()
    private val rangeChoice = ChoiceBox<Int>()
    private val connectButton = Button("Start receiving")

    private val statusLabel = Label("Idle")
    private val rateLabel = Label("—")
    private val countLabel = Label("0 aircraft")

    // The table shows the tracker's live list directly. Order in that list carries
    // no meaning (lookups go through the tracker's ICAO map), so the periodic
    // table.sort() in the pump is free to reorder it in place.
    private val table: TableView<Aircraft> = AircraftTable.create().apply { items = tracker.aircraft }
    private val display = SituationDisplay(tracker)

    override fun start(stage: Stage) {
        buildControls()
        wireSelection()

        val root = BorderPane().apply {
            styleClass.add("root-pane")
            top = VBox(header(), controlBar())
            center = centreSplit()
            bottom = statusBar()
        }

        val scene = Scene(root, 1360.0, 840.0)
        scene.stylesheets.add(javaClass.getResource("/org/tauasa/apps/adsb/adsb.css")!!.toExternalForm())

        stage.title = "adsb-fx"
        stage.scene = scene
        stage.show()

        startPump()
    }

    // ---------------------------------------------------------------- layout

    private fun header(): Region {
        val title = Label("ADS-B RECEIVER").apply { styleClass.add("app-title") }
        val subtitle = Label("1090 MHz extended squitter").apply { styleClass.add("app-subtitle") }
        return HBox(12.0, title, subtitle).apply {
            styleClass.add("header-bar")
            alignment = Pos.BASELINE_LEFT
        }
    }

    private fun controlBar(): Region {
        val bar = HBox(10.0).apply {
            styleClass.add("control-bar")
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0, 14.0, 10.0, 14.0)
        }

        val tcpFields = HBox(6.0, field("Host", hostField, 128.0), field("Port", portField, 68.0))
        val processFields = HBox(6.0, field("Command", executableField, 150.0))

        tcpFields.visibleProperty().bind(sourceChoice.valueProperty().isEqualTo(SourceKind.TCP))
        tcpFields.managedProperty().bind(tcpFields.visibleProperty())
        processFields.visibleProperty().bind(sourceChoice.valueProperty().isEqualTo(SourceKind.PROCESS))
        processFields.managedProperty().bind(processFields.visibleProperty())

        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }

        bar.children.addAll(
            field("Source", sourceChoice, 160.0),
            tcpFields,
            processFields,
            connectButton,
            spacer,
            field("Receiver lat", latitudeField, 96.0),
            field("Receiver lon", longitudeField, 96.0),
            field("Range nm", rangeChoice, 90.0),
        )
        return bar
    }

    private fun field(label: String, control: Region, width: Double): Region {
        control.prefWidth = width
        val caption = Label(label.uppercase()).apply { styleClass.add("field-label") }
        return VBox(3.0, caption, control)
    }

    private fun centreSplit(): Region {
        display.styleClass.add("situation-display")
        val split = SplitPane(display, table)
        split.orientation = Orientation.HORIZONTAL
        split.setDividerPositions(0.56)
        SplitPane.setResizableWithParent(table, true)
        return split
    }

    private fun statusBar(): Region {
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        listOf(statusLabel, rateLabel, countLabel).forEach { it.styleClass.add("status-item") }
        return HBox(18.0, statusLabel, spacer, rateLabel, countLabel).apply {
            styleClass.add("status-bar")
            alignment = Pos.CENTER_LEFT
            padding = Insets(7.0, 14.0, 7.0, 14.0)
        }
    }

    // --------------------------------------------------------------- wiring

    private fun buildControls() {
        sourceChoice.items.addAll(SourceKind.entries)
        sourceChoice.value = SourceKind.TCP

        rangeChoice.items.addAll(20, 60, 120, 250)
        rangeChoice.value = 120
        rangeChoice.valueProperty().addListener { _, _, value ->
            value?.let { display.rangeNm.set(it.toDouble()) }
        }

        latitudeField.promptText = "e.g. 38.7000"
        longitudeField.promptText = "e.g. -121.2000"
        latitudeField.textProperty().addListener { _, _, _ -> updateReceiverPosition() }
        longitudeField.textProperty().addListener { _, _, _ -> updateReceiverPosition() }

        connectButton.setOnAction { if (source == null) startSource() else stopSource() }
    }

    private fun wireSelection() {
        table.selectionModel.selectedItemProperty().addListener { _, _, value ->
            display.selected.set(value)
        }
        display.selected.addListener { _, _, value ->
            if (value != null && table.selectionModel.selectedItem !== value) {
                table.selectionModel.select(value)
                table.scrollTo(value)
            }
        }
    }

    private fun updateReceiverPosition() {
        val latitude = parseCoordinate(latitudeField.text)
        val longitude = parseCoordinate(longitudeField.text)
        val latitudeOk = latitude != null && latitude in -90.0..90.0
        val longitudeOk = longitude != null && longitude in -180.0..180.0

        tracker.receiverPosition =
            if (latitudeOk && longitudeOk) Position(latitude!!, longitude!!) else null

        // Mark a field only once it has something in it — an empty field is not
        // yet an error, and reddening it before the person has typed is noise.
        latitudeField.pseudoClassStateChanged(INVALID, !latitudeOk && latitudeField.text.isNotBlank())
        longitudeField.pseudoClassStateChanged(INVALID, !longitudeOk && longitudeField.text.isNotBlank())

        display.draw()
    }

    /**
     * Parse a coordinate leniently. Decimal commas and a trailing degree sign are
     * how people actually type latitudes, and rejecting them silently leaves the
     * app looking broken for a reason nothing on screen explains.
     */
    private fun parseCoordinate(text: String?): Double? =
        text?.trim()?.replace('\u00B0', ' ')?.replace(',', '.')?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toDoubleOrNull()

    // -------------------------------------------------------------- sources

    private fun startSource() {
        val kind = sourceChoice.value ?: return
        val created = try {
            when (kind) {
                SourceKind.TCP -> Dump1090TcpSource(
                    host = hostField.text.trim().ifEmpty { "127.0.0.1" },
                    port = portField.text.trim().toIntOrNull() ?: 30002,
                )

                SourceKind.PROCESS -> Dump1090ProcessSource(
                    executable = executableField.text.trim().ifEmpty { "dump1090" },
                )

                SourceKind.SIMULATED -> {
                    // Fill the fields in rather than refusing to start: the centre
                    // of a simulation is arbitrary, so asking the person to supply
                    // one is a demand the app can meet itself.
                    val centre = tracker.receiverPosition ?: DEFAULT_SIMULATION_CENTRE.also {
                        latitudeField.text = "%.4f".format(Locale.ROOT, it.latitude)
                        longitudeField.text = "%.4f".format(Locale.ROOT, it.longitude)
                    }
                    SimulatedSource(centre = centre)
                }
            }
        } catch (e: Exception) {
            statusLabel.text = "Could not start source: ${e.message}"
            return
        }

        framesSeen.set(0)
        framesDecoded.set(0)
        tracker.clear()

        created.start(
            onFrame = { frame ->
                framesSeen.incrementAndGet()
                AdsbDecoder.decode(frame)?.let { message ->
                    framesDecoded.incrementAndGet()
                    // Drop rather than grow without bound if the UI ever falls behind.
                    if (inbound.size < MAX_QUEUE_DEPTH) inbound.add(message)
                }
            },
            onEvent = { event -> Platform.runLater { report(event) } },
        )

        source = created
        connectButton.text = "Stop"
        statusLabel.text = "Connecting to ${created.description}…"
        setSourceControlsDisabled(true)
    }

    private fun stopSource() {
        source?.close()
        source = null
        connectButton.text = "Start receiving"
        statusLabel.text = "Stopped"
        setSourceControlsDisabled(false)
    }

    private fun setSourceControlsDisabled(disabled: Boolean) {
        sourceChoice.isDisable = disabled
        hostField.isDisable = disabled
        portField.isDisable = disabled
        executableField.isDisable = disabled
    }

    private fun report(event: SourceEvent) {
        statusLabel.text = when (event) {
            is SourceEvent.Connected -> "Receiving from ${event.detail}"
            is SourceEvent.Disconnected -> "${event.detail} — retrying"
            is SourceEvent.Failed -> event.detail
        }
        statusLabel.styleClass.removeAll("status-error")
        if (event is SourceEvent.Failed) statusLabel.styleClass.add("status-error")
    }

    // ----------------------------------------------------------------- pump

    /**
     * Everything that touches the model runs here, on the FX thread. Sources
     * only ever hand over immutable decoded messages, which is what lets the
     * table bind directly to the aircraft objects without synchronisation.
     */
    private fun startPump() {
        pump = object : AnimationTimer() {
            private var lastTickMillis = 0L
            private var lastRateMillis = 0L
            private var framesAtLastRate = 0L

            override fun handle(nowNanos: Long) {
                var drained = 0
                while (drained < MAX_DRAIN_PER_FRAME) {
                    val message = inbound.poll() ?: break
                    tracker.apply(message)
                    drained++
                }

                val nowMillis = nowNanos / 1_000_000
                if (nowMillis - lastTickMillis >= REDRAW_INTERVAL_MILLIS) {
                    lastTickMillis = nowMillis
                    tracker.tick(System.currentTimeMillis())
                    table.sort()
                    display.draw()
                    countLabel.text =
                        "${tracker.aircraft.size} aircraft · ${tracker.withPositionCount} with position"
                }

                if (nowMillis - lastRateMillis >= 1_000) {
                    val elapsed = (nowMillis - lastRateMillis).coerceAtLeast(1)
                    val total = framesSeen.get()
                    val perSecond = (total - framesAtLastRate) * 1000.0 / elapsed
                    framesAtLastRate = total
                    lastRateMillis = nowMillis
                    rateLabel.text = "%.0f frames/s · %,d decoded of %,d".format(
                        perSecond, framesDecoded.get(), total,
                    )
                }
            }
        }
        pump?.start()
    }

    override fun stop() {
        pump?.stop()
        source?.close()
    }

    private companion object {
        /** Styling hook for a coordinate field that has text in it but no usable number. */
        val INVALID: PseudoClass = PseudoClass.getPseudoClass("invalid")

        /**
         * Where simulated traffic orbits when no receiver position has been entered.
         * The value is arbitrary — a simulation needs a centre, not a true one.
         */
        val DEFAULT_SIMULATION_CENTRE = Position(38.70, -121.20)

        const val MAX_DRAIN_PER_FRAME = 400
        const val MAX_QUEUE_DEPTH = 20_000
        const val REDRAW_INTERVAL_MILLIS = 250L
    }
}
