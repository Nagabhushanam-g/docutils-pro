package com.docutility.pro.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative on-device crop detection for a document photographed against a
 * reasonably uniform contrasting background. Returns null when the document
 * boundary is uncertain, so we never silently trim important content.
 *
 * This detects an axis-aligned rectangle; perspective correction is still
 * available through Smart Scan, and the user can adjust crops manually.
 */
object AutoDocumentCropper {
    private data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    /**
     * Creates a new saved image only when a safe crop is detected.
     * The input URI and original saved image are never overwritten.
     */
    fun autoCrop(context: Context, original: Uri): Uri? {
        val analysis = decodeSampled(context, original, 700) ?: return null
        val bounds = try {
            detectBounds(analysis)
        } finally {
            analysis.recycle()
        } ?: return null

        val full = decodeSampled(context, original, 4000) ?: return null
        var cropped: Bitmap? = null
        var oriented: Bitmap? = null
        val temp = File(context.cacheDir, "autocrop_${System.nanoTime()}.jpg")
        try {
            val left = (bounds.left * full.width).toInt().coerceIn(0, full.width - 1)
            val top = (bounds.top * full.height).toInt().coerceIn(0, full.height - 1)
            val right = (bounds.right * full.width).toInt().coerceIn(left + 1, full.width)
            val bottom = (bounds.bottom * full.height).toInt().coerceIn(top + 1, full.height)
            cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
            val rotation = context.contentResolver.openInputStream(original)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            oriented = if (rotation == 0f) cropped else {
                Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, Matrix().apply {
                    postRotate(rotation)
                }, true)
            }
            temp.outputStream().use { output ->
                check(oriented.compress(Bitmap.CompressFormat.JPEG, 94, output)) { "Cannot save cropped photo" }
            }
            return DocumentStorage.saveScannedImage(context, Uri.fromFile(temp))
        } finally {
            temp.delete()
            if (oriented != null && oriented !== cropped && oriented !== full) oriented.recycle()
            if (cropped != null && cropped !== full) cropped.recycle()
            full.recycle()
        }
    }

    private fun decodeSampled(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, dimensions)
        }
        if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0) return null
        var sample = 1
        while (max(dimensions.outWidth / sample, dimensions.outHeight / sample) > maxDimension ||
            (dimensions.outWidth.toLong() / sample) * (dimensions.outHeight.toLong() / sample) > 12_000_000L
        ) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }

    private fun detectBounds(image: Bitmap): Bounds? {
        val width = image.width
        val height = image.height
        if (width < 160 || height < 160) return null
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, 0, 0, width, height)

        // Estimate the background from the photo's four corners. Require
        // three similar corners rather than assuming the paper is white.
        fun avgCorner(left: Int, top: Int): IntArray {
            var red = 0L
            var green = 0L
            var blue = 0L
            var n = 0
            val x0 = if (left == 0) width / 25 else width * 22 / 25
            val y0 = if (top == 0) height / 25 else height * 22 / 25
            val x1 = min(width, x0 + max(3, width / 14))
            val y1 = min(height, y0 + max(3, height / 14))
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
                val color = pixels[y * width + x]
                red += (color shr 16) and 255
                green += (color shr 8) and 255
                blue += color and 255
                n++
            }
            return intArrayOf((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
        }
        val corners = listOf(avgCorner(0, 0), avgCorner(1, 0), avgCorner(0, 1), avgCorner(1, 1))
        fun colorDistance(a: IntArray, b: IntArray) =
            abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
        val matching = corners.map { corner -> corners.filter { colorDistance(it, corner) < 90 } }
            .maxByOrNull { it.size } ?: return null
        if (matching.size < 3) return null
        val background = IntArray(3) { channel -> matching.sumOf { it[channel] } / matching.size }

        val rowHits = IntArray(height)
        val columnHits = IntArray(width)
        var xCount = 0
        var yCount = 0
        for (y in 0 until height step 2) {
            yCount++
            for (x in 0 until width step 2) {
                val color = pixels[y * width + x]
                val difference = abs(((color shr 16) and 255) - background[0]) +
                    abs(((color shr 8) and 255) - background[1]) +
                    abs((color and 255) - background[2])
                if (difference >= 105) {
                    rowHits[y]++
                    columnHits[x]++
                }
            }
        }
        xCount = (width + 1) / 2
        val minRow = (xCount * 0.22).toInt().coerceAtLeast(1)
        val minCol = (yCount * 0.22).toInt().coerceAtLeast(1)
        val validRows = (0 until height step 2).filter { rowHits[it] >= minRow }
        val validCols = (0 until width step 2).filter { columnHits[it] >= minCol }
        if (validRows.size < height * 0.25 / 2 || validCols.size < width * 0.25 / 2) return null

        val rawLeft = validCols.first()
        val rawRight = min(width - 1, validCols.last() + 2)
        val rawTop = validRows.first()
        val rawBottom = min(height - 1, validRows.last() + 2)
        val boxWidth = rawRight - rawLeft
        val boxHeight = rawBottom - rawTop
        if (boxWidth < width * 0.28 || boxHeight < height * 0.28) return null
        val areaRatio = boxWidth.toDouble() * boxHeight / (width.toDouble() * height)
        if (areaRatio < 0.17 || areaRatio > 0.91) return null

        // Require a substantial, fairly solid interior, not just text,
        // logos or isolated objects on an otherwise uniform page.
        var occupied = 0
        var total = 0
        for (y in rawTop until rawBottom step 3) for (x in rawLeft until rawRight step 3) {
            val color = pixels[y * width + x]
            val distance = abs(((color shr 16) and 255) - background[0]) +
                abs(((color shr 8) and 255) - background[1]) +
                abs((color and 255) - background[2])
            if (distance >= 105) occupied++
            total++
        }
        if (total == 0 || occupied < total * 0.50) return null

        // Include a safety border to avoid cutting document edges.
        val paddingX = max(3, (width * 0.018).toInt())
        val paddingY = max(3, (height * 0.018).toInt())
        val l = max(0, rawLeft - paddingX)
        val t = max(0, rawTop - paddingY)
        val r = min(width, rawRight + paddingX)
        val b = min(height, rawBottom + paddingY)
        val remainingArea = (r - l).toDouble() * (b - t) / (width.toDouble() * height)
        if (remainingArea > 0.96) return null
        return Bounds(l.toFloat() / width, t.toFloat() / height,
            r.toFloat() / width, b.toFloat() / height)
    }
}
