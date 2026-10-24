package com.docutility.pro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DocumentViewer(uri: Uri, isPdf: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var pageIndex by remember(uri) { mutableIntStateOf(0) }
    var pageCount by remember(uri) { mutableIntStateOf(1) }
    var zoom by remember(uri, pageIndex) { mutableFloatStateOf(1f) }
    var offsetX by remember(uri, pageIndex) { mutableFloatStateOf(0f) }
    var offsetY by remember(uri, pageIndex) { mutableFloatStateOf(0f) }
    var loadError by remember(uri) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, pageIndex, isPdf) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (isPdf) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            pageCount = renderer.pageCount
                            if (renderer.pageCount == 0) return@use null
                            renderer.openPage(pageIndex.coerceIn(0, renderer.pageCount - 1)).use { page ->
                                val scale = (1600f / page.width).coerceAtMost(3f)
                                val width = (page.width * scale).toInt().coerceAtLeast(1)
                                val height = (page.height * scale).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(Color.WHITE)
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
            }.getOrNull()
        }
        loadError = value == null
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(if (isPdf) "PDF Viewer" else "Image Viewer", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    val current = bitmap
                    if (current != null) {
                        Image(
                            bitmap = current.asImageBitmap(),
                            contentDescription = "Document preview",
                            modifier = Modifier.fillMaxSize()
                                .pointerInput(uri, pageIndex) {
                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                        zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                        if (zoom > 1f) {
                                            offsetX += pan.x
                                            offsetY += pan.y
                                        } else {
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = offsetX
                                    translationY = offsetY
                                },
                            contentScale = ContentScale.Fit
                        )
                    } else if (loadError) Text("Unable to open this document")
                    else CircularProgressIndicator()
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPdf) {
                        TextButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) { Text("Previous") }
                        Text("${pageIndex + 1} / $pageCount")
                        TextButton(onClick = { pageIndex++ }, enabled = pageIndex < pageCount - 1) { Text("Next") }
                    }
                    TextButton(onClick = { zoom = 1f; offsetX = 0f; offsetY = 0f }) { Text("Reset zoom") }
                }
            }
        }
    }
}
