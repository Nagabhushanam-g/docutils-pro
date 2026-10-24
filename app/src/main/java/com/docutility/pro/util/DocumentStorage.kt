package com.docutility.pro.util

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Public, user-visible storage. Created on first successful save. Android 10+ scoped storage. */
object DocumentStorage {
    fun savePdf(context: Context, file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/DocUtils Pro")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: error("Unable to create PDF destination")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to write PDF")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun saveScannedImage(context: Context, source: Uri): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Scan_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocUtils Pro")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create image destination")
        try {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                    ?: error("Unable to write image")
            } ?: error("Unable to read scanned image")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Rename only a document owned by this app; do not change its folder or URI. */
    fun rename(context: Context, item: SavedItem, requestedName: String): Boolean {
        val stem = requestedName.trim().replace(Regex("[\\\\/:*?\\\"<>|]"), "_")
            .removeSuffix(if (item.isPdf) ".pdf" else ".jpg").trim()
        if (stem.isBlank() || stem.length > 100) return false
        val name = stem + if (item.isPdf) ".pdf" else ".jpg"
        return context.contentResolver.update(
            item.uri,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
            null, null
        ) > 0
    }

    fun delete(context: Context, item: SavedItem): Boolean =
        context.contentResolver.delete(item.uri, null, null) > 0

    data class SavedItem(val uri: Uri, val name: String, val isPdf: Boolean)

    fun recentFiles(context: Context, limit: Int = 30): List<SavedItem> {
        val resolver = context.contentResolver
        val results = mutableListOf<SavedItem>()
        val collection = MediaStore.Files.getContentUri("external")
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf("Documents/DocUtils Pro/", "Pictures/DocUtils Pro/")
        resolver.query(collection, columns, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext() && results.size < limit) {
                val mime = cursor.getString(mimeColumn) ?: continue
                if (mime != "application/pdf" && !mime.startsWith("image/")) continue
                results.add(SavedItem(
                    ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    cursor.getString(nameColumn) ?: "Document",
                    mime == "application/pdf"
                ))
            }
        }
        return results
    }
}
