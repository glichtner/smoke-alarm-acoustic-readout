package de.glichtner.rauchmelder.decoder

import kotlin.math.min

/** Event block: counter (big endian, 4-hour units) plus count. */
data class EventInfo(
    val count: Int,
    val countRaw: Int,
    val ageHours: Int?,
) {
    val ageDays: Int? get() = ageHours?.let { it / 24 }
}

data class DecodedFields(
    val model: String,
    val modelCode: Int,
    val uptimeHours: Int,
    val uptimeDays: Int,
    val contaminationRaw: Int,
    val contaminationLevel: Double?,
    val dustCalculating: Boolean,
    val sensorOk: Boolean,
    val statusRaw: Int,
    val batteryCode: Int,
    val batteryVoltage: Double,
    val batteryStatus: BatteryStatus,
    val testButton: EventInfo,
    val smokeAlarm: EventInfo,
    val lowBattery: EventInfo,
    val removal: EventInfo,
    val alarmId: String,
    val manufactureDate: String,
    val replacementMonth: String,
    val payloadHex: String,
)

enum class BatteryStatus { GREEN, AMBER, RED }

private const val TIME_COUNTER_EPOCH = 24090 // 0x5e1a

private val BATTERY_VOLTAGES = doubleArrayOf(
    2.35, 2.38, 2.42, 2.46, 2.50, 2.54, 2.58, 2.63,
    2.67, 2.72, 2.77, 2.82, 2.87, 2.92, 2.98, 3.04,
)

private fun event(payload: ByteArray, offset: Int, currentCounter: Int, removalBias: Boolean = false): EventInfo {
    val counter = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
    val rawCount = payload[offset + 2].toInt() and 0xFF
    val count = if (removalBias) maxOf(0, rawCount - 1) else rawCount
    val ageHours = if (count > 0) (counter - currentCounter) * 4 else null
    return EventInfo(count = count, countRaw = rawCount, ageHours = ageHours)
}

fun decodeFields(payload: ByteArray): DecodedFields {
    require(payload.size == AudioLinkDecoder.PAYLOAD_LENGTH) { "payload must contain 30 bytes" }
    val uptimeCounter = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    val uptimeHours = (TIME_COUNTER_EPOCH - uptimeCounter) * 4
    val typeCode = payload[2].toInt() and 0xFF
    val contaminationRaw = payload[3].toInt() and 0x3F
    val status = payload[4].toInt() and 0xFF
    val batteryCode = status and 0x0F
    val dustCalculating = status and 0x20 != 0
    val contaminationValid = contaminationRaw <= 32 && !dustCalculating

    val b28 = payload[27].toInt() and 0xFF
    val b29 = payload[28].toInt() and 0xFF
    val day = b28 shr 3
    val month = ((b28 and 0x07) shl 1) or (b29 shr 7)
    val year = 1980 + (b29 and 0x7F)

    return DecodedFields(
        model = when (typeCode) {
            1 -> "Ei650"
            2 -> "Ei650i"
            else -> "unbekannt ($typeCode)"
        },
        modelCode = typeCode,
        uptimeHours = uptimeHours,
        uptimeDays = uptimeHours / 24,
        contaminationRaw = contaminationRaw,
        contaminationLevel = if (contaminationValid) {
            // banker's rounding to one decimal place
            Math.rint(min(contaminationRaw / 3.2, 10.0) * 10.0) / 10.0
        } else null,
        dustCalculating = dustCalculating,
        sensorOk = status and 0x80 == 0,
        statusRaw = status,
        batteryCode = batteryCode,
        batteryVoltage = BATTERY_VOLTAGES[batteryCode],
        batteryStatus = when {
            batteryCode > 8 -> BatteryStatus.GREEN
            batteryCode >= 4 -> BatteryStatus.AMBER
            else -> BatteryStatus.RED
        },
        testButton = event(payload, 5, uptimeCounter),
        smokeAlarm = event(payload, 8, uptimeCounter),
        lowBattery = event(payload, 17, uptimeCounter),
        removal = event(payload, 20, uptimeCounter, removalBias = true),
        alarmId = payload.sliceArray(23..26).joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        manufactureDate = "%04d-%02d-%02d".format(year, month, day),
        replacementMonth = "%04d-%02d".format(year + 11, month),
        payloadHex = payload.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) },
    )
}
