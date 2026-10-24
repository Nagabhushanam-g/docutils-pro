package com.docutility.pro

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.docutility.pro.monetization.AdManager
import com.docutility.pro.monetization.BillingManager
import com.docutility.pro.util.ScanAppearance
import com.docutility.pro.util.PdfProcessor
import com.docutility.pro.util.DocumentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var billingManager: BillingManager
    private lateinit var adManager: AdManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) {}

        billingManager = BillingManager(this)
        adManager = AdManager(this).apply { loadInterstitial() }

        setContent {
            val isProUser by billingManager.isProUser.collectAsState()
            MainScreen(
                isProUser = isProUser,
                onBuyPro = { billingManager.launchPurchaseFlow(this) },
                onShowAd = { onDismissed ->
                    if (isProUser) onDismissed()
                    else adManager.showInterstitial(this, onDismissed)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isProUser: Boolean,
    onBuyPro: () -> Unit,
    onShowAd: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var officeUri by remember { mutableStateOf<Uri?>(null) }
    var officeName by remember { mutableStateOf("") }
    val officePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            officeUri = uri
            officeName = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "Selected Office document"
        }
    }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var savedViewerItem by remember { mutableStateOf<DocumentStorage.SavedItem?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var latestPdf by remember { mutableStateOf<File?>(null) }
    var latestSavedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var recentFiles by remember { mutableStateOf(runCatching { DocumentStorage.recentFiles(context, 500) }.getOrDefault(emptyList())) }
    var libraryFilter by remember { mutableStateOf("All") }
    // Folder assignments are local library labels; original files stay in public storage.
    val libraryPrefs = remember { context.getSharedPreferences("docutils_library", android.content.Context.MODE_PRIVATE) }
    var folderNames by remember {
        mutableStateOf(libraryPrefs.getStringSet("folders", setOf("Bills", "ID Cards", "Personal"))!!.toSet())
    }
    var folderAssignments by remember { mutableStateOf(emptyMap<String, String>()) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var folderDraft by remember { mutableStateOf("") }
    var moveTarget by remember { mutableStateOf<DocumentStorage.SavedItem?>(null) }
    var renameTarget by remember { mutableStateOf<DocumentStorage.SavedItem?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<DocumentStorage.SavedItem?>(null) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var librarySearch by remember { mutableStateOf("") }
    var sortNewestFirst by remember { mutableStateOf(true) }
    var fileMenuUri by remember { mutableStateOf<String?>(null) }
    fun folderOf(uri: Uri): String = folderAssignments[uri.toString()]
        ?: libraryPrefs.getString("folder_" + uri.toString(), "")!!
    fun assignFolder(uri: Uri, folder: String) {
        libraryPrefs.edit().putString("folder_" + uri.toString(), folder).apply()
        folderAssignments = folderAssignments + (uri.toString() to folder)
    }
    if (newFolderDialog) {
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text("Create Folder") },
            text = { OutlinedTextField(value = folderDraft, onValueChange = { folderDraft = it }, label = { Text("Folder name") }) },
            confirmButton = {
                TextButton(onClick = {
                    val name = folderDraft.trim()
                    if (name.isNotEmpty() && name.length <= 40 && name !in folderNames) {
                        folderNames = folderNames + name
                        libraryPrefs.edit().putStringSet("folders", folderNames).apply()
                    }
                    newFolderDialog = false
                    folderDraft = ""
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolderDialog = false }) { Text("Cancel") } }
        )
    }
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename document") },
            text = { OutlinedTextField(value = renameDraft, onValueChange = { renameDraft = it.take(100) }, label = { Text("Filename (without extension)") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = runCatching { DocumentStorage.rename(context, target, renameDraft) }.getOrDefault(false)
                    Toast.makeText(context, if (ok) "Document renamed" else "Unable to rename document", Toast.LENGTH_LONG).show()
                    if (ok) recentFiles = runCatching { DocumentStorage.recentFiles(context, 500) }.getOrDefault(recentFiles)
                    renameTarget = null
                }, enabled = renameDraft.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete document?") },
            text = { Text("Permanently delete ${target.name}? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = runCatching { DocumentStorage.delete(context, target) }.getOrDefault(false)
                    if (ok) {
                        libraryPrefs.edit().remove("folder_" + target.uri.toString()).apply()
                        folderAssignments = folderAssignments - target.uri.toString()
                        recentFiles = runCatching { DocumentStorage.recentFiles(context, 500) }.getOrDefault(recentFiles.filterNot { it.uri == target.uri })
                    }
                    Toast.makeText(context, if (ok) "Document deleted" else "Unable to delete document", Toast.LENGTH_LONG).show()
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
    moveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Organize in folder") },
            text = {
                Column {
                    (listOf("Unfiled") + folderNames.sorted()).forEach { name ->
                        TextButton(onClick = {
                            assignFolder(target.uri, if (name == "Unfiled") "" else name)
                            moveTarget = null
                        }) { Text(name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveTarget = null }) { Text("Close") } }
        )
    }

    officeUri?.let {
        AlertDialog(
            onDismissRequest = { officeUri = null },
            title = { Text("Office document imported") },
            text = {
                Column {
                    Text(officeName)
                    Spacer(Modifier.height(8.dp))
                    Text("File selection is ready. PDF conversion will be available after Word layout and Excel table rendering are implemented and verified.")
                }
            },
            confirmButton = { TextButton(onClick = { officeUri = null }) { Text("OK") } }
        )
    }
    savedViewerItem?.let { item ->
        DocumentViewer(uri = item.uri, isPdf = item.isPdf, onClose = { savedViewerItem = null })
    }

    fun openSaved(item: DocumentStorage.SavedItem) {
        savedViewerItem = item
    }
    var feedback by remember { mutableStateOf<String?>(null) }
    var compressionQuality by remember { mutableFloatStateOf(75f) }
    var scanAppearance by remember { mutableStateOf(ScanAppearance.ORIGINAL) }
    var documentName by remember { mutableStateOf("Document") }
    fun pdfUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)


    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.map { it.imageUri }.orEmpty()
            val allowed = if (isProUser) pages.size else (5 - selectedUris.size).coerceAtLeast(0)
            val saved = pages.take(allowed).mapNotNull { runCatching { DocumentStorage.saveScannedImage(context, it) }.getOrNull() }
            selectedUris = selectedUris + saved
            feedback = "${saved.size} cropped image(s) saved to Pictures/DocUtils Pro"
            recentFiles = runCatching { DocumentStorage.recentFiles(context, 500) }.getOrDefault(recentFiles)
        }
    }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraCapture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { captured ->
        if (captured) {
            pendingCameraUri?.let { uri ->
                if (isProUser || selectedUris.size < 5) {
                    runCatching { DocumentStorage.saveScannedImage(context, uri) }.onSuccess { saved ->
                        selectedUris = selectedUris + saved
                        feedback = "Photo saved to Pictures/DocUtils Pro"
                        recentFiles = runCatching { DocumentStorage.recentFiles(context) }.getOrDefault(recentFiles)
                    }.onFailure { Toast.makeText(context, "Unable to save photo", Toast.LENGTH_LONG).show() }
                } else {
                    Toast.makeText(context, "Free tier limit: 5 images", Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingCameraUri = null
    }
    val cameraPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val photo = File(context.cacheDir, "Scan_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", photo)
            pendingCameraUri = uri
            cameraCapture.launch(uri)
        } else {
            Toast.makeText(context, "Camera permission is required to scan", Toast.LENGTH_LONG).show()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris ->
        if (uris.size > 5 && !isProUser) {
            Toast.makeText(context, "Free tier limit: 5 images. Upgrade to Pro for unlimited batching.", Toast.LENGTH_LONG).show()
            selectedUris = uris.take(5)
        } else {
            selectedUris = uris
        }
    }

    previewUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { previewUri = null },
            title = { Text("Document preview") },
            text = {
                val bitmap = remember(uri) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
                        }
                    }.getOrNull()
                }
                if (bitmap != null) Image(
                    bitmap = bitmap,
                    contentDescription = "Full document preview",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 550.dp),
                    contentScale = ContentScale.Fit
                ) else Text("Preview unavailable")
            },
            confirmButton = { TextButton(onClick = { previewUri = null }) { Text("Close") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DocUtils Pro") },
                actions = {
                    if (!isProUser) {
                        Button(onClick = onBuyPro) {
                            Text("Go Ad-Free")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!isProUser) {
                // AdMob Banner at bottom
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    factory = { ctx ->
                        AdView(ctx).apply {
                            setAdSize(AdSize.BANNER)
                            // AdMob test banner ID
                            adUnitId = "ca-app-pub-3940256099942544/6300978111"
                            loadAd(AdRequest.Builder().build())
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedButton(
                onClick = {
                    officePicker.launch(arrayOf(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel"
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import Word / Excel (Phase 2)") }

            Button(
                onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Select Images (${selectedUris.size} selected)")
            }

            OutlinedButton(
                onClick = {
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        val photo = File(context.cacheDir, "Scan_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", photo)
                        pendingCameraUri = uri
                        cameraCapture.launch(uri)
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Scan with Camera") }

            Button(onClick = {
                val options = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(if (isProUser) 30 else 5)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                GmsDocumentScanning.getClient(options)
                    .getStartScanIntent(context as ComponentActivity)
                    .addOnSuccessListener { sender ->
                        scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                    .addOnFailureListener {
                        Toast.makeText(context, "Smart scan unavailable; use camera instead", Toast.LENGTH_LONG).show()
                    }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Smart Scan: Auto Crop & Enhance")
            }
            Text("Capture documents or select photos, then convert them into a PDF.")
            if (selectedUris.isNotEmpty()) {
                Text("Pages (in PDF order)", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(selectedUris) { index, uri ->
                        Column(modifier = Modifier.width(140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val preview = remember(uri) {
                                runCatching {
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                                        BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
                                    }
                                }.getOrNull()
                            }
                            if (preview != null) {
                                Image(
                                    bitmap = preview,
                                    contentDescription = "Page ${index + 1} preview",
                                    modifier = Modifier.fillMaxWidth().height(180.dp).clickable { previewUri = uri },
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Text("Preview unavailable")
                            }
                            Text("Page ${index + 1} • Tap to preview")
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = {
                                    if (index > 0) {
                                        val pages = selectedUris.toMutableList()
                                        java.util.Collections.swap(pages, index, index - 1)
                                        selectedUris = pages
                                    }
                                }, enabled = index > 0) { Text("←") }
                                TextButton(onClick = {
                                    if (index < selectedUris.lastIndex) {
                                        val pages = selectedUris.toMutableList()
                                        java.util.Collections.swap(pages, index, index + 1)
                                        selectedUris = pages
                                    }
                                }, enabled = index < selectedUris.lastIndex) { Text("→") }
                            }
                            TextButton(onClick = {
                                selectedUris = selectedUris.filterIndexed { i, _ -> i != index }
                            }) { Text("Remove") }
                        }
                    }
                }
                OutlinedTextField(
                    value = documentName,
                    onValueChange = { documentName = it.take(60) },
                    label = { Text("PDF filename") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("PDF image appearance", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(ScanAppearance.ORIGINAL to "Original", ScanAppearance.ENHANCED to "Enhance", ScanAppearance.BLACK_AND_WHITE to "B&W").forEach { (mode, label) ->
                        if (scanAppearance == mode) Button(onClick = { scanAppearance = mode }, modifier = Modifier.weight(1f)) { Text(label) }
                        else OutlinedButton(onClick = { scanAppearance = mode }, modifier = Modifier.weight(1f)) { Text(label) }
                    }
                }
                Text("Original preserves the scanned image. Enhanced applies gentle contrast; B&W removes color. Choose Original for faint handwriting.", style = MaterialTheme.typography.bodySmall)
                Text("Compression preset")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("Small" to 50f, "Normal" to 75f, "High" to 95f).forEach { (label, quality) ->
                        OutlinedButton(onClick = { compressionQuality = quality }, modifier = Modifier.weight(1f)) {
                            Text(label)
                        }
                    }
                }
                Text("JPEG image quality: ${compressionQuality.toInt()}%")
                Slider(
                    value = compressionQuality,
                    onValueChange = { compressionQuality = it },
                    valueRange = 40f..95f
                )
                Button(
                    onClick = {
                        isProcessing = true
                        val safeName = documentName.trim().removeSuffix(".pdf")
                            .replace(Regex("[^a-zA-Z0-9 _-]"), "_")
                            .trim().ifEmpty { "Document" }
                        val outputFile = File(context.cacheDir, "${safeName}_${System.currentTimeMillis()}.pdf")

                        coroutineScope.launch {
                            val result = PdfProcessor.convertImagesToPdf(
                                context = context,
                                imageUris = selectedUris,
                                outputFile = outputFile,
                                compressionQuality = compressionQuality.toInt(),
                                appearance = scanAppearance,
                                watermarkText = if (isProUser) null else "Generated by DocUtils Free"
                            )

                            isProcessing = false

                            result.onSuccess { file ->
                                val saved = withContext(Dispatchers.IO) { runCatching { DocumentStorage.savePdf(context, file) } }
                                if (saved.isFailure) {
                                    feedback = "PDF created but automatic save failed"
                                    return@onSuccess
                                }
                                latestSavedPdfUri = saved.getOrNull()
                                latestPdf = file
                                recentFiles = runCatching { DocumentStorage.recentFiles(context) }.getOrDefault(recentFiles)
                                feedback = "Saved to Documents/DocUtils Pro • ${selectedUris.size} page(s) • ${file.length() / 1024} KB"
                                // Show interstitial ad before triggering share/view intent
                                onShowAd {
                                    val uri = latestSavedPdfUri ?: pdfUri(file)
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/pdf")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching { context.startActivity(Intent.createChooser(intent, "Open PDF")) }
                                        .onFailure { Toast.makeText(context, "No PDF viewer found. The PDF is saved in Documents/DocUtils Pro.", Toast.LENGTH_LONG).show() }
                                }
                            }.onFailure {
                                Toast.makeText(context, "Failed to create PDF", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isProcessing) "Converting..." else "Convert to Compressed PDF")
                }
            }
            if (selectedUris.isNotEmpty()) {
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(selectedUris))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Images"))
                }, modifier = Modifier.fillMaxWidth()) { Text("Share Images") }
            }
            HorizontalDivider()
            Text("My Files & Folders", style = MaterialTheme.typography.titleLarge)
            Text("Automatically saved to Documents/DocUtils Pro and Pictures/DocUtils Pro", style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "PDFs", "Images").forEach { category ->
                    val count = when (category) {
                        "PDFs" -> recentFiles.count { it.isPdf }
                        "Images" -> recentFiles.count { !it.isPdf }
                        else -> recentFiles.size
                    }
                    if (libraryFilter == category) Button(
                        onClick = { libraryFilter = category },
                        modifier = Modifier.weight(1f)
                    ) { Text("$category ($count)", maxLines = 1) }
                    else OutlinedButton(
                        onClick = { libraryFilter = category },
                        modifier = Modifier.weight(1f)
                    ) { Text("$category ($count)", maxLines = 1) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { newFolderDialog = true }, modifier = Modifier.weight(1f)) { Text("+ Folder") }
                OutlinedButton(onClick = { sortNewestFirst = !sortNewestFirst }, modifier = Modifier.weight(1f)) {
                    Text(if (sortNewestFirst) "Newest" else "Oldest")
                }
                OutlinedButton(onClick = {
                    recentFiles = runCatching { DocumentStorage.recentFiles(context, 500) }.getOrDefault(recentFiles)
                }, modifier = Modifier.weight(1f)) { Text("Refresh") }
            }
            OutlinedTextField(
                value = librarySearch,
                onValueChange = { librarySearch = it },
                label = { Text("Search saved files") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Folders (${folderNames.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selectedFolder == null) Button(onClick = { selectedFolder = null }) { Text("All (${recentFiles.size})") }
                else OutlinedButton(onClick = { selectedFolder = null }) { Text("All (${recentFiles.size})") }
                folderNames.sorted().take(3).forEach { name ->
                    TextButton(onClick = { selectedFolder = if (selectedFolder == name) null else name }) {
                        Text("${if (selectedFolder == name) "✓ " else ""}$name (${recentFiles.count { folderOf(it.uri) == name }})", maxLines = 1)
                    }
                }
            }
            folderNames.sorted().drop(3).forEach { name ->
                TextButton(onClick = { selectedFolder = if (selectedFolder == name) null else name }) {
                    Text("${if (selectedFolder == name) "✓ " else ""}$name (${recentFiles.count { folderOf(it.uri) == name }})")
                }
            }
            val visibleFiles = recentFiles.filter { item ->
                (libraryFilter == "All" || (libraryFilter == "PDFs" && item.isPdf) ||
                    (libraryFilter == "Images" && !item.isPdf)) &&
                    (selectedFolder == null || folderOf(item.uri) == selectedFolder) &&
                    item.name.contains(librarySearch.trim(), ignoreCase = true)
            }.let { if (sortNewestFirst) it else it.reversed() }
            if (visibleFiles.isEmpty()) {
                Text("No matching documents. Scan or convert a document to add one.")
            } else {
                visibleFiles.forEach { item ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val thumb = remember(item.uri) {
                                runCatching {
                                    if (item.isPdf) {
                                        context.contentResolver.openFileDescriptor(item.uri, "r")?.use { descriptor ->
                                            PdfRenderer(descriptor).use { renderer ->
                                                if (renderer.pageCount > 0) {
                                                    renderer.openPage(0).use { page ->
                                                        val width = 110
                                                        val height = (page.height.toFloat() * width / page.width).toInt().coerceIn(80, 160)
                                                        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                                        bitmap.eraseColor(android.graphics.Color.WHITE)
                                                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                        bitmap.asImageBitmap()
                                                    }
                                                } else null
                                            }
                                        }
                                    } else {
                                        context.contentResolver.openInputStream(item.uri)?.use {
                                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 12 })?.asImageBitmap()
                                        }
                                    }
                                }.getOrNull()
                            }
                            if (thumb != null) Image(
                                bitmap = thumb,
                                contentDescription = "Preview of ${item.name}",
                                modifier = Modifier.size(width = 58.dp, height = 76.dp).clickable { openSaved(item) },
                                contentScale = ContentScale.Fit
                            ) else Text(if (item.isPdf) "PDF" else "IMG", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(if (item.isPdf) "PDF document" else "Scanned image", style = MaterialTheme.typography.bodySmall)
                                Text(if (item.isPdf) "PDF" else "IMAGE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                val folder = folderOf(item.uri)
                                if (folder.isNotEmpty()) Text("Folder: $folder", style = MaterialTheme.typography.labelSmall)
                            }
                            Box {
                                TextButton(onClick = { fileMenuUri = item.uri.toString() }) { Text("⋮") }
                                DropdownMenu(
                                    expanded = fileMenuUri == item.uri.toString(),
                                    onDismissRequest = { fileMenuUri = null }
                                ) {
                                    DropdownMenuItem(text = { Text("Open") }, onClick = {
                                        fileMenuUri = null
                                        openSaved(item)
                                    })
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                        fileMenuUri = null
                                        renameTarget = item
                                        renameDraft = item.name.substringBeforeLast(".")
                                    })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                        fileMenuUri = null
                                        deleteTarget = item
                                    })
                                    DropdownMenuItem(text = { Text("Move to folder") }, onClick = {
                                        fileMenuUri = null
                                        moveTarget = item
                                    })
                                    DropdownMenuItem(text = { Text("Share") }, onClick = {
                                        fileMenuUri = null
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = if (item.isPdf) "application/pdf" else "image/jpeg"
                                            putExtra(Intent.EXTRA_STREAM, item.uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Share file"))
                                    })
                                }
                            }
                        }
                    }
                }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            latestPdf?.let { file ->
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, latestSavedPdfUri ?: pdfUri(file))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share PDF"))
                    }
                ) { Text("Share PDF") }
            }
        }
    }
}
