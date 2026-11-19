package com.upscaler.studio.ui.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.upscaler.studio.data.repository.BackendUrlParser
import com.upscaler.studio.data.repository.ConnectionState
import com.upscaler.studio.data.repository.UpscalerRepository
import com.upscaler.studio.ui.theme.DarkBackground
import com.upscaler.studio.ui.theme.DarkSurface
import com.upscaler.studio.ui.theme.GreenIconTint
import com.upscaler.studio.ui.theme.GreenPrimary
import com.upscaler.studio.ui.theme.ShapeCorner10
import com.upscaler.studio.ui.theme.ShapeCorner12
import com.upscaler.studio.ui.theme.StatusOfflineRed
import com.upscaler.studio.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { UpscalerRepository.instance }
    val currentConnectedHost by repository.currentBaseUrl.collectAsState()
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var pendingSwitchRaw by remember { mutableStateOf<String?>(null) }

    val scanGate = remember { AtomicBoolean(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }
    var boundCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRequestedOnce = true
        hasCameraPermission = granted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { boundCameraProvider?.unbindAll() }
            cameraExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Scan Colab QR Code", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            boundCameraProvider = cameraProvider

                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setTargetResolution(Size(1280, 720))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && !scanGate.get()) {
                                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                    barcodeScanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            for (barcode in barcodes) {
                                                val rawValue = barcode.rawValue ?: continue
                                                if (rawValue.contains("trycloudflare.com") ||
                                                    rawValue.startsWith("upscaler://connect") ||
                                                    rawValue.startsWith("http://") ||
                                                    rawValue.startsWith("https://")
                                                ) {
                                                    if (!scanGate.compareAndSet(false, true)) return@addOnSuccessListener

                                                    val parsedCandidate = BackendUrlParser.parsePairingInput(rawValue).getOrNull()
                                                    val candidateBase = parsedCandidate?.baseUrl ?: rawValue
                                                    val isConnected = repository.connectionState.value is ConnectionState.Connected
                                                    val activeHost = currentConnectedHost ?: ""

                                                    if (isConnected && activeHost.isNotBlank() && !activeHost.equals(candidateBase, ignoreCase = true)) {
                                                        pendingSwitchRaw = rawValue
                                                        return@addOnSuccessListener
                                                    }

                                                    scope.launch {
                                                        isVerifying = true
                                                        val healthRes = repository.connectAndVerify(rawValue)
                                                        isVerifying = false
                                                        if (healthRes.isSuccess) {
                                                            onConnected()
                                                        } else {
                                                            scanError = healthRes.exceptionOrNull()?.localizedMessage
                                                                ?: "Scanned backend did not respond."
                                                            scanGate.set(false)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } else {
                                    imageProxy.close()
                                }
                            }

                            runCatching {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay guide + status/error card
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.82f))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(color = GreenPrimary, modifier = Modifier.size(30.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Verifying Colab backend connection...",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    } else if (scanError != null) {
                        Text(
                            text = scanError!!,
                            color = StatusOfflineRed,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    scanError = null
                                    scanGate.set(false)
                                },
                                shape = ShapeCorner10,
                                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("Scan Again", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onBack,
                                shape = ShapeCorner10,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("Back to Paste URL", color = Color.White)
                            }
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = GreenIconTint,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Point camera at the QR code in your Colab output card",
                            color = Color.White,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                val activity = context as? Activity
                val showRationale = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
                val permanentlyDenied = permissionRequestedOnce && !showRationale

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = GreenIconTint,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (permanentlyDenied) {
                            "Camera permission was denied. Enable it in App Settings to scan the Colab QR code, or paste the URL manually on Home."
                        } else {
                            "Camera permission is required to scan the Colab pairing QR code."
                        },
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    if (permanentlyDenied) {
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            },
                            shape = ShapeCorner12,
                            colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Open App Settings", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            shape = ShapeCorner12,
                            colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("Grant Camera Permission", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (pendingSwitchRaw != null) {
            val newHost = BackendUrlParser.parsePairingInput(pendingSwitchRaw!!).getOrNull()?.baseUrl ?: pendingSwitchRaw!!
            val currentHost = currentConnectedHost ?: "Current host"
            AlertDialog(
                onDismissRequest = {
                    pendingSwitchRaw = null
                    scanGate.set(false)
                },
                title = { Text("Switch Backend Host?", color = Color.White) },
                text = {
                    Text(
                        "You are currently connected to:\n$currentHost\n\nSwitching to:\n$newHost\n\nExisting jobs will continue running on their original backend.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val raw = pendingSwitchRaw!!
                            pendingSwitchRaw = null
                            scope.launch {
                                isVerifying = true
                                val healthRes = repository.connectAndVerify(raw)
                                isVerifying = false
                                if (healthRes.isSuccess) {
                                    onConnected()
                                } else {
                                    scanError = healthRes.exceptionOrNull()?.localizedMessage
                                        ?: "Scanned backend did not respond."
                                    scanGate.set(false)
                                }
                            }
                        },
                        shape = ShapeCorner10,
                        colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                    ) {
                        Text("Switch & Connect", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            pendingSwitchRaw = null
                            scanGate.set(false)
                        },
                        shape = ShapeCorner10
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = DarkSurface
            )
        }
    }
}
