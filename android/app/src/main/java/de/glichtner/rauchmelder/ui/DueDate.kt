package de.glichtner.rauchmelder.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class DueStatus { OK, DUE_SOON, OVERDUE, NEVER_CHECKED }

data class DueInfo(
    val nextDue: LocalDate?,
    val status: DueStatus,
    val daysUntilDue: Long?,
)

private val germanDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun LocalDate.formatGerman(): String = format(germanDate)

fun timestampToLocalDate(timestamp: Long): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

/** The next inspection is due one year after the last one. */
fun dueInfo(lastInspection: Long?, today: LocalDate = LocalDate.now()): DueInfo {
    if (lastInspection == null) return DueInfo(null, DueStatus.NEVER_CHECKED, null)
    val nextDue = timestampToLocalDate(lastInspection).plusYears(1)
    val days = ChronoUnit.DAYS.between(today, nextDue)
    val status = when {
        days < 0 -> DueStatus.OVERDUE
        days <= 30 -> DueStatus.DUE_SOON
        else -> DueStatus.OK
    }
    return DueInfo(nextDue, status, days)
}
