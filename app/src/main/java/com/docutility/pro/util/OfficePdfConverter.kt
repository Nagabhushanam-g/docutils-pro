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
                    if (!entry.isDirectory && (entry.name.startsWith("word/") || (entry.name.startsWith("xl/") || entry.name == "[Content_Types].xml"))) {
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

    private data class PrintArea(val firstColumn: Int, val lastColumn: Int, val firstRow: Int, val lastRow: Int)

    private fun excelColumnIndex(letters: String): Int =
        letters.uppercase().fold(0) { n, ch -> n * 26 + (ch - 'A' + 1) } - 1

    /** Excel stores _xlnm.Print_Area in workbook.xml, not in the worksheet XML. */
    private fun printAreas(entries: Map<String, ByteArray>): Map<Int, PrintArea> {
        val workbook = entries["xl/workbook.xml"] ?: return emptyMap()
        val parser = xmlParser(workbook)
        val areas = mutableMapOf<Int, PrintArea>()
        var sheetIndex: Int? = null
        val reference = Regex("""(?:'[^']+'|[^!]+)!\$?([A-Za-z]+)\$?(\d+):\$?([A-Za-z]+)\$?(\d+)""")
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "definedName") {
                sheetIndex = if (parser.getAttributeValue(null, "name") == "_xlnm.Print_Area")
                    parser.getAttributeValue(null, "localSheetId")?.toIntOrNull() else null
            } else if (parser.eventType == XmlPullParser.TEXT && sheetIndex != null) {
                val match = reference.find(parser.text.orEmpty())
                if (match != null) {
                    val (a, b, d, e) = match.destructured
                    val left = excelColumnIndex(a)
                    val right = excelColumnIndex(d)
                    val top = b.toIntOrNull() ?: 1
                    val bottom = e.toIntOrNull() ?: top
                    if (left in 0..255 && right in left..255 && top in 1..20000 && bottom in top..20000)
                        areas[sheetIndex!!] = PrintArea(left, right, top, bottom)
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "definedName") {
                sheetIndex = null
            }
        }
        return areas
    }

    private fun excelRows(entries: Map<String, ByteArray>): List<Pair<String, List<List<String>>>> {
        val areas = printAreas(entries)
        val strings = entries["xl/sharedStrings.xml"]?.let { groupedText(it, "si", "t") }.orEmpty()
        val sheets = entries.keys.filter { Regex("xl/worksheets/sheet[0-9]+[.]xml").matches(it) }
            .sortedBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }
        require(sheets.isNotEmpty()) { "No Excel worksheets found" }
        return sheets.mapIndexed { index, name ->
            val rows = mutableListOf<List<String>>()
            val area = areas[index]
            var rowNumber = 0
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
                        "row" -> {
                            cells = sortedMapOf()
                            nextColumn = 0
                            val nextRow = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (rowNumber + 1)
                            // Sparse worksheet XML omits empty rows, but they still
                            // occupy space inside an explicitly defined print area.
                            if (area != null && nextRow > maxOf(rowNumber + 1, area.firstRow)) {
                                val firstMissing = maxOf(rowNumber + 1, area.firstRow)
                                val lastMissing = minOf(nextRow - 1, area.lastRow)
                                if (firstMissing <= lastMissing) {
                                    for (missing in firstMissing..lastMissing) {
                                        rows.add(List(area.lastColumn - area.firstColumn + 1) { "" })
                                    }
                                }
                            }
                            rowNumber = nextRow
                        }
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
                            if (text.isNotBlank() && column in 0..255 && (area == null || (rowNumber in area.firstRow..area.lastRow && column in area.firstColumn..area.lastColumn))) cells[column] = text
                            inCell = false
                        }
                        "row" -> {
                            if (area == null || rowNumber in area.firstRow..area.lastRow) {
                                val first = area?.firstColumn ?: 0
                                val last = area?.lastColumn ?: (if (cells.isEmpty()) -1 else cells.lastKey())
                                if (last >= first) {
                                    rows.add((first..last).map { cells[it].orEmpty() })
                                    totalCells += (last - first + 1).toLong()
                                }
                            }
                        }
                    }
                }
                require(rows.size <= 20000) { "Worksheet exceeds the 20,000 non-empty row limit" }
                require(totalCells <= 1_000_000L) {
                    "Worksheet has too many cells to convert safely"
                }
            }
            if (area != null && rowNumber < area.lastRow) {
                for (missing in maxOf(rowNumber + 1, area.firstRow)..area.lastRow) {
                    rows.add(List(area.lastColumn - area.firstColumn + 1) { "" })
                }
            }
            "Worksheet ${index + 1}" to rows
        }
    }

    // Extract source worksheet print orientation for the import dialog and
    // subsequent source-aware conversion. Each sheet may specify its own setup.
    fun sourceSheetOrientations(context: Context, uri: Uri): List<String> {
        val entries = zipEntries(context, uri)
        return entries.keys.filter { Regex("xl/worksheets/sheet[0-9]+[.]xml").matches(it) }
            .sortedBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }
            .map { name ->
                val parser = xmlParser(entries.getValue(name))
                var orientation = "unspecified"
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "pageSetup") {
                        orientation = parser.getAttributeValue(null, "orientation") ?: "unspecified"
                        break
                    }
                }
                orientation
            }
    }

    // Read original Excel column dimensions instead of guessing widths from text.
    // Column dimensions are stored as character units in worksheet <cols>.
    private fun excelColumnWidths(entries: Map<String, ByteArray>): List<Map<Int, Float>> {
        val names = entries.keys.filter { Regex("xl/worksheets/sheet[0-9]+[.]xml").matches(it) }
            .sortedBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }
        return names.map { name ->
            val widths = mutableMapOf<Int, Float>()
            val parser = xmlParser(entries.getValue(name))
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "col") {
                    val first = parser.getAttributeValue(null, "min")?.toIntOrNull()
                    val last = parser.getAttributeValue(null, "max")?.toIntOrNull()
                    val w = parser.getAttributeValue(null, "width")?.toFloatOrNull()
                    if (first != null && last != null && w != null &&
                        first in 1..256 && last in first..256 && w > 0f) {
                        for (index in first..last) widths[index - 1] = w
                    }
                }
            }
            widths
        }
    }

    // Row heights in worksheet XML are expressed in points, matching PDF units.
    private fun excelRowHeights(entries: Map<String, ByteArray>): List<Map<Int, Float>> {
        val names = entries.keys.filter { Regex("xl/worksheets/sheet[0-9]+[.]xml").matches(it) }
            .sortedBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }
        return names.map { name ->
            val heights = mutableMapOf<Int, Float>()
            val parser = xmlParser(entries.getValue(name))
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "row") {
                    val index = parser.getAttributeValue(null, "r")?.toIntOrNull()
                    val height = parser.getAttributeValue(null, "ht")?.toFloatOrNull()
                    if (index != null && index in 1..20000 && height != null && height > 0f)
                        heights[index] = height.coerceAtMost(500f)
                }
            }
            heights
        }
    }

    private data class HorizontalMerge(val row: Int, val lastRow: Int, val first: Int, val last: Int)

    private fun excelHorizontalMerges(entries: Map<String, ByteArray>): List<List<HorizontalMerge>> {
        val areas = printAreas(entries)
        val names = entries.keys.filter { Regex("xl/worksheets/sheet[0-9]+[.]xml").matches(it) }
            .sortedBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }
        val range = Regex("""([A-Za-z]+)([0-9]+):([A-Za-z]+)([0-9]+)""")
        return names.mapIndexed { index, name ->
            val merges = mutableListOf<HorizontalMerge>()
            val area = areas[index]
            val parser = xmlParser(entries.getValue(name))
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "mergeCell") {
                    val match = range.matchEntire(parser.getAttributeValue(null, "ref").orEmpty())
                    if (match != null) {
                        val (a, r1, b, r2) = match.destructured
                        val firstRow = r1.toIntOrNull() ?: 0
                        val lastRow = r2.toIntOrNull() ?: 0
                        val firstCol = excelColumnIndex(a)
                        val lastCol = excelColumnIndex(b)
                        // Include rectangular merges spanning multiple rows.
                        // The text belongs to the top-left cell only.
                        if (firstRow <= lastRow && firstCol <= lastCol &&
                            (area == null || (firstRow >= area.firstRow && lastRow <= area.lastRow &&
                                firstCol >= area.firstColumn && lastCol <= area.lastColumn))) {
                            merges.add(HorizontalMerge(firstRow - (area?.firstRow ?: 1),
                                lastRow - (area?.firstRow ?: 1),
                                firstCol - (area?.firstColumn ?: 0),
                                lastCol - (area?.firstColumn ?: 0)))
                        }
                    }
                }
            }
            merges
        }
    }

    private data class SourceMargins(val left: Float, val right: Float, val top: Float, val bottom: Float)

    private fun excelPageMargins(entries: Map<String, ByteArray>): List<SourceMargins> {
        val names = entries.keys.filter { Regex("xl/worksheets/sheet[0-9]+[.]xml").matches(it) }
            .sortedBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }
        return names.map { name ->
            val parser = xmlParser(entries.getValue(name))
            var result = SourceMargins(35f, 35f, 35f, 35f)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "pageMargins") {
                    fun points(key: String, fallback: Float): Float =
                        (parser.getAttributeValue(null, key)?.toFloatOrNull()?.times(72f) ?: fallback)
                            .coerceIn(0f, 180f)
                    result = SourceMargins(points("left", 35f), points("right", 35f),
                        points("top", 35f), points("bottom", 35f))
                    break
                }
            }
            result
        }
    }

    private fun renderExcel(pdf: PdfDocument, sheets: List<Pair<String, List<List<String>>>>, landscape: Boolean, sheetOrientations: List<Boolean?>, sourceWidths: List<Map<Int, Float>>, sourceMerges: List<List<HorizontalMerge>>, sourceMargins: List<SourceMargins>, sourceRowHeights: List<Map<Int, Float>>, sourceAreas: Map<Int, PrintArea>) {
        // A4 at 72 PDF points per inch, with selectable print orientation.
        var width = if (landscape) 842 else 595
        var height = if (landscape) 595 else 842
        var leftMargin = 35f
        var rightMargin = 35f
        var top = 35f
        var bottom = height - 35f
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

        sheets.forEachIndexed { sheetIndex, (title, rows) ->
            val useLandscape = sheetOrientations.getOrNull(sheetIndex) ?: landscape
            width = if (useLandscape) 842 else 595
            height = if (useLandscape) 595 else 842
            val margins = sourceMargins.getOrNull(sheetIndex) ?: SourceMargins(35f, 35f, 35f, 35f)
            leftMargin = margins.left
            rightMargin = margins.right
            top = margins.top
            bottom = height - margins.bottom
            if (rows.isEmpty()) {
                newPage(title)
                return@forEachIndexed
            }
            val cols = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
            // Keep each worksheet's columns together on the page. Scale text to
            // the available printable width instead of generating horizontal tiles.
            val columnsPerPage = cols
            val tiles = 1
            val cellFontSize = (12f * (8f / cols.coerceAtLeast(8))).coerceIn(7f, 12f)
            font.textSize = cellFontSize
            headerFont.textSize = cellFontSize
            // Row height must scale with text size; fixed 34pt rows waste paper
            // and create unnecessary extra pages for dense printed worksheets.
            val lineHeight = (cellFontSize * 1.35f).coerceAtLeast(8f)
            val rowPadding = 5f
            val minimumRowHeight = (lineHeight + rowPadding).coerceAtLeast(14f)
            for (tile in 0 until tiles) {
                val first = tile * columnsPerPage
                val count = minOf(columnsPerPage, cols - first)
                val printableWidth = width - leftMargin - rightMargin
                val preferredWidths = (0 until count).map { i ->
                    val column = first + i
                    val originalWidth = sourceWidths.getOrNull(sheetIndex)?.get(column)
                    if (originalWidth != null) {
                        // Excel width is measured in approximately 7-pixel characters.
                        // Preserve relative proportions; normalize to printable width below.
                        originalWidth.coerceIn(2f, 100f) * 7f
                    } else {
                    val sample = rows.asSequence().map { it.getOrNull(column).orEmpty() }
                        .filter { it.isNotBlank() }.take(250)
                        .maxOfOrNull { font.measureText(it.take(55)) + 14f } ?: 70f
                    sample.coerceIn(16f, printableWidth * 0.55f)
                    }
                }
                val totalPreferred = preferredWidths.sum()
                val cellWidths = preferredWidths.map { it * printableWidth / totalPreferred }
                val cellStarts = cellWidths.runningFold(leftMargin) { x, w -> x + w }
                val label = if (tiles == 1) title else "$title (columns ${first + 1}-${first + count})"
                // Some worksheets contain leading rows populated only in other
                // column tiles. Avoid rendering those as blank rows at the top
                // of this tile; keep interior spacing unchanged.
                val tileRows = rows
                val merges = sourceMerges.getOrNull(sheetIndex).orEmpty()
                if (tileRows.isEmpty()) continue
                val header = tileRows.first()
                // Split tall cells across pages rather than drawing beyond the page.
                // Keep each column's wrapped text and continue it on the next page.
                fun drawRow(row: List<String>, headerRow: Boolean, rowIndex: Int) {
                    val mergedAt = merges.filter { rowIndex in it.row..it.lastRow }
                    val wrapped = (0 until count).map { i ->
                        val merge = mergedAt.firstOrNull { i in it.first..it.last }
                        val available = if (merge != null && merge.first == i)
                            cellWidths.subList(i, (merge.last + 1).coerceAtMost(count)).sum()
                        else cellWidths[i]
                        if (merge != null && (merge.first != i || merge.row != rowIndex)) listOf("")
                        else listOf(row.getOrNull(first + i).orEmpty().replace('\n', ' ').replace('\r', ' '))
                    }
                    var offset = 0
                    val maxLines = wrapped.maxOf { it.size }
                    while (offset < maxLines) {
                        val availableLines = ((bottom - y - rowPadding) / lineHeight).toInt()
                        if (availableLines < 1 || bottom - y < minimumRowHeight) {
                            newPage(label)
                        }
                        val capacity = ((bottom - y - rowPadding) / lineHeight).toInt().coerceAtLeast(1)
                        val chunk = minOf(maxLines - offset, capacity)
                        val originalRow = rowIndex + (sourceAreas[sheetIndex]?.firstRow ?: 1)
                        val requestedHeight = sourceRowHeights.getOrNull(sheetIndex)?.get(originalRow)
                        // Source row heights are in unscaled points. The cell font and
                        // widths are already fitted to the printable page, so cap the
                        // requested height by the same effective width scaling factor.
                        // This avoids expanding a dense worksheet into extra pages.
                        val sourceTotalWidth = (0 until count).sumOf { i ->
                            (sourceWidths.getOrNull(sheetIndex)?.get(first + i) ?: 8.43f).toDouble() * 7.0
                        }.toFloat().coerceAtLeast(1f)
                        val effectiveScale = (printableWidth / sourceTotalWidth).coerceAtMost(1f)
                        val scaledHeight = (requestedHeight ?: 0f) * effectiveScale
                        val rowHeight = maxOf(minimumRowHeight, chunk * lineHeight + rowPadding,
                            if (offset == 0) scaledHeight else 0f)
                            .coerceAtMost((bottom - y).coerceAtLeast(minimumRowHeight))
                        for (i in 0 until count) {
                            val x = cellStarts[i]
                            val merge = mergedAt.firstOrNull { i in it.first..it.last }
                            if (merge != null && merge.first != i) continue
                            val spanWidth = if (merge != null)
                                cellWidths.subList(i, (merge.last + 1).coerceAtMost(count)).sum()
                            else cellWidths[i]
                            // Avoid horizontal rules through vertically merged cells.
                            // Keep the outer boundary of each merged region.
                            if (merge != null && merge.lastRow > merge.row) {
                                val canvas = page!!.canvas
                                canvas.drawLine(x, y, x, y + rowHeight, border)
                                canvas.drawLine(x + spanWidth, y, x + spanWidth, y + rowHeight, border)
                                if (rowIndex == merge.row) canvas.drawLine(x, y, x + spanWidth, y, border)
                                if (rowIndex == merge.lastRow) canvas.drawLine(x, y + rowHeight, x + spanWidth, y + rowHeight, border)
                            } else {
                                page!!.canvas.drawRect(x, y, x + spanWidth, y + rowHeight, border)
                            }
                            val pen = if (headerRow) headerFont else font
                            val canvas = page!!.canvas
                            canvas.save()
                            canvas.clipRect(x + 2f, y, x + spanWidth - 2f, y + rowHeight)
                            wrapped[i].drop(offset).take(chunk).forEachIndexed { lineIndex, line ->
                                canvas.drawText(line, x + 2f, y + cellFontSize + 2f + lineIndex * lineHeight, pen)
                            }
                            canvas.restore()
                        }
                        y += rowHeight
                        offset += chunk
                        if (offset < maxLines) newPage(label)
                    }
                }
                newPage(label)
                tileRows.forEachIndexed { rowIndex, row ->
                    // Repeat the first worksheet row as a header on ordinary page breaks.
                    if (y >= bottom - minimumRowHeight) {
                        newPage(label)
                    }
                    drawRow(row, rowIndex == 0, rowIndex)
                }
            }
        }
        page?.let { pdf.finishPage(it) }
    }

    suspend fun convert(context: Context, uri: Uri, filename: String, landscape: Boolean = true): Result<Uri> =
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
                        // Honor the source workbook's first worksheet orientation rather
                        // than imposing the app's previous landscape default.
                        // Full print scaling, merged-cell layout and page breaks still
                        // require a layout-capable Office renderer.
                        val firstSheetName = entries.keys
                            .filter { Regex("xl/worksheets/sheet[0-9]+[.]xml").matches(it) }
                            .minByOrNull { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: Int.MAX_VALUE }
                        val sourceLandscape = firstSheetName?.let { sheetName ->
                            val setup = xmlParser(entries.getValue(sheetName))
                            var orientation: Boolean? = null
                            while (setup.next() != XmlPullParser.END_DOCUMENT) {
                                if (setup.eventType == XmlPullParser.START_TAG && setup.name == "pageSetup") {
                                    orientation = when (setup.getAttributeValue(null, "orientation")) {
                                        "landscape" -> true
                                        "portrait" -> false
                                        else -> null
                                    }
                                    break
                                }
                            }
                            orientation
                        }
                        val worksheetNames = entries.keys
                            .filter { Regex("xl/worksheets/sheet[0-9]+[.]xml").matches(it) }
                            .sortedBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 }
                        val orientations = worksheetNames.map { sheetName ->
                            val setup = xmlParser(entries.getValue(sheetName))
                            var direction: Boolean? = null
                            while (setup.next() != XmlPullParser.END_DOCUMENT) {
                                if (setup.eventType == XmlPullParser.START_TAG && setup.name == "pageSetup") {
                                    direction = when (setup.getAttributeValue(null, "orientation")) {
                                        "landscape" -> true
                                        "portrait" -> false
                                        else -> null
                                    }
                                    break
                                }
                            }
                            direction
                        }
                        renderExcel(pdf, excelRows(entries), sourceLandscape ?: landscape, orientations, excelColumnWidths(entries), excelHorizontalMerges(entries), excelPageMargins(entries), excelRowHeights(entries), printAreas(entries))
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
