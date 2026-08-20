package com.capstone.chatapp.ui.pairing

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.capstone.chatapp.data.security.QrCodec
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Shows this device's own pairing QR (always available) and, when [peerUid] is set, that
 * contact's verified state + safety number. "Scan" opens a live camera preview that decodes QR
 * frames directly with ZXing against CameraX's analysis output — no ML Kit dependency needed for
 * something this narrow (one barcode format, one screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    peerUid: String?,
    peerName: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: PairingViewModel = viewModel(factory = viewModelFactory {
        initializer { PairingViewModel(context.applicationContext as Application, peerUid, peerName) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (granted) vm.setScanning(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.peerUid != null) "Verify Contact" else "My QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.scanning) {
                Text("Point the camera at their QR code", style = MaterialTheme.typography.bodyLarge)
                QrScannerView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    onDecoded = vm::onQrScanned,
                )
                OutlinedButton(onClick = { vm.setScanning(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            } else {
                if (state.peerUid != null) {
                    Text(state.peerName ?: state.peerUid.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    if (state.peerVerified) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(" Verified", color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Text("Not verified yet", color = MaterialTheme.colorScheme.error)
                    }
                    state.safetyNumber?.let { number ->
                        Text("Safety number", style = MaterialTheme.typography.titleMedium)
                        Text(number, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "If this matches on both phones, your connection to this contact " +
                                "hasn't been tampered with.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(
                    onClick = {
                        if (hasCameraPermission) vm.setScanning(true)
                        else permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.peerUid != null) "Scan Their QR to Verify" else "Scan a QR Code")
                }

                Text("My QR code", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Let someone scan this to start an encrypted, verified chat with you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.myQrText.isNotBlank()) {
                    val bitmap = remember(state.myQrText) { QrCodec.toBitmap(state.myQrText) }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "My pairing QR code",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QrScannerView(modifier: Modifier, onDecoded: (String) -> Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { MultiFormatReader() }
    val onDecodedState = rememberUpdatedState(onDecoded)
    var handled by remember { mutableStateOf(false) }
    // bindToLifecycle ties the camera to the whole host Activity's lifecycle, not to this
    // composable's presence — without an explicit unbind on dispose, the camera (and its
    // preview) would keep running in the background after the user leaves scan mode.
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProviderRef.value?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProviderRef.value = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy ->
                    if (!handled) {
                        decodeQr(imageProxy, reader)?.let { text ->
                            if (onDecodedState.value(text)) handled = true
                        }
                    }
                    imageProxy.close()
                }
                cameraProvider.unbindAll()
                runCatching {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private fun decodeQr(imageProxy: ImageProxy, reader: MultiFormatReader): String? {
    val plane = imageProxy.planes.getOrNull(0) ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val source = PlanarYUVLuminanceSource(
        data,
        plane.rowStride,
        imageProxy.height,
        0,
        0,
        imageProxy.width,
        imageProxy.height,
        false,
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decode(bitmap).text
    } catch (e: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}
