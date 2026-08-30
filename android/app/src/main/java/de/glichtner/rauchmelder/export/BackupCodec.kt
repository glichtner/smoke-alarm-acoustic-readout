package de.glichtner.rauchmelder.export

import com.google.gson.GsonBuilder
import de.glichtner.rauchmelder.data.Detector
import de.glichtner.rauchmelder.data.Inspection

/**
 * Complete JSON backup of the database (all detector and inspection
 * columns), for moving the data to another phone. The CSV export is a
 * human-readable report and intentionally not the migration format.
 */
object BackupCodec {
    const val FORMAT = "rauchmelder-backup"
    const val VERSION = 1

    data class Backup(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val exportedAt: Long = System.currentTimeMillis(),
        val detectors: List<Detector> = emptyList(),
        val inspections: List<Inspection> = emptyList(),
    )

    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    fun encode(detectors: List<Detector>, inspections: List<Inspection>): String =
        gson.toJson(Backup(detectors = detectors, inspections = inspections))

    /** Parses a backup; throws IllegalArgumentException for foreign or newer files. */
    fun decode(json: String): Backup {
        val backup = try {
            gson.fromJson(json, Backup::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Datei ist kein gültiges JSON: ${e.message}")
        } ?: throw IllegalArgumentException("Datei ist leer")
        require(backup.format == FORMAT) { "Datei ist keine Rauchmelder-Datensicherung" }
        require(backup.version <= VERSION) { "Datensicherung stammt aus einer neueren App-Version (${backup.version})" }
        return backup
    }
}
