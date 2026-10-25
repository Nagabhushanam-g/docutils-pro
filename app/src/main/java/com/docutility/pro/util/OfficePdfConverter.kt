package com.docutility.pro.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Basic, offline OOXML-to-PDF conversion. Supports DOCX paragraphs and XLSX cell
 * values; advanced formatting, embedded images, formulas and legacy DOC/XLS
 * are NOT preserved. Never claim layout-fidelity for this converter.
 */
object OfficePdfConverter {
    // Use Android's native streaming parser instead of DOM factory features that
    // differ between Android versions. OOXML does not need DTD processing.
    private fun xmlParser(bytes: ByteArray): XmlPullParser {
        val xml = bytes.toString(Charsets.UTF_8)
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
            "XML DTDs and entities are not supported"
        }
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(bytes.inputStream(), "UTF-8")
        }
    }

    // Collect text from OOXML leaf elements, grouping by a specified parent.
    private fun groupedText(bytes: ByteArray, group: String, textTag: String): List<String> {
        val parser = xmlParser(bytes)
        val result = mutableListOf<String>()
        var depth = 0
        var groupDepth = -1
        var textDepth = -1
        var current = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == group && groupDepth == -1) {
                        groupDepth = depth
                        current = StringBuilder()
                    } else if (groupDepth != -1 && parser.name == textTag) {
                        textDepth = depth
                    }
                }
                XmlPullParser.TEXT -> if (textDepth != -1) current.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (depth == textDepth) textDepth = -1
                    if (depth == groupDepth) {
                        result.add(current.toString())
                        groupDepth = -1
                    }
                    depth--
                }
                XmlPullParser.END_DOCUMENT -> return result
            }
        }
    }

    private fun zipEntries(context: Context, uri: Uri): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                var total = 0L
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name.startsWith("word/") || entry.name.startsWith("xl/"))) {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16384)
                        var entrySize = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count == -1) break
                            entrySize += count
                            total += count
                            require(entrySize <= 48L * 1024 * 1024 && total <= 96L * 1024 * 1024) {
                                "Workbook exceeds safe conversion limits. Please split the workbook."
                            }
                            output.write(buffer, 0, count)
                        }
                        val bytes = output.toByteArray()
                        result[entry.name] = bytes
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Unable to open document")
        return result
    }

    private fun wordLines(entries: Map<String, ByteArray>): List<String> =
        groupedText(entries["word/document.xml"] ?: error("Invalid DOCX file"), "p", "t")

    private fun excelRows(entries: Map<String, ByteArray>): List<Pair<String, List<List<String>>>> {
        val strings = entries["xl/sharedStrings.xml"]?.let { groupedText(it, "si", "t") }.orEmpty()
        val sheets = entries.keys.filter { Regex("xl/worksheets/sheet[0-9]+[.]xml").matches(it) }
            .sortedBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }
        require(sheets.isNotEmpty()) { "No Excel worksheets found" }
        return sheets.mapIndexed { index, name ->
            val rows = mutableListOf<List<String>>()
            var totalCells = 0L
            val parser = xmlParser(entries.getValue(name))
            var cells = sortedMapOf<Int, String>()
            var nextColumn = 0
            var column = 0
            var cellType = ""
            var value = StringBuilder()
            var reading = false
            var inCell = false
            while (true) {
                val event = parser.next()
                if (event == XmlPullParser.END_DOCUMENT) break
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> { cells = sortedMapOf(); nextColumn = 0 }
                        "c" -> {
                            inCell = true
                            cellType = parser.getAttributeValue(null, "t").orEmpty()
                            val ref = parser.getAttributeValue(null, "r").orEmpty()
                            val letters = ref.takeWhile { it.isLetter() }.uppercase()
                            column = if (letters.isEmpty()) nextColumn else
                                letters.fold(0) { n, c -> n * 26 + (c - 'A' + 1) } - 1
                            nextColumn = column + 1
                            value = StringBuilder()
                        }
                        "v", "t" -> if (inCell) reading = true
                    }
                    XmlPullParser.TEXT -> if (reading) value.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "v", "t" -> reading = false
                        "c" -> {
                            val raw = value.toString()
                            val text = if (cellType == "s") strings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                            if (text.isNotBlank() && column in 0..255) cells[column] = text
                            inCell = false
                        }
                        "row" -> {
                            if (cells.isNotEmpty()) {
                                val max = cells.lastKey().coerceAtMost(255)
                                rows.add((0..max).map { cells[it].orEmpty() })
                                totalCells += max + 1L
                            }
                        }
                    }
                }
                require(rows.size <= 20000) { "Worksheet exceeds the 20,000 non-empty row limit" }
                require(totalCells <= 1_000_000L) {
                    "Worksheet has too many cells to convert safely"
                }
            }
            "Worksheet ${index + 1}" to rows
        }
    }

    private fun renderExcel(pdf: PdfDocument, sheets: List<Pair<String, List<List<String>>>>) {
        // Landscape A3, with readable cell text, wrapped rows and repeating headers.
        val width = 1191
        val height = 842
        val margin = 35f
        val top = 68f
        val bottom = height - margin
        val font = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 16f }
        val headerFont = Paint(font).apply { isFakeBoldText = true }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 0.6f
        }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = top

        fun newPage(title: String) {
            page?.let { pdf.finishPage(it) }
            pageNumber++
            require(pageNumber <= 300) { "Conversion stopped: more than 300 pages. Select a smaller workbook." }
            page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
            page!!.canvas.drawColor(Color.WHITE)
            page!!.canvas.drawText(title.take(110), margin, 51f, headerFont)
            y = top
        }

        fun wrapCell(value: String, availableWidth: Float): List<String> {
            val source = value.replace('\r', ' ').replace('\n', ' ').trim()
            if (source.isEmpty()) return listOf("")
            val lines = mutableListOf<String>()
            var remaining = source
            while (remaining.isNotEmpty() && lines.size < 80) {
                val count = font.breakText(remaining, true, availableWidth, null).coerceAtLeast(1)
                if (count >= remaining.length) {
                    lines.add(remaining)
                    remaining = ""
                } else {
                    val breakAt = remaining.lastIndexOf(' ', count - 1)
                    val end = if (breakAt > 0) breakAt else count
                    lines.add(remaining.substring(0, end).trimEnd())
                    remaining = remaining.substring(end).trimStart()
                }
            }
            if (remaining.isNotEmpty() && lines.isNotEmpty()) {
                var last = lines.last()
                while (last.isNotEmpty() && font.measureText(last + "…") > availableWidth) last = last.dropLast(1)
                lines[lines.lastIndex] = last + "…"
            }
            return lines
        }

        sheets.forEach { (title, rows) ->
            if (rows.isEmpty()) {
                newPage(title)
                return@forEach
            }
            val cols = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
            // Limit the number of columns per tile to keep text legible.
            val columnsPerPage = 4
            val tiles = (cols + columnsPerPage - 1) / columnsPerPage
            for (tile in 0 until tiles) {
                val first = tile * columnsPerPage
                val count = minOf(columnsPerPage, cols - first)
                val cellWidth = (width - 2 * margin) / count
                val label = if (tiles == 1) title else "$title (columns ${first + 1}-${first + count})"
                // Some worksheets contain leading rows populated only in other
                // column tiles. Avoid rendering those as blank rows at the top
                // of this tile; keep interior spacing unchanged.
                val tileRows = rows.dropWhile { row ->
                    (0 until count).all { i ->
                        row.getOrNull(first + i).isNullOrBlank()
                    }
                }
                if (tileRows.isEmpty()) continue
                val header = tileRows.first()
                // Split tall cells across pages rather than drawing beyond the page.
                // Keep each column's wrapped text and continue it on the next page.
                fun drawRow(row: List<String>, headerRow: Boolean) {
                    val wrapped = (0 until count).map { i ->
                        wrapCell(row.getOrNull(first + i).orEmpty(), cellWidth - 12f)
                    }
                    var offset = 0
                    val maxLines = wrapped.maxOf { it.size }
                    while (offset < maxLines) {
                        val availableLines = ((bottom - y - 12f) / 22f).toInt()
                        if (availableLines < 1 || bottom - y < 32f) {
                            newPage(label)
                        }
                        val capacity = ((bottom - y - 12f) / 22f).toInt().coerceAtLeast(1)
                        val chunk = minOf(maxLines - offset, capacity)
                        val rowHeight = maxOf(34f, chunk * 22f + 12f)
                        for (i in 0 until count) {
                            val x = margin + i * cellWidth
                            page!!.canvas.drawRect(x, y, x + cellWidth, y + rowHeight, border)
                            val pen = if (headerRow) headerFont else font
                            wrapped[i].drop(offset).take(chunk).forEachIndexed { lineIndex, line ->
                                page!!.canvas.drawText(line, x + 6f, y + 21f + lineIndex * 22f, pen)
                            }
                        }
                        y += rowHeight
                        offset += chunk
                        if (offset < maxLines) newPage(label)
                    }
                }
                newPage(label)
                tileRows.forEachIndexed { rowIndex, row ->
                    // Repeat the first worksheet row as a header on ordinary page breaks.
                    if (y >= bottom - 32f) {
                        newPage(label)
                        if (rowIndex > 0) drawRow(header, true)
                    }
                    drawRow(row, rowIndex == 0)
                }
            }
        }
        page?.let { pdf.finishPage(it) }
    }

    suspend fun convert(context: Context, uri: Uri, filename: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val extension = filename.substringAfterLast('.', "").lowercase()
                require(extension == "docx" || extension == "xlsx") {
                    "Basic conversion supports DOCX and XLSX only"
                }
                val entries = zipEntries(context, uri)
                val lines = if (extension == "docx") wordLines(entries) else emptyList()
                if (extension == "docx") require(lines.isNotEmpty()) { "Document has no readable content" }
                val pdf = PdfDocument()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f }
                val width = 595
                val height = 842
                var pageNumber = 0
                var page: PdfDocument.Page? = null
                var y = 0f
                fun startPage() {
                    page?.let { pdf.finishPage(it) }
                    pageNumber++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
                    page!!.canvas.drawColor(Color.WHITE)
                    y = 48f
                }
                fun drawLine(text: String) {
                    if (page == null || y > height - 45f) startPage()
                    page!!.canvas.drawText(text, 40f, y, paint)
                    y += 19f
                }
                try {
                    if (extension == "xlsx") {
                        renderExcel(pdf, excelRows(entries))
                    } else {
                    startPage()
                    lines.forEach { line ->
                        if (line.isBlank()) { drawLine(" "); return@forEach }
                        var remaining = line.trim()
                        while (remaining.isNotEmpty()) {
                            val limit = paint.breakText(remaining, true, width - 80f, null).coerceAtLeast(1)
                            if (limit >= remaining.length) {
                                drawLine(remaining)
                                break
                            }
                            val boundary = remaining.lastIndexOf(' ', limit - 1)
                            val end = if (boundary > 0) boundary else limit
                            drawLine(remaining.substring(0, end).trimEnd())
                            remaining = remaining.substring(end).trimStart()
                        }
                    }
                    page?.let { pdf.finishPage(it) }
                    }
                    val stem = filename.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "_").take(50)
                    val temp = File(context.cacheDir, "${stem}_converted_${System.currentTimeMillis()}.pdf")
                    temp.outputStream().use { pdf.writeTo(it) }
                    try { DocumentStorage.savePdf(context, temp) } finally { temp.delete() }
                } finally {
                    pdf.close()
                }
            }
        }
}
