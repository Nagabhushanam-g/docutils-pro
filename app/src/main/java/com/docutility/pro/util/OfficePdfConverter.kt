package com.docutility.pro.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader

/**
 * Basic, offline OOXML-to-PDF conversion. Supports DOCX paragraphs and XLSX cell
 * values; advanced formatting, embedded images, formulas and legacy DOC/XLS
 * are NOT preserved. Never claim layout-fidelity for this converter.
 */
object OfficePdfConverter {
    private fun children(node: Node, name: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = (node as Element).getElementsByTagNameNS("*", name)
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(result::add)
        return result
    }

    private fun parse(bytes: ByteArray): org.w3c.dom.Document {
        // Android's built-in XML parser does not support every Apache Xerces feature.
        // Reject DTDs before parsing and block external entity resolution explicitly.
        val xml = bytes.toString(Charsets.UTF_8)
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
            "XML DTDs and entities are not supported"
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(bytes.inputStream())
    }

    private fun zipEntries(context: Context, uri: Uri): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                var total = 0L
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name.startsWith("word/") || entry.name.startsWith("xl/"))) {
                        val bytes = zip.readBytes()
                        total += bytes.size
                        require(total <= 24L * 1024 * 1024) { "Document too large" }
                        result[entry.name] = bytes
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Unable to open document")
        return result
    }

    private fun wordLines(entries: Map<String, ByteArray>): List<String> {
        val xml = parse(entries["word/document.xml"] ?: error("Invalid DOCX file"))
        return children(xml.documentElement, "p").map { p ->
            children(p, "t").joinToString("") { it.textContent.orEmpty() }
        }
    }

    private fun excelLines(entries: Map<String, ByteArray>): List<String> {
        val shared = entries["xl/sharedStrings.xml"]?.let { parse(it) }
        val strings = shared?.let { children(it.documentElement, "si").map { si ->
            children(si, "t").joinToString("") { it.textContent.orEmpty() }
        } }.orEmpty()
        val sheets = entries.keys.filter { Regex("xl/worksheets/sheet[0-9]+\\.xml").matches(it) }.sorted()
        require(sheets.isNotEmpty()) { "No Excel worksheets found" }
        val lines = mutableListOf<String>()
        sheets.forEachIndexed { index, name ->
            lines.add("Worksheet ${index + 1}")
            val sheet = parse(entries.getValue(name))
            children(sheet.documentElement, "row").forEach { row ->
                val cells = children(row, "c").map { cell ->
                    val value = children(cell, "v").firstOrNull()?.textContent.orEmpty()
                    when (cell.getAttribute("t")) {
                        "s" -> strings.getOrNull(value.toIntOrNull() ?: -1).orEmpty()
                        "inlineStr" -> children(cell, "t").joinToString("") { it.textContent.orEmpty() }
                        else -> value
                    }
                }
                lines.add(cells.joinToString(" | "))
            }
            lines.add("")
        }
        return lines
    }

    suspend fun convert(context: Context, uri: Uri, filename: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val extension = filename.substringAfterLast('.', "").lowercase()
                require(extension == "docx" || extension == "xlsx") {
                    "Basic conversion supports DOCX and XLSX only"
                }
                val entries = zipEntries(context, uri)
                val lines = if (extension == "docx") wordLines(entries) else excelLines(entries)
                require(lines.isNotEmpty()) { "Document has no readable content" }
                val pdf = PdfDocument()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f }
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
                    y += 17f
                }
                try {
                    startPage()
                    lines.forEach { line ->
                        if (line.isBlank()) { drawLine(" "); return@forEach }
                        var remaining = line
                        while (remaining.isNotEmpty()) {
                            val count = paint.breakText(remaining, true, width - 80f, null).coerceAtLeast(1)
                            drawLine(remaining.take(count))
                            remaining = remaining.drop(count)
                        }
                    }
                    page?.let { pdf.finishPage(it) }
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
