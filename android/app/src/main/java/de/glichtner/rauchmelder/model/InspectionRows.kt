package de.glichtner.rauchmelder.model

import de.glichtner.rauchmelder.data.Inspection

/** "2025-09-15" -> "15.09.2025" */
fun formatIsoDate(isoDate: String): String {
    val parts = isoDate.split("-")
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else isoDate
}

/** "2036-09" -> "09/2036" */
fun formatIsoMonth(isoMonth: String): String {
    val parts = isoMonth.split("-")
    return if (parts.size == 2) "${parts[1]}/${parts[0]}" else isoMonth
}

fun batteryLabel(status: String?): String = when (status) {
    "GREEN" -> "gut"
    "AMBER" -> "schwach"
    "RED" -> "kritisch"
    null -> "–"
    else -> status
}

private fun de(value: Double, digits: Int): String = String.format("%.${digits}f", value).replace('.', ',')

/**
 * Human-readable (German) label/value rows describing an inspection, in a
 * protocol-specific order. Used by the scan result, the inspection log, and
 * the PDF report so all three show the same data.
 */
fun inspectionRows(inspection: Inspection): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>()
    when (inspection.protocol) {
        Protocol.AUDIOLINK.name -> {
            rows.add(
                "Batterie" to (inspection.batteryVoltage?.let { "${de(it, 2)} V (${batteryLabel(inspection.batteryStatus)})" } ?: "–"),
            )
            rows.add("Sensor" to if (inspection.sensorOk == true) "OK" else "FEHLER")
            rows.add(
                "Verschmutzung" to (
                    inspection.contaminationLevel?.let { "${de(it, 1)} von 10" }
                        ?: if (inspection.dustCalculating == true) "wird berechnet" else "nicht verfügbar"
                    ),
            )
            rows.add("Betriebsdauer" to "${inspection.uptimeDays ?: 0} Tage")
            rows.add(
                "Testauslösungen" to "${inspection.testCount ?: 0}" +
                    (inspection.testAgeDays?.let { " (vor $it Tagen)" } ?: ""),
            )
            rows.add("Rauchalarme" to "${inspection.alarmCount ?: 0}")
            rows.add("Batterie-leer-Ereignisse" to "${inspection.lowBatteryCount ?: 0}")
            rows.add("Demontagen" to "${inspection.removalCount ?: 0}")
        }
        Protocol.SMARTSONIC.name -> {
            rows.add("Batterie" to if (inspection.batteryLow == true) "schwach" else "gut")
            rows.add(
                "Störungen" to buildList {
                    if (inspection.deviceFault == true) add("Gerätefehler")
                    if (inspection.radioFault == true) add("Funknetzfehler")
                }.ifEmpty { listOf("keine") }.joinToString(", "),
            )
            rows.add("Driftstatus" to "${inspection.driftState ?: 0}")
            rows.add("Alarme gesamt" to "${inspection.alarmCount ?: 0}")
            rows.add("Alarme letzte 3 Monate" to "${inspection.alarmsLast3Months ?: 0}")
            rows.add("Letzter Alarm" to (inspection.lastAlarmDate?.let(::formatIsoDate) ?: "–"))
            rows.add("Letzter Selbsttest" to (inspection.lastSelfTestDate?.let(::formatIsoDate) ?: "–"))
            rows.add("Demontagen" to "${inspection.removalCount ?: 0}")
            rows.add("Gerätealter" to "${inspection.ageDays ?: 0} Tage")
            rows.add("Lagerbetrieb" to "${inspection.storageHours ?: 0} Stunden")
            rows.add(
                "Garantie" to if ((inspection.warrantyFlags ?: 0) == 0) "möglich"
                else "eingeschränkt (0x%04x)".format(inspection.warrantyFlags),
            )
            if (inspection.radioModule != null) {
                rows.add("Funkmodul" to inspection.radioModule)
                rows.add("Funkmodul-SN" to (inspection.radioSerial ?: "–"))
                rows.add("Funklinie" to (inspection.radioLine ?: "–"))
                rows.add("Funkstörung" to (inspection.radioInterferencePercent?.let { "${de(it, 1)} %" } ?: "–"))
            }
        }
    }
    if (inspection.issues.isNotBlank()) rows.add("Probleme" to inspection.issues)
    return rows
}
