package de.glichtner.rauchmelder

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.glichtner.rauchmelder.reminder.DueReminderWorker
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.glichtner.rauchmelder.export.CsvExporter
import de.glichtner.rauchmelder.export.PdfReportExporter
import de.glichtner.rauchmelder.ui.DetectorDetailScreen
import de.glichtner.rauchmelder.ui.MainViewModel
import de.glichtner.rauchmelder.ui.OverviewScreen
import de.glichtner.rauchmelder.ui.ScanScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DueReminderWorker.schedule(this)
        setContent {
            MaterialTheme {
                Surface {
                    RauchmelderApp()
                }
            }
        }
    }
}

@Composable
fun RauchmelderApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun share(fileName: String, mimeType: String, writer: (File) -> Unit) {
        scope.launch {
            try {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, fileName)
                withContext(Dispatchers.IO) { writer(file) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Export teilen"))
            } catch (e: Exception) {
                Toast.makeText(context, "Export fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val dateSuffix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    var importMessage by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw IllegalArgumentException("Datei konnte nicht gelesen werden")
                val result = viewModel.importBackup(json)
                importMessage = "Import abgeschlossen: ${result.detectorsAdded} Melder und " +
                    "${result.inspectionsAdded} Prüfungen übernommen, ${result.inspectionsSkipped} bereits vorhanden."
            } catch (e: Exception) {
                importMessage = "Import fehlgeschlagen: ${e.message}"
            }
        }
    }
    importMessage?.let { message ->
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        importMessage = null
    }

    NavHost(navController = navController, startDestination = "overview") {
        composable("overview") {
            OverviewScreen(
                viewModel = viewModel,
                onScanClick = {
                    viewModel.resetScan()
                    navController.navigate("scan")
                },
                onDetectorClick = { detectorId -> navController.navigate("detector/$detectorId") },
                onExportCsv = {
                    scope.launch {
                        val detectors = viewModel.detectors.value.map { it.detector }
                        val inspections = viewModel.allInspections()
                        share("pruefprotokoll-$dateSuffix.csv", "text/csv") { file ->
                            file.writeText(
                                CsvExporter.export(detectors, inspections),
                                Charsets.UTF_8,
                            )
                        }
                    }
                },
                onExportPdf = {
                    scope.launch {
                        val detectors = viewModel.detectors.value.map { it.detector }
                        val latest = viewModel.latestInspections()
                        share("pruefbericht-$dateSuffix.pdf", "application/pdf") { file ->
                            file.outputStream().use { out ->
                                PdfReportExporter.writeReport(out, detectors, latest)
                            }
                        }
                    }
                },
                onExportBackup = {
                    scope.launch {
                        val json = viewModel.exportBackup()
                        share("rauchmelder-sicherung-$dateSuffix.json", "application/json") { file ->
                            file.writeText(json, Charsets.UTF_8)
                        }
                    }
                },
                onImportBackup = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
            )
        }
        composable("scan") {
            ScanScreen(
                viewModel = viewModel,
                onDone = { navController.popBackStack() },
            )
        }
        composable("detector/{detectorId}") { backStackEntry ->
            val detectorId = backStackEntry.arguments?.getString("detectorId") ?: return@composable
            DetectorDetailScreen(
                viewModel = viewModel,
                detectorId = detectorId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
