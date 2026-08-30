package de.glichtner.rauchmelder.export

import de.glichtner.rauchmelder.data.Detector
import de.glichtner.rauchmelder.data.Inspection
import de.glichtner.rauchmelder.model.Protocol
import de.glichtner.rauchmelder.model.batteryLabel
import de.glichtner.rauchmelder.model.formatIsoDate
import de.glichtner.rauchmelder.model.formatIsoMonth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CSV export of all inspections (semicolon-separated for German Excel
 * locales; column headers stay German on purpose). Columns are the union of
 * both protocols; cells not applicable to a protocol stay empty.
 */
object CsvExporter {

    private val dateTimeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    private val HEADER = listOf(
        "Wohnung", "Zimmer", "Hersteller", "Modell", "Protokoll", "Melder-ID/Seriennummer",
        "Produktionsdatum", "Ersetzen bis", "Prüfdatum", "Ergebnis", "Hinweise",
        // Ei650i
        "Batterie (V)", "Batteriestatus", "Sensor", "Verschmutzung", "Betriebstage",
        "Testereignisse", "Batterie-leer-Ereignisse",
        // both
        "Alarme", "Demontagen",
        // Hekatron
        "Alarme letzte 3 Monate", "Letzter Alarm", "Letzter Selbsttest", "Gerätealter (Tage)",
        "Lagerbetrieb (h)", "Driftstatus", "Garantieflags", "Funkmodul", "Funkmodul-SN", "Funklinie",
        "Funkstörung (%)",
    )

    fun export(detectors: List<Detector>, inspections: List<Inspection>): String {
        val detectorById = detectors.associateBy { it.id }
        val builder = StringBuilder()
        builder.append(HEADER.joinToString(";")).append("\r\n")
        for (inspection in inspections) {
            val detector = detectorById[inspection.detectorId] ?: continue
            val protocol = Protocol.entries.firstOrNull { it.name == detector.protocol }
            val cells = listOf(
                detector.apartment,
                detector.room,
                detector.manufacturer,
                detector.model,
                protocol?.label ?: detector.protocol,
                detector.id,
                formatIsoDate(detector.manufactureDate),
                formatIsoMonth(detector.replacementMonth),
                formatTimestamp(inspection.timestamp),
                if (inspection.ok) "in Ordnung" else "auffällig",
                inspection.issues,
                inspection.batteryVoltage?.let { de(it, 2) } ?: "",
                inspection.batteryStatus?.let(::batteryLabel) ?: "",
                inspection.sensorOk?.let { if (it) "OK" else "Fehler" } ?: "",
                inspection.contaminationLevel?.let { de(it, 1) } ?: "",
                inspection.uptimeDays?.toString() ?: "",
                inspection.testCount?.toString() ?: "",
                inspection.lowBatteryCount?.toString() ?: "",
                inspection.alarmCount?.toString() ?: "",
                inspection.removalCount?.toString() ?: "",
                inspection.alarmsLast3Months?.toString() ?: "",
                inspection.lastAlarmDate?.let(::formatIsoDate) ?: "",
                inspection.lastSelfTestDate?.let(::formatIsoDate) ?: "",
                inspection.ageDays?.toString() ?: "",
                inspection.storageHours?.toString() ?: "",
                inspection.driftState?.toString() ?: "",
                inspection.warrantyFlags?.let { "0x%04x".format(it) } ?: "",
                inspection.radioModule ?: "",
                inspection.radioSerial ?: "",
                inspection.radioLine ?: "",
                inspection.radioInterferencePercent?.let { de(it, 1) } ?: "",
            )
            builder.append(cells.joinToString(";") { csv(it) }).append("\r\n")
        }
        return builder.toString()
    }

    fun formatTimestamp(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(dateTimeFormat)

    private fun de(value: Double, digits: Int): String = String.format("%.${digits}f", value).replace('.', ',')

    private fun csv(value: String): String =
        if (value.contains(';') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
