package com.docutility.pro.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

enum class ScanAppearance { ORIGINAL, ENHANCED, BLACK_AND_WHITE }

object PdfProcessor {

    suspend fun convertImagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        outputFile: File,
        compressionQuality: Int = 80, // 0 to 100 for compression
        appearance: ScanAppearance = ScanAppearance.ORIGINAL,
        watermarkText: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val pdfDoc = PdfDocument()

            imageUris.forEachIndexed { index, uri ->
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val originalBitmap = BitmapFactory.decodeStream(stream) ?: error("Unable to decode selected image")

                    // Optional compression via JPEG re-encoding
                    val compressedStream = ByteArrayOutputStream()
                    val adjustedBitmap = if (appearance == ScanAppearance.ORIGINAL) originalBitmap else {
                        val adjusted = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
                        val matrix = if (appearance == ScanAppearance.BLACK_AND_WHITE) {
                            ColorMatrix().apply { setSaturation(0f) }
                        } else {
                            // Gentle contrast only; avoid clipping pale handwriting and paper highlights.
                            ColorMatrix(floatArrayOf(
                                1.06f, 0f, 0f, 0f, -7f,
                                0f, 1.06f, 0f, 0f, -7f,
                                0f, 0f, 1.06f, 0f, -7f,
                                0f, 0f, 0f, 1f, 0f
                            ))
                        }
                        Canvas(adjusted).drawBitmap(originalBitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                            colorFilter = ColorMatrixColorFilter(matrix)
                        })
                        adjusted
                    }
                    adjustedBitmap.compress(Bitmap.CompressFormat.JPEG, compressionQuality, compressedStream)
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
                    if (adjustedBitmap !== originalBitmap) adjustedBitmap.recycle()
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

