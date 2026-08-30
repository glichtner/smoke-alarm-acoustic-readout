package de.glichtner.rauchmelder.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A registered smoke detector. [id] is the acoustic identifier: the 32-bit
 * AudioLINK+ ID as eight hex digits (Ei Electronics) or the decimal
 * Smartsonic serial number (Hekatron).
 */
@Entity(tableName = "detectors")
data class Detector(
    @PrimaryKey val id: String,
    val protocol: String,
    val manufacturer: String,
    val model: String,
    val apartment: String,
    val room: String,
    val manufactureDate: String,
    val replacementMonth: String,
    val createdAt: Long,
)

/**
 * One acoustic readout of a detector. Only the columns of the detector's
 * protocol are populated; the rest stay null. [ok] is the overall verdict,
 * [issues] a semicolon-separated list of German issue texts (may be empty).
 */
@Entity(
    tableName = "inspections",
    foreignKeys = [
        ForeignKey(
            entity = Detector::class,
            parentColumns = ["id"],
            childColumns = ["detectorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("detectorId")],
)
data class Inspection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val detectorId: String,
    val timestamp: Long,
    val protocol: String,
    val ok: Boolean,
    val issues: String,
    // counters present in both protocols
    val alarmCount: Int?,
    val removalCount: Int?,
    // Ei650i / AudioLINK+
    val batteryVoltage: Double?,
    val batteryStatus: String?,
    val sensorOk: Boolean?,
    val contaminationLevel: Double?,
    val dustCalculating: Boolean?,
    val uptimeDays: Int?,
    val testCount: Int?,
    val testAgeDays: Int?,
    val alarmAgeDays: Int?,
    val lowBatteryCount: Int?,
    val lowBatteryAgeDays: Int?,
    val removalAgeDays: Int?,
    // Hekatron / Smartsonic
    val alarmsLast3Months: Int?,
    val lastAlarmDate: String?,
    val lastSelfTestDate: String?,
    val ageDays: Int?,
    val storageHours: Int?,
    val warrantyFlags: Int?,
    val statusRaw: Int?,
    val driftState: Int?,
    val batteryLow: Boolean?,
    val deviceFault: Boolean?,
    val radioFault: Boolean?,
    val radioModule: String?,
    val radioSerial: String?,
    val radioLine: String?,
    val radioInterferencePercent: Double?,
    val payloadHex: String,
)

/** Detector plus the timestamp and verdict of its latest inspection. */
data class DetectorWithLastInspection(
    @Embedded val detector: Detector,
    val lastInspection: Long?,
    val lastOk: Boolean?,
    val lastIssues: String?,
)
