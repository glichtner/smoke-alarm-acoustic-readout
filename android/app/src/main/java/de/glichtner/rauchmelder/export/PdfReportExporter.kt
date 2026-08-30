package de.glichtner.rauchmelder.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import de.glichtner.rauchmelder.data.Detector
import de.glichtner.rauchmelder.data.Inspection
import de.glichtner.rauchmelder.model.Protocol
import de.glichtner.rauchmelder.model.formatIsoDate
import de.glichtner.rauchmelder.model.formatIsoMonth
import de.glichtner.rauchmelder.model.inspectionRows
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * PDF inspection report over all registered detectors, grouped by apartment,
 * each with its latest inspection. Report text is German on purpose.
 */
object PdfReportExporter {

    private const val PAGE_WIDTH = 595 // A4 in PostScript points
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val LINE = 14f

    fun writeReport(
        out: OutputStream,
        detectors: List<Detector>,
        latestInspections: List<Inspection>,
    ) {
        val inspectionByDetector = latestInspections.associateBy { it.detectorId }
        val document = PdfDocument()

        val titlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 18f
            color = Color.BLACK
            isAntiAlias = true
        }
        val headingPaint = Paint(titlePaint).apply { textSize = 13f }
        val textPaint = Paint().apply {
            typeface = Typeface.SANS_SERIF
            textSize = 10f
            color = Color.BLACK
            isAntiAlias = true
        }
        val boldPaint = Paint(textPaint).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val grayPaint = Paint(textPaint).apply { color = Color.rgb(90, 90, 90) }
        val linePaint = Paint().apply {
            color = Color.rgb(180, 180, 180)
            strokeWidth = 0.7f
        }
        val germanDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun newPage() {
            page?.let { document.finishPage(it) }
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(info)
            canvas = page!!.canvas
            y = MARGIN
            if (pageNumber == 1) {
                canvas!!.drawText("Prüfbericht Rauchwarnmelder", MARGIN, y + 14f, titlePaint)
                y += 34f
                val today = LocalDate.now().format(germanDate)
                canvas!!.drawText(
                    "Erstellt am $today · akustische Auslesung (AudioLINK+/Smartsonic) · ${detectors.size} Melder",
                    MARGIN, y, grayPaint,
                )
                y += 24f
            }
            canvas!!.drawText("Seite $pageNumber", PAGE_WIDTH - MARGIN - 45f, PAGE_HEIGHT - 20f, grayPaint)
        }

        fun ensureSpace(needed: Float) {
            if (page == null || y + needed > PAGE_HEIGHT - MARGIN - 20f) newPage()
        }

        /** Joins label/value rows into lines that fit the page width. */
        fun wrapRows(rows: List<Pair<String, String>>, paint: Paint, maxWidth: Float): List<String> {
            val lines = ArrayList<String>()
            var current = ""
            for ((label, value) in rows) {
                val piece = "$label: $value"
                val candidate = if (current.isEmpty()) piece else "$current · $piece"
                if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                    lines.add(current)
                    current = piece
                } else {
                    current = candidate
                }
            }
            if (current.isNotEmpty()) lines.add(current)
            return lines
        }

        newPage()

        val grouped = detectors.groupBy { it.apartment }.toSortedMap()
        for ((apartment, apartmentDetectors) in grouped) {
            ensureSpace(30f + LINE * 8)
            canvas!!.drawText("Wohnung: $apartment", MARGIN, y + 12f, headingPaint)
            y += 20f
            canvas!!.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 8f

            for (detector in apartmentDetectors.sortedBy { it.room }) {
                val inspection = inspectionByDetector[detector.id]
                val protocol = Protocol.entries.firstOrNull { it.name == detector.protocol }
                val detailLines = inspection?.let {
                    wrapRows(inspectionRows(it), textPaint, PAGE_WIDTH - 2 * MARGIN - 12f)
                } ?: emptyList()
                val blockHeight = LINE * (3 + detailLines.size) + 14f
                ensureSpace(blockHeight)

                canvas!!.drawText(detector.room, MARGIN, y + 10f, boldPaint)
                canvas!!.drawText(
                    "${detector.manufacturer} ${detector.model} · ${protocol?.label ?: detector.protocol} · " +
                        "${if (protocol == Protocol.SMARTSONIC) "SN" else "ID"} ${detector.id} · hergestellt " +
                        "${formatIsoDate(detector.manufactureDate)} · ersetzen bis " +
                        formatIsoMonth(detector.replacementMonth),
                    MARGIN + 100f, y + 10f, grayPaint,
                )
                y += LINE + 4f

                if (inspection == null) {
                    canvas!!.drawText("Noch keine Prüfung erfasst.", MARGIN + 12f, y + 8f, textPaint)
                    y += LINE + 6f
                } else {
                    val checkDate = CsvExporter.formatTimestamp(inspection.timestamp)
                    val nextDue = Instant.ofEpochMilli(inspection.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .plusYears(1)
                        .format(germanDate)
                    canvas!!.drawText(
                        "Letzte Prüfung: $checkDate · nächste Prüfung fällig: $nextDue",
                        MARGIN + 12f, y + 8f, textPaint,
                    )
                    y += LINE
                    for (line in detailLines) {
                        canvas!!.drawText(line, MARGIN + 12f, y + 8f, textPaint)
                        y += LINE
                    }
                    canvas!!.drawText(
                        "Ergebnis: ${if (inspection.ok) "in Ordnung" else "auffällig – prüfen"}",
                        MARGIN + 12f, y + 8f, boldPaint,
                    )
                    y += LINE + 6f
                }
            }
            y += 10f
        }

        if (detectors.isEmpty()) {
            canvas!!.drawText("Es sind noch keine Melder registriert.", MARGIN, y + 10f, textPaint)
        }

        page?.let { document.finishPage(it) }
        document.writeTo(out)
        document.close()
    }
}
