package de.glichtner.rauchmelder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.glichtner.rauchmelder.data.Detector
import de.glichtner.rauchmelder.data.Inspection
import de.glichtner.rauchmelder.export.CsvExporter
import de.glichtner.rauchmelder.model.Protocol
import de.glichtner.rauchmelder.model.formatIsoDate
import de.glichtner.rauchmelder.model.formatIsoMonth
import de.glichtner.rauchmelder.model.inspectionRows

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectorDetailScreen(
    viewModel: MainViewModel,
    detectorId: String,
    onBack: () -> Unit,
) {
    val detector by viewModel.observeDetector(detectorId).collectAsStateWithLifecycle(null)
    val inspections by viewModel.observeInspections(detectorId).collectAsStateWithLifecycle(emptyList())
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val current = detector ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${current.apartment} · ${current.room}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { showEdit = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Standort bearbeiten")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Melder löschen")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                DetectorInfoCard(current, inspections.firstOrNull()?.timestamp)
            }
            item {
                Text(
                    "Prüfprotokoll (${inspections.size} Prüfungen)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (inspections.isEmpty()) {
                item { Text("Noch keine Prüfungen erfasst.") }
            }
            items(inspections.size, key = { inspections[it].id }) { index ->
                InspectionCard(inspections[index])
            }
        }
    }

    if (showEdit) {
        EditLocationDialog(
            detector = current,
            onSave = { apartment, room ->
                viewModel.updateDetectorLocation(current, apartment, room)
                showEdit = false
            },
            onDismiss = { showEdit = false },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Melder löschen?") },
            text = {
                Text(
                    "Der Melder ${current.id} (${current.apartment}, ${current.room}) " +
                        "und sein gesamtes Prüfprotokoll werden gelöscht."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDetector(current)
                    showDelete = false
                    onBack()
                }) {
                    Text("Löschen", color = Color(0xFFD32F2F))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun DetectorInfoCard(detector: Detector, lastInspection: Long?) {
    val due = dueInfo(lastInspection)
    val protocol = Protocol.entries.firstOrNull { it.name == detector.protocol }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoRow("Hersteller", detector.manufacturer)
            InfoRow("Modell", detector.model)
            InfoRow(if (protocol == Protocol.SMARTSONIC) "Seriennummer" else "Melder-ID", detector.id)
            InfoRow("Protokoll", protocol?.label ?: detector.protocol)
            InfoRow("Wohnung", detector.apartment)
            InfoRow("Zimmer", detector.room)
            InfoRow("Produziert", formatIsoDate(detector.manufactureDate))
            InfoRow("Ersetzen bis", formatIsoMonth(detector.replacementMonth))
            InfoRow(
                "Nächste Prüfung",
                when (due.status) {
                    DueStatus.NEVER_CHECKED -> "noch keine Prüfung erfasst"
                    else -> due.nextDue!!.formatGerman() +
                        if (due.status == DueStatus.OVERDUE) " (überfällig)" else ""
                },
            )
        }
    }
}

@Composable
private fun InspectionCard(inspection: Inspection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (inspection.ok) androidx.compose.material3.CardDefaults.cardColors()
        else androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFFFF3F3)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    CsvExporter.formatTimestamp(inspection.timestamp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (inspection.ok) "in Ordnung" else "PROBLEM",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (inspection.ok) Color(0xFF388E3C) else Color(0xFFD32F2F),
                )
            }
            if (!inspection.ok) {
                Spacer(Modifier.height(4.dp))
                ProblemBanner(inspection.issues)
            }
            Spacer(Modifier.height(2.dp))
            for ((label, value) in inspectionRows(inspection)) InfoRow(label, value)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // the label keeps its intrinsic width so long words never wrap mid-word
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun EditLocationDialog(
    detector: Detector,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var apartment by remember { mutableStateOf(detector.apartment) }
    var room by remember { mutableStateOf(detector.room) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Standort bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = apartment,
                    onValueChange = { apartment = it },
                    label = { Text("Wohnung") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Zimmer") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(apartment, room) },
                enabled = apartment.isNotBlank() && room.isNotBlank(),
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}
