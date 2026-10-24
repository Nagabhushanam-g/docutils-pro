package com.docutility.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import com.docutility.pro.util.PdfProcessor
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
    var isProcessing by remember { mutableStateOf(false) }
    var latestPdf by remember { mutableStateOf<File?>(null) }
    var recentPdfs by remember {
        mutableStateOf(context.cacheDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(10) ?: emptyList())
    }
    fun openPdf(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Open PDF")) }
            .onFailure { Toast.makeText(context, "No PDF viewer found", Toast.LENGTH_LONG).show() }
    }
    var feedback by remember { mutableStateOf<String?>(null) }
    var compressionQuality by remember { mutableFloatStateOf(75f) }
    val savePdf = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val file = latestPdf
        if (destination != null && file != null) {
            runCatching {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Cannot open destination")
            }.onSuccess {
                Toast.makeText(context, "PDF saved successfully", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "Unable to save PDF", Toast.LENGTH_LONG).show()
            }
        }
    }
    fun pdfUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)


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

            Text("Convert photos to a PDF, then save or share it.")
            if (selectedUris.isNotEmpty()) {
                Text("JPEG image quality: ${compressionQuality.toInt()}%")
                Slider(
                    value = compressionQuality,
                    onValueChange = { compressionQuality = it },
                    valueRange = 40f..95f
                )
                Button(
                    onClick = {
                        isProcessing = true
                        val outputFile = File(context.cacheDir, "Document_${System.currentTimeMillis()}.pdf")

                        coroutineScope.launch {
                            val result = PdfProcessor.convertImagesToPdf(
                                context = context,
                                imageUris = selectedUris,
                                outputFile = outputFile,
                                compressionQuality = compressionQuality.toInt(),
                                watermarkText = if (isProUser) null else "Generated by DocUtils Free"
                            )

                            isProcessing = false

                            result.onSuccess { file ->
                                latestPdf = file
                                recentPdfs = (listOf(file) + recentPdfs).distinctBy { it.absolutePath }.take(10)
                                feedback = "PDF ready • ${selectedUris.size} page(s) • ${file.length() / 1024} KB"
                                // Show interstitial ad before triggering share/view intent
                                onShowAd {
                                    val uri = pdfUri(file)
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/pdf")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching { context.startActivity(Intent.createChooser(intent, "Open PDF")) }
                                        .onFailure { Toast.makeText(context, "No PDF viewer found. Use Save PDF instead.", Toast.LENGTH_LONG).show() }
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
            if (recentPdfs.isNotEmpty()) {
                HorizontalDivider()
                Text("Recent PDFs", style = MaterialTheme.typography.titleMedium)
                recentPdfs.forEach { file ->
                    if (file.exists()) {
                        OutlinedButton(
                            onClick = {
                                latestPdf = file
                                feedback = "${file.name} • ${file.length() / 1024} KB"
                                openPdf(file)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(file.name, maxLines = 1)
                        }
                    }
                }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            latestPdf?.let { file ->
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { savePdf.launch(file.name) }
                ) { Text("Save PDF to device") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, pdfUri(file))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share PDF"))
                    }
                ) { Text("Share PDF") }
            }
        }
    }
}
