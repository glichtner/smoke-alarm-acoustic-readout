package de.glichtner.rauchmelder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sensors
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.glichtner.rauchmelder.data.DetectorWithLastInspection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    viewModel: MainViewModel,
    onScanClick: () -> Unit,
    onDetectorClick: (String) -> Unit,
    onExportCsv: () -> Unit,
    onExportPdf: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    val detectors by viewModel.detectors.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    // Android 13+: notifications (annual reminders) need a runtime permission
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rauchmelder") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Export")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("PDF-Prüfbericht exportieren") },
                            onClick = {
                                menuOpen = false
                                onExportPdf()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("CSV-Prüfprotokoll exportieren") },
                            onClick = {
                                menuOpen = false
                                onExportCsv()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Datensicherung exportieren (JSON)") },
                            onClick = {
                                menuOpen = false
                                onExportBackup()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Datensicherung importieren") },
                            onClick = {
                                menuOpen = false
                                onImportBackup()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScanClick,
                icon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                text = { Text("Melder auslesen") },
            )
        },
    ) { padding ->
        if (detectors.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Noch keine Melder registriert.\n\n" +
                        "Mit „Melder auslesen“ den ersten Rauchmelder akustisch erfassen (Ei AudioLINK+ oder Hekatron Smartsonic).",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val grouped = detectors.groupBy { it.detector.apartment }.toSortedMap()
            for ((apartment, apartmentDetectors) in grouped) {
                item(key = "header-$apartment") {
                    Text(
                        apartment,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(apartmentDetectors.size, key = { apartmentDetectors[it].detector.id }) { index ->
                    DetectorCard(apartmentDetectors[index], onDetectorClick)
                }
            }
        }
    }
}

@Composable
private fun DetectorCard(
    entry: DetectorWithLastInspection,
    onDetectorClick: (String) -> Unit,
) {
    val due = dueInfo(entry.lastInspection)
    val (statusColor, statusText) = when (due.status) {
        DueStatus.NEVER_CHECKED -> Color(0xFF9E9E9E) to "Noch keine Prüfung"
        DueStatus.OVERDUE -> Color(0xFFD32F2F) to
            "Prüfung überfällig seit ${due.nextDue!!.formatGerman()}"
        DueStatus.DUE_SOON -> Color(0xFFF9A825) to
            "Prüfung fällig bis ${due.nextDue!!.formatGerman()} (${due.daysUntilDue} Tage)"
        DueStatus.OK -> Color(0xFF388E3C) to
            "Nächste Prüfung: ${due.nextDue!!.formatGerman()}"
    }
    val warning = entry.lastOk == false

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDetectorClick(entry.detector.id) },
        colors = if (warning) CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)) else CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(statusColor, CircleShape),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.detector.room,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${entry.detector.manufacturer} ${entry.detector.model} · ${entry.detector.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                )
                if (warning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning, contentDescription = null,
                            tint = Color(0xFFD32F2F), modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            "Problem: ${entry.lastIssues?.ifBlank { null } ?: "letzte Prüfung auffällig"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
