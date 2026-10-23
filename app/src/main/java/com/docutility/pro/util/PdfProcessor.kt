package com.docutility.pro.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object PdfProcessor {

    suspend fun convertImagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        outputFile: File,
        compressionQuality: Int = 80, // 0 to 100 for compression
        watermarkText: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val pdfDoc = PdfDocument()

            imageUris.forEachIndexed { index, uri ->
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val originalBitmap = BitmapFactory.decodeStream(stream) ?: error("Unable to decode selected image")

                    // Optional compression via JPEG re-encoding
                    val compressedStream = ByteArrayOutputStream()
                    originalBitmap.compress(Bitmap.CompressFormat.JPEG, compressionQuality, compressedStream)
                    val compressedBytes = compressedStream.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)

                    // Standard A4 dimensions (595 x 842 points @ 72 dpi)
                    val pageWidth = 595
                    val pageHeight = 842
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = pdfDoc.startPage(pageInfo)
                    val canvas: Canvas = page.canvas

                    // Scale & center image
                    val scale = minOf(
                        pageWidth.toFloat() / bitmap.width.toFloat(),
                        pageHeight.toFloat() / bitmap.height.toFloat()
                    )
                    val scaledW = bitmap.width * scale
                    val scaledH = bitmap.height * scale
                    val left = (pageWidth - scaledW) / 2f
                    val top = (pageHeight - scaledH) / 2f

                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + scaledW, top + scaledH), paint)

                    // Apply Watermark if provided
                    watermarkText?.let { text ->
                        val textPaint = Paint().apply {
                            color = Color.argb(60, 128, 128, 128)
                            textSize = 42f
                            isAntiAlias = true
                            isFakeBoldText = true
                        }
                        canvas.save()
                        canvas.rotate(-45f, (pageWidth / 2).toFloat(), (pageHeight / 2).toFloat())
                        canvas.drawText(text, (pageWidth / 4).toFloat(), (pageHeight / 2).toFloat(), textPaint)
                        canvas.restore()
                    }

                    pdfDoc.finishPage(page)
                    bitmap.recycle()
                    originalBitmap.recycle()
                }
            }

            FileOutputStream(outputFile).use { out ->
                pdfDoc.writeTo(out)
            }
            pdfDoc.close()
            outputFile
        }
    }
}

