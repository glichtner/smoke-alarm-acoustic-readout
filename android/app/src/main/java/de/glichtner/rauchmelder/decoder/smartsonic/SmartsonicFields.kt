package de.glichtner.rauchmelder.decoder.smartsonic

import java.time.LocalDate

/** Radio-module extension of a 32-byte Smartsonic payload. */
data class SmartsonicRadio(
    val stateRaw: Int,
    val stateFlags: List<String>,
    val serial: Long,
    val serialHex: String,
    val lineId: Long,
    val line: String,
    val switchRaw: Int,
    val switchFlags: List<String>,
    val interferenceRaw: Int,
    val interferencePercent: Double,
)

/** Parsed Smartsonic payload (see protocol/smartsonic/PROTOCOL.md). */
data class SmartsonicFields(
    val unknownByte0: Int,
    val detectorSerial: Long,
    val detectorSerialHex: String,
    val productTypeCode: Int,
    val productType: String,
    val radioProductTypeCode: Int,
    val radioProductType: String,
    val deinstallationCount: Int,
    val alarmCount: Int,
    val alarmCountLast3Months: Int,
    val lastAlarmOffsetDays: Int?,
    val productionAgeDays: Int,
    val hoursInStorageMode: Int,
    val lastSelftestOffsetDays: Int?,
    val readoutReferenceDate: LocalDate,
    val productionDate: LocalDate,
    val lastAlarmDate: LocalDate?,
    val lastSelftestDate: LocalDate?,
    val warrantyFlagsRaw: Int,
    val warrantyFlags: List<String>,
    val statusRaw: Int,
    val batteryLowFault: Boolean,
    val deviceFault: Boolean,
    val radioNetworkFault: Boolean,
    val driftState: Int,
    val dirtForecastNegative: Boolean,
    val radio: SmartsonicRadio?,
    val payloadHex: String,
) {
    val warrantyPossible: Boolean get() = warrantyFlagsRaw == 0
    val hasRadioExtension: Boolean get() = radio != null
}

class SmartsonicPayloadException(message: String) : IllegalArgumentException(message)

object SmartsonicPayload {
    const val MIN_KNOWN_PAYLOAD_LENGTH = 20

    val PRODUCT_TYPES = mapOf(0 to "Genius H", 1 to "Genius Hx", 2 to "Genius Plus", 3 to "Genius Plus X")
    val RADIO_PRODUCT_TYPES = mapOf(
        0 to "kein FM", 1 to "FM.Basis", 2 to "FM.Pro", 3 to "FM.MCP", 4 to "FM.Basis X", 5 to "FM.Pro X",
    )

    val WARRANTY_FLAG_NAMES = listOf(
        "max_dirty", "out_of_temperature", "detector_too_old", "storage_time_exceeded",
        "activation_time_exceeded", "too_many_events", "too_many_alarms", "too_many_faults",
        "too_many_self_tests", "too_many_radio_faults", "too_many_radio_out_of_order_events",
        "radio_installation_too_old", "too_much_radio_activity", "too_much_radio_interference",
        "too_many_radio_tx_events", "too_many_radio_rx_events",
    )

    /** German labels for the warranty flags, used by the app UI and reports. */
    val WARRANTY_FLAG_LABELS = mapOf(
        "max_dirty" to "maximale Verschmutzung überschritten",
        "out_of_temperature" to "außerhalb Temperaturbereich",
        "detector_too_old" to "Melder zu alt",
        "storage_time_exceeded" to "Lagerzeit überschritten",
        "activation_time_exceeded" to "Aktivierungszeit überschritten",
        "too_many_events" to "zu viele Ereignisse",
        "too_many_alarms" to "zu viele Alarme",
        "too_many_faults" to "zu viele Fehler",
        "too_many_self_tests" to "zu viele Selbsttests",
        "too_many_radio_faults" to "zu viele Funkfehler",
        "too_many_radio_out_of_order_events" to "zu viele Funk-Außerbetrieb-Ereignisse",
        "radio_installation_too_old" to "Funkinstallation zu alt",
        "too_much_radio_activity" to "zu viel Funkaktivität",
        "too_much_radio_interference" to "zu viel Funkstörung",
        "too_many_radio_tx_events" to "zu viele Funk-Sendeereignisse",
        "too_many_radio_rx_events" to "zu viele Funk-Empfangsereignisse",
    )

    // bit order per protocol/smartsonic/PROTOCOL.md
    val RADIO_STATE_FLAGS = listOf(
        0x01 to "remote_alarm", 0x02 to "radio_link_error", 0x04 to "remote_error",
        0x08 to "remote_battery_low", 0x10 to "fm_battery_low_fault", 0x20 to "self_test",
        0x40 to "transmission_range_test", 0x80 to "fm_fault",
    )
    val RADIO_SWITCH_FLAGS = listOf(
        0x01 to "suppress_warnings", 0x02 to "suppress_alarms", 0x04 to "send_collective_alarm",
        0x08 to "receive_collective_alarm", 0x10 to "radio_link_supervision",
        0x20 to "reduced_transmitting_power",
    )

