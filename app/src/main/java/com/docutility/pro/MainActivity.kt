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
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var latestPdf by remember { mutableStateOf<File?>(null) }
    var latestSavedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var recentFiles by remember { mutableStateOf(runCatching { DocumentStorage.recentFiles(context) }.getOrDefault(emptyList())) }
    fun openSaved(item: DocumentStorage.SavedItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, if (item.isPdf) "application/pdf" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Open document")) }
            .onFailure { Toast.makeText(context, "No compatible viewer found", Toast.LENGTH_LONG).show() }
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
            recentFiles = runCatching { DocumentStorage.recentFiles(context) }.getOrDefault(recentFiles)
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
            if (recentFiles.isNotEmpty()) {
                HorizontalDivider()
                Text("Recent Files", style = MaterialTheme.typography.titleMedium)
                recentFiles.forEach { item ->
                    OutlinedButton(
                        onClick = { openSaved(item) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text((if (item.isPdf) "PDF • " else "Image • ") + item.name, maxLines = 1)
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
