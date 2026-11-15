package org.tauasa.apps.adsb.ui

import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import org.tauasa.apps.adsb.model.Aircraft

/**
 * The tabular half of the picture. Columns hold real types rather than
 * pre-formatted strings so that sorting by altitude sorts by altitude and not
 * by the first digit of a right-aligned string.
 */
object AircraftTable {

    fun create(): TableView<Aircraft> {
        val table = TableView<Aircraft>()
        table.styleClass.add("aircraft-table")
        table.placeholder = javafx.scene.control.Label("No aircraft heard yet.")

        val address = TableColumn<Aircraft, String>("ICAO")
        address.prefWidth = 76.0
        address.setCellValueFactory { ReadOnlyStringWrapper(it.value.address) }
        address.styleClass.add("mono-column")

        val callsign = TableColumn<Aircraft, String>("Callsign")
        callsign.prefWidth = 92.0
        callsign.setCellValueFactory { it.value.callsign }
        callsign.styleClass.add("mono-column")

        val altitude = numberColumn("Alt ft", 78.0, { it.altitudeFeet }) { "%,d".format(it) }
        val speed = numberColumn("GS kt", 66.0, { it.groundSpeedKnots }) { it.toString() }
        val track = numberColumn("Track", 62.0, { it.trackDegrees }) { "%03d°".format(it) }
        val vertical = numberColumn("V/S fpm", 76.0, { it.verticalRateFpm }) { "%+,d".format(it) }
        val distance = numberColumn("Range nm", 84.0, { it.distanceNm }) { "%.1f".format(it) }
        val bearing = numberColumn("Brg", 60.0, { it.bearingDegrees }) { "%03.0f°".format(it) }

        val messages = counterColumn("Msgs", 62.0) { it.messageCount }
        val age = counterColumn("Age s", 60.0) { it.ageSeconds }

        table.columns.add(address)
        table.columns.add(callsign)
        table.columns.add(altitude)
        table.columns.add(speed)
        table.columns.add(track)
        table.columns.add(vertical)
        table.columns.add(distance)
        table.columns.add(bearing)
        table.columns.add(messages)
        table.columns.add(age)
        table.sortOrder.add(distance)
        return table
    }

    private fun <T : Any> numberColumn(
        title: String,
        width: Double,
        value: (Aircraft) -> ObservableValue<T?>,
        format: (T) -> String,
    ): TableColumn<Aircraft, T?> {
        val column = TableColumn<Aircraft, T?>(title)
        column.prefWidth = width
        column.setCellValueFactory { value(it.value) }
        column.setCellFactory {
            object : TableCell<Aircraft, T?>() {
                override fun updateItem(item: T?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) "" else format(item)
                    alignment = Pos.CENTER_RIGHT
                }
            }
        }
        column.styleClass.add("mono-column")
        return column
    }

    private fun counterColumn(
        title: String,
        width: Double,
        value: (Aircraft) -> ObservableValue<Number>,
    ): TableColumn<Aircraft, Number> {
        val column = TableColumn<Aircraft, Number>(title)
        column.prefWidth = width
        column.setCellValueFactory { value(it.value) }
        column.setCellFactory {
            object : TableCell<Aircraft, Number>() {
                override fun updateItem(item: Number?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) "" else item.toString()
                    alignment = Pos.CENTER_RIGHT
                }
            }
        }
        column.styleClass.add("mono-column")
        return column
    }
}