    private fun u8(payload: ByteArray, offset: Int): Int = payload[offset].toInt() and 0xFF
    private fun u16le(payload: ByteArray, offset: Int): Int = u8(payload, offset) or (u8(payload, offset + 1) shl 8)
    private fun u32be(payload: ByteArray, offset: Int): Long =
        (u8(payload, offset).toLong() shl 24) or (u8(payload, offset + 1).toLong() shl 16) or
            (u8(payload, offset + 2).toLong() shl 8) or u8(payload, offset + 3).toLong()

    private fun hex(payload: ByteArray, from: Int, to: Int): String =
        (from until to).joinToString("") { "%02x".format(u8(payload, it)) }

    private fun relativeEventDate(reference: LocalDate, productionAge: Int, offset: Int): LocalDate? =
        if (offset == 0xFFFF || offset > productionAge) null
        else reference.minusDays((productionAge - offset).toLong())

    /**
     * Parses a 20- or 32-byte payload. Smartsonic carries relative day
     * counters, so [referenceDate] must be the date of the acoustic readout.
     * Throws [SmartsonicPayloadException] for values outside the specification.
     */
    fun parse(payload: ByteArray, referenceDate: LocalDate): SmartsonicFields {
        if (payload.size < MIN_KNOWN_PAYLOAD_LENGTH) {
            throw SmartsonicPayloadException("known payload parser needs at least 20 bytes, got ${payload.size}")
        }
        if (payload.size in 21..31) {
            throw SmartsonicPayloadException("truncated radio extension: got ${payload.size} bytes, need at least 32")
        }
        val typeByte = u8(payload, 5)
        val productCode = typeByte and 0x0F
        val radioProductCode = typeByte shr 4
        val lastAlarmOffset = u16le(payload, 9)
        val productionAge = u16le(payload, 11)
        val storageHours = u16le(payload, 13)
        val lastSelftestOffset = u16le(payload, 15)
        val warrantyRaw = u16le(payload, 17)
        val status = u8(payload, 19)
        val driftState = (status shr 3) and 0x0F
        if (driftState > 8) {
            throw SmartsonicPayloadException("invalid detector drift state $driftState; valid range is 0..8")
        }

        val radio = if (payload.size >= 32) {
            val lineByte = u8(payload, 29)
            val letterCode = lineByte shr 4
            val number = lineByte and 0x0F
            if (letterCode > 9 || number > 9) {
                throw SmartsonicPayloadException("invalid radio line byte 0x%02x; both nibbles must be decimal digits".format(lineByte))
            }
            val switch = u8(payload, 30)
            if (switch and 0xC0 != 0) {
                throw SmartsonicPayloadException("invalid radio switch mask 0x%02x; bits 6 and 7 are reserved".format(switch))
            }
            val state = u8(payload, 20)
            val interference = u8(payload, 31)
            SmartsonicRadio(
                stateRaw = state,
                stateFlags = RADIO_STATE_FLAGS.filter { state and it.first != 0 }.map { it.second },
                serial = u32be(payload, 21),
                serialHex = hex(payload, 21, 25),
                lineId = u32be(payload, 25),
                line = "${'A' + letterCode}$number",
                switchRaw = switch,
                switchFlags = RADIO_SWITCH_FLAGS.filter { switch and it.first != 0 }.map { it.second },
                interferenceRaw = interference,
                interferencePercent = if (interference > 0) interference / 10.0 else 0.0,
            )
        } else null

        return SmartsonicFields(
            unknownByte0 = u8(payload, 0),
            detectorSerial = u32be(payload, 1),
            detectorSerialHex = hex(payload, 1, 5),
            productTypeCode = productCode,
            productType = PRODUCT_TYPES[productCode] ?: "unbekannt",
            radioProductTypeCode = radioProductCode,
            radioProductType = RADIO_PRODUCT_TYPES[radioProductCode] ?: "unbekannt",
            deinstallationCount = u8(payload, 6),
            alarmCount = u8(payload, 7),
            alarmCountLast3Months = u8(payload, 8),
            lastAlarmOffsetDays = if (lastAlarmOffset == 0xFFFF) null else lastAlarmOffset,
            productionAgeDays = productionAge,
            hoursInStorageMode = storageHours,
            lastSelftestOffsetDays = if (lastSelftestOffset == 0xFFFF) null else lastSelftestOffset,
            readoutReferenceDate = referenceDate,
            productionDate = referenceDate.minusDays(productionAge.toLong()),
            lastAlarmDate = relativeEventDate(referenceDate, productionAge, lastAlarmOffset),
            lastSelftestDate = relativeEventDate(referenceDate, productionAge, lastSelftestOffset),
            warrantyFlagsRaw = warrantyRaw,
            warrantyFlags = WARRANTY_FLAG_NAMES.filterIndexed { bit, _ -> warrantyRaw and (1 shl bit) != 0 },
            statusRaw = status,
            batteryLowFault = status and 0x01 != 0,
            deviceFault = status and 0x02 != 0,
            radioNetworkFault = status and 0x04 != 0,
            driftState = driftState,
            dirtForecastNegative = status and 0x80 != 0,
            radio = radio,
            payloadHex = hex(payload, 0, payload.size),
        )
    }
}
