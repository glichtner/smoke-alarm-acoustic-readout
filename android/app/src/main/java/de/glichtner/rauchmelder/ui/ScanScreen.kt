package de.glichtner.rauchmelder.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.ui.draw.alpha
import de.glichtner.rauchmelder.audio.ScanPhase
import de.glichtner.rauchmelder.model.DetectorReading
import de.glichtner.rauchmelder.model.formatIsoDate
import de.glichtner.rauchmelder.model.formatIsoMonth
import de.glichtner.rauchmelder.model.inspectionRows

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: MainViewModel,
    onDone: () -> Unit,
) {
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startScan()
    }

    fun startWithPermission() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startScan() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Melder auslesen") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.resetScan()
                        onDone()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val state = scanState) {
                is ScanState.Idle -> {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Handy-Mikrofon direkt an den Melder halten und den Melder zum Senden bringen:\n\n" +
                            "• Ei Electronics (AudioLINK): Prüftaste 3× innerhalb von 5 Sekunden drücken\n" +
                            "• Hekatron Genius (Smartsonic): Prüftaste 5 Sekunden gedrückt halten\n\n" +
                            "Das Protokoll wird automatisch erkannt.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { startWithPermission() }) {
                        Text("Empfang starten")
                    }
                }

                is ScanState.Listening -> {
                    when (state.phase) {
                        ScanPhase.LISTENING -> {
                            CircularProgressIndicator(modifier = Modifier.size(72.dp))
                            Spacer(Modifier.height(24.dp))
                            Text("Warte auf Melder-Signal …", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Melder auslösen und das Mikrofon direkt an den Melder halten.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        ScanPhase.RECEIVING -> {
                            val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
                                initialValue = 0.4f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    tween(500, easing = LinearEasing),
                                    RepeatMode.Reverse,
                                ),
                                label = "pulseAlpha",
                            )
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp).alpha(pulse.value),
                                tint = Color(0xFF388E3C),
                            )
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "Signal erkannt – Übertragung läuft …",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF388E3C),
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Handy ruhig halten, bis die Übertragung endet.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        ScanPhase.DECODING -> {
                            CircularProgressIndicator(modifier = Modifier.size(72.dp))
                            Spacer(Modifier.height(24.dp))
                            Text("Signal empfangen – dekodiere …", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    OutlinedButton(onClick = { viewModel.stopScan() }) {
                        Text("Abbrechen")
                    }
                }

                is ScanState.KnownDetector -> {
                    ResultHeader(
                        ok = state.reading.inspection.ok,
                        title = "Prüfung gespeichert",
                        subtitle = "${state.detector.apartment} · ${state.detector.room}",
                    )
                    Spacer(Modifier.height(16.dp))
                    if (!state.reading.inspection.ok) {
                        ProblemBanner(state.reading.inspection.issues)
                        Spacer(Modifier.height(8.dp))
                    }
                    ReadingCard(state.reading)
                    Spacer(Modifier.height(24.dp))
                    DoneButtons(viewModel, onDone)
                }

                is ScanState.NewDetector -> {
                    NewDetectorForm(viewModel, state.reading)
                }

                is ScanState.Registered -> {
                    ResultHeader(
                        ok = state.reading.inspection.ok,
                        title = "Melder registriert",
                        subtitle = "${state.detector.apartment} · ${state.detector.room}",
                    )
                    Spacer(Modifier.height(16.dp))
                    if (!state.reading.inspection.ok) {
                        ProblemBanner(state.reading.inspection.issues)
                        Spacer(Modifier.height(8.dp))
                    }
                    ReadingCard(state.reading)
                    Spacer(Modifier.height(24.dp))
                    DoneButtons(viewModel, onDone)
                }

                is ScanState.Failed -> {
                    Icon(
                        Icons.Default.Error, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = Color(0xFFD32F2F),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Kein Melder erkannt", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { startWithPermission() }) {
                        Text("Erneut versuchen")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        viewModel.resetScan()
                        onDone()
                    }) {
                        Text("Zurück zur Übersicht")
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneButtons(viewModel: MainViewModel, onDone: () -> Unit) {
    Button(onClick = {
        viewModel.resetScan()
        onDone()
    }) {
        Text("Fertig")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { viewModel.startScan() }) {
        Text("Weiteren Melder auslesen")
    }
}

@Composable
private fun ResultHeader(ok: Boolean, title: String, subtitle: String) {
    Icon(
        if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = if (ok) Color(0xFF388E3C) else Color(0xFFD32F2F),
    )
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

/** Prominent red box listing the problems of a conspicuous inspection. */
@Composable
fun ProblemBanner(issues: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Column {
                Text("Problem festgestellt", fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C))
                for (issue in issues.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                    Text("• $issue", color = Color(0xFFB71C1C), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun ReadingCard(reading: DetectorReading) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldRow("Hersteller", reading.manufacturer)
            FieldRow("Modell", reading.model)
            FieldRow(if (reading.protocol == de.glichtner.rauchmelder.model.Protocol.SMARTSONIC) "Seriennummer" else "Melder-ID", reading.id)
            FieldRow("Protokoll", reading.protocol.label)
            FieldRow("Produziert", formatIsoDate(reading.manufactureDate))
            FieldRow("Ersetzen bis", formatIsoMonth(reading.replacementMonth))
            for ((label, value) in inspectionRows(reading.inspection)) FieldRow(label, value)
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
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
private fun NewDetectorForm(viewModel: MainViewModel, reading: DetectorReading) {
    var apartment by rememberSaveable { mutableStateOf("") }
    var room by rememberSaveable { mutableStateOf("") }

    Text(
        "Neuer Melder erkannt",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "${reading.manufacturer} ${reading.model} (${reading.protocol.label}), " +
            "${if (reading.protocol == de.glichtner.rauchmelder.model.Protocol.SMARTSONIC) "Seriennummer" else "ID"} ${reading.id} " +
            "ist noch nicht registriert. Standort angeben:",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = apartment,
        onValueChange = { apartment = it },
        label = { Text("Wohnung") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = room,
        onValueChange = { room = it },
        label = { Text("Zimmer") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { viewModel.registerDetector(reading, apartment, room) },
        enabled = apartment.isNotBlank() && room.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Melder registrieren und Prüfung speichern")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { viewModel.resetScan() }, modifier = Modifier.fillMaxWidth()) {
        Text("Verwerfen")
    }
}
