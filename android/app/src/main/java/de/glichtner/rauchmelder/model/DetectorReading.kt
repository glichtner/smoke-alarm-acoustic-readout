package de.glichtner.rauchmelder.model

import de.glichtner.rauchmelder.data.Inspection
import de.glichtner.rauchmelder.decoder.BatteryStatus
import de.glichtner.rauchmelder.decoder.DecodedFields
import de.glichtner.rauchmelder.decoder.smartsonic.SmartsonicFields
import de.glichtner.rauchmelder.decoder.smartsonic.SmartsonicPayload
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

enum class Protocol(val label: String, val manufacturer: String) {
    AUDIOLINK("AudioLINK+", "Ei Electronics"),
    SMARTSONIC("Smartsonic", "Hekatron"),
}

/**
 * Protocol-independent result of one acoustic readout: the detector's
 * identity/metadata plus an [Inspection] template (detectorId set to [id],
 * timestamp set to the readout time).
 */
data class DetectorReading(
    val protocol: Protocol,
    val id: String,
    val model: String,
    val manufactureDate: String,
    val replacementMonth: String,
    val inspection: Inspection,
) {
    val manufacturer: String get() = protocol.manufacturer
}

private val iso = DateTimeFormatter.ISO_LOCAL_DATE

/** Adds an issue when the replacement month ("yyyy-MM") has been reached. */
private fun replacementIssue(replacementMonth: String, issues: MutableList<String>, today: LocalDate = LocalDate.now()) {
    val month = runCatching { YearMonth.parse(replacementMonth) }.getOrNull() ?: return
    if (!YearMonth.from(today).isBefore(month)) {
        issues.add("Austauschfrist erreicht (${formatIsoMonth(replacementMonth)})")
    }
}

/** Ei650i: end of life is eleven calendar years after production. */
fun DecodedFields.toReading(timestamp: Long = System.currentTimeMillis()): DetectorReading {
    val issues = ArrayList<String>()
    if (!sensorOk) issues.add("Sensorfehler")
    when (batteryStatus) {
        BatteryStatus.RED -> issues.add("Batterie kritisch")
        BatteryStatus.AMBER -> issues.add("Batterie schwach")
        BatteryStatus.GREEN -> Unit
    }
    // contamination counts as high from raw value 27 (8.4 of 10)
    if (contaminationLevel != null && contaminationRaw >= 27) issues.add("Verschmutzung hoch (${String.format("%.1f", contaminationLevel).replace('.', ',')} von 10)")
    replacementIssue(replacementMonth, issues)
    val inspection = Inspection(
        detectorId = alarmId,
        timestamp = timestamp,
        protocol = Protocol.AUDIOLINK.name,
        ok = issues.isEmpty(),
        issues = issues.joinToString("; "),
        alarmCount = smokeAlarm.count,
        removalCount = removal.count,
        batteryVoltage = batteryVoltage,
        batteryStatus = batteryStatus.name,
        sensorOk = sensorOk,
        contaminationLevel = contaminationLevel,
        dustCalculating = dustCalculating,
        uptimeDays = uptimeDays,
        testCount = testButton.count,
        testAgeDays = testButton.ageDays,
        alarmAgeDays = smokeAlarm.ageDays,
        lowBatteryCount = lowBattery.count,
        lowBatteryAgeDays = lowBattery.ageDays,
        removalAgeDays = removal.ageDays,
        alarmsLast3Months = null,
        lastAlarmDate = null,
        lastSelfTestDate = null,
        ageDays = null,
        storageHours = null,
        warrantyFlags = null,
        statusRaw = null,
        driftState = null,
        batteryLow = null,
        deviceFault = null,
        radioFault = null,
        radioModule = null,
        radioSerial = null,
        radioLine = null,
        radioInterferencePercent = null,
        payloadHex = payloadHex,
    )
    return DetectorReading(
        protocol = Protocol.AUDIOLINK,
        id = alarmId,
        model = model,
        manufactureDate = manufactureDate,
        replacementMonth = replacementMonth,
        inspection = inspection,
    )
}

/**
 * Hekatron Genius: the detector is designed for ten years of operation, so
 * the replacement month is taken as production month plus ten years.
 */
fun SmartsonicFields.toReading(timestamp: Long = System.currentTimeMillis()): DetectorReading {
    val issues = ArrayList<String>()
    if (batteryLowFault) issues.add("Batterie schwach")
    if (deviceFault) issues.add("Gerätefehler")
    if (radioNetworkFault) issues.add("Funknetzfehler")
    if (dirtForecastNegative) issues.add("Verschmutzungsprognose negativ")
    for (flag in warrantyFlags) issues.add(SmartsonicPayload.WARRANTY_FLAG_LABELS[flag] ?: flag)
    radio?.let { r ->
        if ("fm_fault" in r.stateFlags) issues.add("Funkmodul-Fehler")
        if ("radio_link_error" in r.stateFlags) issues.add("Funkverbindungsfehler")
        if ("fm_battery_low_fault" in r.stateFlags) issues.add("Funkmodul-Batterie schwach")
    }
    val replacementMonth = productionDate.plusYears(10).format(DateTimeFormatter.ofPattern("yyyy-MM"))
    replacementIssue(replacementMonth, issues)
    val serial = detectorSerial.toString()
    val model = if (radio != null) "$productType + $radioProductType" else productType
    val inspection = Inspection(
        detectorId = serial,
        timestamp = timestamp,
        protocol = Protocol.SMARTSONIC.name,
        ok = issues.isEmpty(),
        issues = issues.joinToString("; "),
        alarmCount = alarmCount,
        removalCount = deinstallationCount,
        batteryVoltage = null,
        batteryStatus = null,
        sensorOk = null,
        contaminationLevel = null,
        dustCalculating = null,
        uptimeDays = null,
        testCount = null,
        testAgeDays = null,
        alarmAgeDays = null,
        lowBatteryCount = null,
        lowBatteryAgeDays = null,
        removalAgeDays = null,
        alarmsLast3Months = alarmCountLast3Months,
        lastAlarmDate = lastAlarmDate?.format(iso),
        lastSelfTestDate = lastSelftestDate?.format(iso),
        ageDays = productionAgeDays,
        storageHours = hoursInStorageMode,
        warrantyFlags = warrantyFlagsRaw,
        statusRaw = statusRaw,
        driftState = driftState,
        batteryLow = batteryLowFault,
        deviceFault = deviceFault,
        radioFault = radioNetworkFault,
        radioModule = radio?.let { radioProductType },
        radioSerial = radio?.serial?.toString(),
        radioLine = radio?.line,
        radioInterferencePercent = radio?.interferencePercent,
        payloadHex = payloadHex,
    )
    return DetectorReading(
        protocol = Protocol.SMARTSONIC,
        id = serial,
        model = model,
        manufactureDate = productionDate.format(iso),
        replacementMonth = replacementMonth,
        inspection = inspection,
    )
}
