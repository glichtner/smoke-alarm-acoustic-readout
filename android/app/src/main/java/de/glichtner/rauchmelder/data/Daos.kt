package de.glichtner.rauchmelder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectorDao {
    @Query(
        """
        SELECT d.*,
               (SELECT MAX(i.timestamp) FROM inspections i WHERE i.detectorId = d.id) AS lastInspection,
               (SELECT i.ok FROM inspections i WHERE i.detectorId = d.id
                ORDER BY i.timestamp DESC LIMIT 1) AS lastOk,
               (SELECT i.issues FROM inspections i WHERE i.detectorId = d.id
                ORDER BY i.timestamp DESC LIMIT 1) AS lastIssues
        FROM detectors d
        ORDER BY d.apartment, d.room
        """
    )
    fun observeAllWithLastInspection(): Flow<List<DetectorWithLastInspection>>

    @Query(
        """
        SELECT d.*,
               (SELECT MAX(i.timestamp) FROM inspections i WHERE i.detectorId = d.id) AS lastInspection,
               (SELECT i.ok FROM inspections i WHERE i.detectorId = d.id
                ORDER BY i.timestamp DESC LIMIT 1) AS lastOk,
               (SELECT i.issues FROM inspections i WHERE i.detectorId = d.id
                ORDER BY i.timestamp DESC LIMIT 1) AS lastIssues
        FROM detectors d
        ORDER BY d.apartment, d.room
        """
    )
    suspend fun getAllWithLastInspection(): List<DetectorWithLastInspection>

    @Query("SELECT * FROM detectors ORDER BY apartment, room")
    suspend fun getAll(): List<Detector>

    @Query("SELECT * FROM detectors WHERE id = :id")
    suspend fun findById(id: String): Detector?

    @Query("SELECT * FROM detectors WHERE id = :id")
    fun observeById(id: String): Flow<Detector?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(detector: Detector)

    @Update
    suspend fun update(detector: Detector)

    @Delete
    suspend fun delete(detector: Detector)
}

@Dao
interface InspectionDao {
    @Query("SELECT * FROM inspections WHERE detectorId = :detectorId ORDER BY timestamp DESC")
    fun observeForDetector(detectorId: String): Flow<List<Inspection>>

    @Query("SELECT * FROM inspections ORDER BY timestamp")
    suspend fun getAll(): List<Inspection>

    @Query(
        """
        SELECT i.* FROM inspections i
        JOIN (SELECT detectorId, MAX(timestamp) AS maxTs FROM inspections GROUP BY detectorId) latest
          ON i.detectorId = latest.detectorId AND i.timestamp = latest.maxTs
        """
    )
    suspend fun getLatestPerDetector(): List<Inspection>

    @Query("SELECT COUNT(*) FROM inspections WHERE detectorId = :detectorId AND timestamp = :timestamp")
    suspend fun count(detectorId: String, timestamp: Long): Int

    @Insert
    suspend fun insert(inspection: Inspection): Long
}
