package com.cancerdetection.multimodal.research.data.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import java.io.OutputStream

/**
 * Generates an on-device, vector-quality PDF clinical research report
 * representing the current case's verified clinical findings, computational
 * prediction, tile attention weights, and investigational disclaimer.
 */
object PdfReportGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_LEFT = 40f
    private const val MARGIN_RIGHT = 555f
    private const val MARGIN_TOP = 40f
    private const val MARGIN_BOTTOM = 790f
    private const val CONTENT_WIDTH = MARGIN_RIGHT - MARGIN_LEFT // 515f

    /**
     * Sanitizes a case ID for a clean filename.
     */
    fun sanitizeFilename(caseIdentifier: String?): String = sanitizePdfFilename(caseIdentifier)

    /**
     * Exports the research case report to the given content URI.
     */
    fun exportPdfToUri(
        context: Context,
        researchCase: ResearchCase,
        destinationUri: Uri
    ): Result<Unit> {
        return try {
            val content = extractPdfReportContent(researchCase)
            val outputStream = context.contentResolver.openOutputStream(destinationUri)
                ?: return Result.failure(IllegalStateException("Unable to open output stream for selected destination"))
            outputStream.use { os ->
                writePdfToStream(content, os)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Renders the structured content into a vector PdfDocument and writes to outputStream.
     */
    fun writePdfToStream(content: PdfReportContent, outputStream: OutputStream) {
        val pdfDocument = PdfDocument()
        val renderer = CanvasReportRenderer(pdfDocument, content)
        renderer.render()
        pdfDocument.writeTo(outputStream)
        outputStream.flush()
        pdfDocument.close()
    }

    private class CanvasReportRenderer(
        private val pdfDoc: PdfDocument,
        private val content: PdfReportContent
    ) {
        private var pageNumber = 1
        private var currentPage = pdfDoc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        private var canvas = currentPage.canvas
        private var currentY = MARGIN_TOP

        // Paints & Typography
        private val titlePaint = Paint().apply {
            color = Color.parseColor("#0F1D38") // PathologyNavy
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13.5f
            isAntiAlias = true
        }

        private val subtitlePaint = Paint().apply {
            color = Color.parseColor("#475569") // PathologyTextSecondary
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 7.5f
            isAntiAlias = true
        }

        private val datePaint = Paint().apply {
            color = Color.parseColor("#64748B") // PathologyTextTertiary
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 7.5f
            isAntiAlias = true
        }

        private val sectionHeaderPaint = Paint().apply {
            color = Color.parseColor("#0F1D38")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 9.5f
            isAntiAlias = true
        }

        private val tableHeaderPaint = Paint().apply {
            color = Color.parseColor("#0F1D38")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 8f
            isAntiAlias = true
        }

        private val cellBoldPaint = Paint().apply {
            color = Color.parseColor("#0F1D38")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 8f
            isAntiAlias = true
        }

        private val cellRegularPaint = Paint().apply {
            color = Color.parseColor("#0F1D38")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 8f
            isAntiAlias = true
        }

        private val cellItalicPaint = Paint().apply {
            color = Color.parseColor("#64748B")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            textSize = 8f
            isAntiAlias = true
        }

        private val cellMonospacePaint = Paint().apply {
            color = Color.parseColor("#0F1D38")
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 8f
            isAntiAlias = true
        }

        private val footnotePaint = Paint().apply {
            color = Color.parseColor("#475569")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            textSize = 7f
            isAntiAlias = true
        }

        private val disclaimerTitlePaint = Paint().apply {
            color = Color.parseColor("#92400E")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 8f
            isAntiAlias = true
        }

        private val disclaimerBodyPaint = Paint().apply {
            color = Color.parseColor("#92400E")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 7.5f
            isAntiAlias = true
        }

        private val footerPaint = Paint().apply {
            color = Color.parseColor("#64748B")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 7f
            isAntiAlias = true
        }

        private val continuedHeaderPaint = Paint().apply {
            color = Color.parseColor("#0F1D38")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 8f
            isAntiAlias = true
        }

        // Structural Paints
        private val linePaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }

        private val headerFillPaint = Paint().apply {
            color = Color.parseColor("#F1F5F9") // PathologySurfaceVariant
            style = Paint.Style.FILL
        }

        private val altRowFillPaint = Paint().apply {
            color = Color.parseColor("#F8FAFC")
            style = Paint.Style.FILL
        }

        private val accentPaint = Paint().apply {
            color = Color.parseColor("#006970") // PathologyTeal
            style = Paint.Style.FILL
        }

        private val disclaimerFillPaint = Paint().apply {
            color = Color.parseColor("#FFFBEB")
            style = Paint.Style.FILL
        }

        private val disclaimerBorderPaint = Paint().apply {
            color = Color.parseColor("#FDE68A")
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
        }

        fun render() {
            drawDocumentHeader()
            drawSectionA()
            drawSectionB()
            drawSectionC()
            drawSectionD()
            if (content.reviewNotes.isNotEmpty()) {
                drawSectionReviewNotes()
            }
            drawSectionDisclaimer()
            finishDocument()
        }

        private fun ensureSpace(neededHeight: Float) {
            if (currentY + neededHeight > MARGIN_BOTTOM) {
                drawPageFooter()
                pdfDoc.finishPage(currentPage)
                pageNumber++
                currentPage = pdfDoc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = currentPage.canvas
                currentY = MARGIN_TOP
                drawContinuationHeader()
            }
        }

        private fun drawPageFooter() {
            canvas.drawLine(MARGIN_LEFT, 802f, MARGIN_RIGHT, 802f, linePaint)
            canvas.drawText(
                "INVESTIGATIONAL RESEARCH USE ONLY — NOT APPROVED FOR CLINICAL DIAGNOSTICS",
                MARGIN_LEFT,
                815f,
                footerPaint
            )
            val pageStr = "Page $pageNumber"
            val pageStrWidth = footerPaint.measureText(pageStr)
            canvas.drawText(pageStr, MARGIN_RIGHT - pageStrWidth, 815f, footerPaint)
        }

        private fun drawContinuationHeader() {
            canvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + 3f, accentPaint)
            currentY += 12f
            canvas.drawText(
                "Case: ${content.caseIdentifier} — Multimodal Research Report (Continued)",
                MARGIN_LEFT,
                currentY,
                continuedHeaderPaint
            )
            currentY += 4f
            canvas.drawLine(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY, linePaint)
            currentY += 12f
        }

        private fun drawDocumentHeader() {
            // Top accent bar
            canvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + 3.5f, accentPaint)
            currentY += 16f

            // Document Title
            canvas.drawText(content.title, MARGIN_LEFT, currentY, titlePaint)
            currentY += 12f

            // Subtitle and Date on same baseline row
            canvas.drawText(content.subtitle, MARGIN_LEFT, currentY, subtitlePaint)
            val dateText = "Report Date: ${content.generatedDate}"
            val dateWidth = datePaint.measureText(dateText)
            canvas.drawText(dateText, MARGIN_RIGHT - dateWidth, currentY, datePaint)
            currentY += 6f

            // Divider rule
            canvas.drawLine(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY, linePaint)
            currentY += 14f
        }

        private fun drawSectionHeader(title: String) {
            canvas.drawRect(MARGIN_LEFT, currentY, MARGIN_LEFT + 3.5f, currentY + 10f, accentPaint)
            canvas.drawText(title, MARGIN_LEFT + 7f, currentY + 9f, sectionHeaderPaint)
            currentY += 15f
        }

        private fun drawSectionA() {
            ensureSpace(80f)
            drawSectionHeader("A. CASE OVERVIEW")

            val col1W = 190f
            val col2W = CONTENT_WIDTH - col1W // 325f

            // Table header
            drawTableHeader(col1W, col2W, "PARAMETER", "CASE SPECIFICATION")

            content.caseOverviewItems.forEachIndexed { idx, (param, value) ->
                val lines = wrapText(value, cellRegularPaint, col2W - 12f)
                val rowH = maxOf(1, lines.size) * 11f + 6f
                ensureSpace(rowH)

                drawRowBackground(idx, rowH)

                // Draw Col 1 text
                canvas.drawText(param, MARGIN_LEFT + 6f, currentY + 11f, cellBoldPaint)

                // Draw Col 2 text
                lines.forEachIndexed { lineIdx, lineText ->
                    val paintToUse = if (param.contains("Identifier", ignoreCase = true)) cellMonospacePaint else cellRegularPaint
                    canvas.drawText(lineText, MARGIN_LEFT + col1W + 6f, currentY + 11f + lineIdx * 11f, paintToUse)
                }

                drawRowBorder(rowH)
                currentY += rowH
            }
            currentY += 10f
        }

        private fun drawSectionB() {
            ensureSpace(80f)
            drawSectionHeader("B. DOCUMENTED CLINICAL FINDINGS")

            val col1W = 200f
            val col2W = CONTENT_WIDTH - col1W // 315f

            drawTableHeader(col1W, col2W, "DOCUMENTED PARAMETER", "SOURCE FINDING")

            content.clinicalFindings.forEachIndexed { idx, (param, finding) ->
                val lines = wrapText(finding, cellRegularPaint, col2W - 12f)
                val rowH = maxOf(1, lines.size) * 11f + 6f
                ensureSpace(rowH)

                drawRowBackground(idx, rowH)

                canvas.drawText(param, MARGIN_LEFT + 6f, currentY + 11f, cellBoldPaint)

                val isUnstated = finding.contains("Not stated", ignoreCase = true) || finding.contains("None documented", ignoreCase = true)
                val paintToUse = if (isUnstated) cellItalicPaint else cellRegularPaint

                lines.forEachIndexed { lineIdx, lineText ->
                    canvas.drawText(lineText, MARGIN_LEFT + col1W + 6f, currentY + 11f + lineIdx * 11f, paintToUse)
                }

                drawRowBorder(rowH)
                currentY += rowH
            }
            currentY += 10f
        }

        private fun drawSectionC() {
            ensureSpace(80f)
            drawSectionHeader("C. COMPUTATIONAL MULTIMODAL PREDICTION")

            val col1W = 200f
            val col2W = CONTENT_WIDTH - col1W // 315f

            drawTableHeader(col1W, col2W, "METRIC", "RESEARCH FINDING")

            content.predictionRows.forEachIndexed { idx, (metric, finding) ->
                val lines = wrapText(finding, cellRegularPaint, col2W - 12f)
                val rowH = maxOf(1, lines.size) * 11f + 6f
                ensureSpace(rowH)

                drawRowBackground(idx, rowH)

                canvas.drawText(metric, MARGIN_LEFT + 6f, currentY + 11f, cellBoldPaint)

                if (metric.contains("Class", ignoreCase = true)) {
                    val isMsi = finding.contains("MSI", ignoreCase = true)
                    val pillPaint = Paint().apply {
                        color = Color.parseColor(if (isMsi) "#F3E5F5" else "#E0F2F1")
                        style = Paint.Style.FILL
                    }
                    val pillTextPaint = Paint().apply {
                        color = Color.parseColor(if (isMsi) "#7B1FA2" else "#00695C")
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textSize = 8f
                        isAntiAlias = true
                    }
                    val textW = pillTextPaint.measureText(finding)
                    val pillRect = RectF(
                        MARGIN_LEFT + col1W + 4f,
                        currentY + 2f,
                        MARGIN_LEFT + col1W + 12f + textW,
                        currentY + rowH - 2f
                    )
                    canvas.drawRoundRect(pillRect, 3f, 3f, pillPaint)
                    canvas.drawText(finding, MARGIN_LEFT + col1W + 8f, currentY + 11f, pillTextPaint)
                } else if (metric.contains("Probability", ignoreCase = true)) {
                    canvas.drawText(finding, MARGIN_LEFT + col1W + 6f, currentY + 11f, cellMonospacePaint)
                } else {
                    lines.forEachIndexed { lineIdx, lineText ->
                        canvas.drawText(lineText, MARGIN_LEFT + col1W + 6f, currentY + 11f + lineIdx * 11f, cellRegularPaint)
                    }
                }

                drawRowBorder(rowH)
                currentY += rowH
            }

            // Footnote for Section C
            val fnLines = wrapText(content.predictionFootnote, footnotePaint, CONTENT_WIDTH)
            ensureSpace(fnLines.size * 9f + 6f)
            currentY += 4f
            fnLines.forEach { fnLine ->
                canvas.drawText(fnLine, MARGIN_LEFT, currentY + 7f, footnotePaint)
                currentY += 9f
            }
            currentY += 8f
        }

        private fun drawSectionD() {
            ensureSpace(80f)
            drawSectionHeader("D. TOP CROSS-ATTENTION SCORES")

            val col1W = 250f
            val col2W = CONTENT_WIDTH - col1W // 265f

            drawTableHeader(col1W, col2W, "TILE RANK", "ATTENTION WEIGHT")

            content.attentionScores.forEachIndexed { idx, (tile, weight) ->
                val rowH = 16f
                ensureSpace(rowH)

                drawRowBackground(idx, rowH)

                canvas.drawText(tile, MARGIN_LEFT + 6f, currentY + 11f, cellRegularPaint)
                canvas.drawText(weight, MARGIN_LEFT + col1W + 6f, currentY + 11f, cellMonospacePaint)

                drawRowBorder(rowH)
                currentY += rowH
            }

            // Footnote for Section D
            val fnLines = wrapText(content.attentionFootnote, footnotePaint, CONTENT_WIDTH)
            ensureSpace(fnLines.size * 9f + 6f)
            currentY += 4f
            fnLines.forEach { fnLine ->
                canvas.drawText(fnLine, MARGIN_LEFT, currentY + 7f, footnotePaint)
                currentY += 9f
            }
            currentY += 10f
        }

        private fun drawSectionReviewNotes() {
            ensureSpace(70f)
            drawSectionHeader("E. CLINICIAN REVIEW NOTES (${content.reviewNotes.size})")

            val col1W = 180f
            val col2W = CONTENT_WIDTH - col1W // 335f

            drawTableHeader(col1W, col2W, "REVIEWER / CATEGORY", "OBSERVATION & NOTES")

            content.reviewNotes.forEachIndexed { idx, (header, noteContent) ->
                val headerLines = wrapText(header, cellBoldPaint, col1W - 12f)
                val contentLines = wrapText(noteContent, cellRegularPaint, col2W - 12f)
                val maxLines = maxOf(headerLines.size, contentLines.size)
                val rowH = maxOf(18f, maxLines * 11f + 6f)

                ensureSpace(rowH)
                drawRowBackground(idx, rowH)

                var yPosHeader = currentY + 10f
                headerLines.forEach { line ->
                    canvas.drawText(line, MARGIN_LEFT + 6f, yPosHeader, cellBoldPaint)
                    yPosHeader += 11f
                }

                var yPosContent = currentY + 10f
                contentLines.forEach { line ->
                    canvas.drawText(line, MARGIN_LEFT + col1W + 6f, yPosContent, cellRegularPaint)
                    yPosContent += 11f
                }

                drawRowBorder(rowH)
                currentY += rowH
            }
            currentY += 10f
        }

        private fun drawSectionDisclaimer() {
            val sectionLetter = if (content.reviewNotes.isNotEmpty()) "F" else "E"
            val lines = wrapText(content.limitationsDisclaimer, disclaimerBodyPaint, CONTENT_WIDTH - 20f)
            val boxH = 16f + lines.size * 10f + 6f
            ensureSpace(boxH + 10f)

            val rect = RectF(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + boxH)
            canvas.drawRoundRect(rect, 4f, 4f, disclaimerFillPaint)
            canvas.drawRoundRect(rect, 4f, 4f, disclaimerBorderPaint)

            canvas.drawText(
                "$sectionLetter. LIMITATIONS & RESEARCH VERIFICATION DISCLAIMER",
                MARGIN_LEFT + 10f,
                currentY + 12f,
                disclaimerTitlePaint
            )

            lines.forEachIndexed { lineIdx, lineText ->
                canvas.drawText(
                    lineText,
                    MARGIN_LEFT + 10f,
                    currentY + 23f + lineIdx * 10f,
                    disclaimerBodyPaint
                )
            }

            currentY += boxH + 10f
        }

        private fun drawTableHeader(col1W: Float, col2W: Float, col1Title: String, col2Title: String) {
            val headerH = 16f
            canvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + headerH, headerFillPaint)
            canvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + headerH, linePaint)
            canvas.drawLine(MARGIN_LEFT + col1W, currentY, MARGIN_LEFT + col1W, currentY + headerH, linePaint)

            canvas.drawText(col1Title, MARGIN_LEFT + 6f, currentY + 11f, tableHeaderPaint)
            canvas.drawText(col2Title, MARGIN_LEFT + col1W + 6f, currentY + 11f, tableHeaderPaint)
            currentY += headerH
        }

        private fun drawRowBackground(idx: Int, rowH: Float) {
            if (idx % 2 == 1) {
                canvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + rowH, altRowFillPaint)
            }
        }

        private fun drawRowBorder(rowH: Float) {
            canvas.drawRect(MARGIN_LEFT, currentY, MARGIN_RIGHT, currentY + rowH, linePaint)
        }

        private fun finishDocument() {
            drawPageFooter()
            pdfDoc.finishPage(currentPage)
        }
    }
}
