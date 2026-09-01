package de.glichtner.rauchmelder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.glichtner.rauchmelder.audio.DetectorScanListener
import de.glichtner.rauchmelder.audio.ScanPhase
import de.glichtner.rauchmelder.data.AppDatabase
import de.glichtner.rauchmelder.data.Detector
import de.glichtner.rauchmelder.data.DetectorWithLastInspection
import de.glichtner.rauchmelder.data.Inspection
import de.glichtner.rauchmelder.export.BackupCodec
import androidx.room.withTransaction
import de.glichtner.rauchmelder.model.DetectorReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of the scan screen. */
sealed interface ScanState {
    data object Idle : ScanState
    data class Listening(val phase: ScanPhase) : ScanState
    /** Detector was already registered; the inspection has been stored. */
    data class KnownDetector(val detector: Detector, val reading: DetectorReading) : ScanState
    /** Unknown ID; the location still needs to be entered. */
    data class NewDetector(val reading: DetectorReading) : ScanState
    data class Registered(val detector: Detector, val reading: DetectorReading) : ScanState
    data class Failed(val message: String) : ScanState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val detectorDao = db.detectorDao()
    private val inspectionDao = db.inspectionDao()
    private val listener = DetectorScanListener(
        viewModelScope,
        debugDir = application.getExternalFilesDir(null)?.let { java.io.File(it, "debug") },
    )

    val detectors: StateFlow<List<DetectorWithLastInspection>> =
        detectorDao.observeAllWithLastInspection()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    fun observeDetector(id: String): Flow<Detector?> = detectorDao.observeById(id)

    fun observeInspections(id: String): Flow<List<Inspection>> = inspectionDao.observeForDetector(id)

    fun startScan() {
        if (listener.isRunning) return
        _scanState.value = ScanState.Listening(ScanPhase.LISTENING)
        listener.start(object : DetectorScanListener.Callback {
            override fun onPhase(phase: ScanPhase) {
                if (_scanState.value is ScanState.Listening) {
                    _scanState.value = ScanState.Listening(phase)
                }
            }

            override fun onResult(reading: DetectorReading) {
                handleReading(reading)
            }

            override fun onTimeout() {
                _scanState.value = ScanState.Failed(
                    "Kein Melder-Signal erkannt. Ei-Melder: Prüftaste 3× innerhalb von 5 Sekunden " +
                        "drücken. Hekatron-Melder: Prüftaste 5 Sekunden gedrückt halten. " +
                        "Das Mikrofon dabei direkt an den Melder halten."
                )
            }

            override fun onError(message: String) {
                _scanState.value = ScanState.Failed(message)
            }
        })
    }

    fun stopScan() {
        listener.stop()
        _scanState.value = ScanState.Idle
    }

    fun resetScan() {
        listener.stop()
        _scanState.value = ScanState.Idle
    }

    private fun handleReading(reading: DetectorReading) {
        viewModelScope.launch {
            val existing = detectorDao.findById(reading.id)
            if (existing != null) {
                inspectionDao.insert(reading.inspection.copy(detectorId = existing.id))
                _scanState.value = ScanState.KnownDetector(existing, reading)
            } else {
                _scanState.value = ScanState.NewDetector(reading)
            }
        }
    }

    /** Registers a new detector with its location and stores the first inspection. */
    fun registerDetector(reading: DetectorReading, apartment: String, room: String) {
        viewModelScope.launch {
            val detector = Detector(
                id = reading.id,
                protocol = reading.protocol.name,
                manufacturer = reading.manufacturer,
                model = reading.model,
                apartment = apartment.trim(),
                room = room.trim(),
                manufactureDate = reading.manufactureDate,
                replacementMonth = reading.replacementMonth,
                createdAt = System.currentTimeMillis(),
            )
            detectorDao.insert(detector)
            inspectionDao.insert(reading.inspection.copy(detectorId = detector.id))
            _scanState.value = ScanState.Registered(detector, reading)
        }
    }

    fun updateDetectorLocation(detector: Detector, apartment: String, room: String) {
        viewModelScope.launch {
            detectorDao.update(detector.copy(apartment = apartment.trim(), room = room.trim()))
        }
    }

    fun deleteDetector(detector: Detector) {
        viewModelScope.launch { detectorDao.delete(detector) }
    }

    suspend fun allInspections(): List<Inspection> = inspectionDao.getAll()

    suspend fun exportBackup(): String =
        BackupCodec.encode(detectorDao.getAll(), inspectionDao.getAll())

    data class ImportResult(val detectorsAdded: Int, val inspectionsAdded: Int, val inspectionsSkipped: Int)

    /**
     * Merges a backup into the database: unknown detectors are added, known
     * ones keep their current location; inspections already present (same
     * detector and timestamp) are skipped.
     */
    suspend fun importBackup(json: String): ImportResult {
        val backup = BackupCodec.decode(json)
        var detectorsAdded = 0
        var inspectionsAdded = 0
        var inspectionsSkipped = 0
        db.withTransaction {
            for (detector in backup.detectors) {
                if (detectorDao.findById(detector.id) == null) {
                    detectorDao.insert(detector)
                    detectorsAdded++
                }
            }
            for (inspection in backup.inspections) {
                if (detectorDao.findById(inspection.detectorId) == null) {
                    inspectionsSkipped++
                    continue
                }
                if (inspectionDao.count(inspection.detectorId, inspection.timestamp) > 0) {
                    inspectionsSkipped++
                } else {
                    inspectionDao.insert(inspection.copy(id = 0))
                    inspectionsAdded++
                }
            }
        }
        return ImportResult(detectorsAdded, inspectionsAdded, inspectionsSkipped)
    }

    suspend fun latestInspections(): List<Inspection> = inspectionDao.getLatestPerDetector()

    override fun onCleared() {
        listener.stop()
    }
}
