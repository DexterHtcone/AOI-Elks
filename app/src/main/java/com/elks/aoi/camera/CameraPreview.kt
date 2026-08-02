package com.elks.aoi.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs

data class DefectRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Composable
fun CameraCaptureScreen(
    isFlashOn: Boolean,
    onFlashToggle: () -> Unit,
    onCapture: (Bitmap) -> Unit,
    onBack: () -> Unit,
    guidanceText: String = "Расположите плату в рамке",
    showGuidance: Boolean = true,
    defectRegions: List<DefectRegion> = emptyList(),
    statusText: String? = null,
    statusColor: Color = Color.White
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // Torch control
    LaunchedEffect(isFlashOn, camera) {
        camera?.cameraControl?.enableTorch(isFlashOn)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    imageCapture = capture

                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Guidance overlay
        if (showGuidance) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val frameW = size.width * 0.85f
                val frameH = size.height * 0.55f
                val left = (size.width - frameW) / 2
                val top = (size.height - frameH) / 2 - 40f

                // Dim outside
                drawRect(Color.Black.copy(alpha = 0.35f), Offset.Zero, Size(size.width, top))
                drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, top + frameH), Size(size.width, size.height - top - frameH))
                drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, top), Size(left, frameH))
                drawRect(Color.Black.copy(alpha = 0.35f), Offset(left + frameW, top), Size(size.width - left - frameW, frameH))

                // Frame
                drawRect(
                    color = Color(0xFF4CAF50),
                    topLeft = Offset(left, top),
                    size = Size(frameW, frameH),
                    style = Stroke(width = 4f)
                )
                // Corner marks
                val corner = 40f
                val stroke = 8f
                // TL
                drawLine(Color.White, Offset(left, top), Offset(left + corner, top), stroke)
                drawLine(Color.White, Offset(left, top), Offset(left, top + corner), stroke)
                // TR
                drawLine(Color.White, Offset(left + frameW - corner, top), Offset(left + frameW, top), stroke)
                drawLine(Color.White, Offset(left + frameW, top), Offset(left + frameW, top + corner), stroke)
                // BL
                drawLine(Color.White, Offset(left, top + frameH - corner), Offset(left, top + frameH), stroke)
                drawLine(Color.White, Offset(left, top + frameH), Offset(left + corner, top + frameH), stroke)
                // BR
                drawLine(Color.White, Offset(left + frameW, top + frameH - corner), Offset(left + frameW, top + frameH), stroke)
                drawLine(Color.White, Offset(left + frameW - corner, top + frameH), Offset(left + frameW, top + frameH), stroke)
            }
        }

        // Defect red rectangles (normalized 0..1)
        if (defectRegions.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                defectRegions.forEach { r ->
                    drawRect(
                        color = Color.Red.copy(alpha = 0.7f),
                        topLeft = Offset(r.left * size.width, r.top * size.height),
                        size = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height),
                        style = Stroke(width = 6f)
                    )
                }
            }
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Назад", tint = Color.White)
            }

            IconButton(
                onClick = onFlashToggle,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isFlashOn) Color(0xFFFFC107) else Color.Black.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Фонарик",
                    tint = if (isFlashOn) Color.Black else Color.White
                )
            }
        }

        // Guidance text
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
                .background(Color.Black.copy(alpha = 0.55f), shape = MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(guidanceText, color = Color.White, fontSize = 14.sp)
            if (statusText != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(statusText, color = statusColor, fontSize = 16.sp)
            }
        }

        // Capture button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    if (isCapturing) return@FloatingActionButton
                    val capture = imageCapture ?: return@FloatingActionButton
                    isCapturing = true
                    val executor = Executors.newSingleThreadExecutor()
                    capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bitmap = imageProxyToBitmap(image)
                            image.close()
                            scope.launch {
                                onCapture(bitmap)
                                isCapturing = false
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            exception.printStackTrace()
                            isCapturing = false
                        }
                    })
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                } else {
                    Icon(Icons.Default.Camera, contentDescription = "Снять", modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val matrix = Matrix().apply {
        postRotate(image.imageInfo.rotationDegrees.toFloat())
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Простое зональное сравнение двух изображений.
 * Разбивает на сетку и ищет зоны с большой разницей яркости/контраста.
 * Возвращает список дефектных регионов (нормализованные 0..1).
 */
fun detectDefects(reference: Bitmap, captured: Bitmap, threshold: Float = 0.18f): List<DefectRegion> {
    val gridX = 8
    val gridY = 6
    val defects = mutableListOf<DefectRegion>()

    val refScaled = Bitmap.createScaledBitmap(reference, 320, 240, true)
    val capScaled = Bitmap.createScaledBitmap(captured, 320, 240, true)

    val cellW = refScaled.width / gridX
    val cellH = refScaled.height / gridY

    for (gy in 0 until gridY) {
        for (gx in 0 until gridX) {
            var diffSum = 0.0
            var count = 0
            val x0 = gx * cellW
            val y0 = gy * cellH

            for (y in y0 until (y0 + cellH).coerceAtMost(refScaled.height)) {
                for (x in x0 until (x0 + cellW).coerceAtMost(refScaled.width)) {
                    val p1 = refScaled.getPixel(x, y)
                    val p2 = capScaled.getPixel(x, y)
                    val r1 = (p1 shr 16) and 0xFF
                    val g1 = (p1 shr 8) and 0xFF
                    val b1 = p1 and 0xFF
                    val r2 = (p2 shr 16) and 0xFF
                    val g2 = (p2 shr 8) and 0xFF
                    val b2 = p2 and 0xFF
                    // Luma difference
                    val l1 = 0.299 * r1 + 0.587 * g1 + 0.114 * b1
                    val l2 = 0.299 * r2 + 0.587 * g2 + 0.114 * b2
                    diffSum += abs(l1 - l2)
                    count++
                }
            }

            val avgDiff = if (count > 0) (diffSum / count) / 255.0 else 0.0
            if (avgDiff > threshold) {
                defects.add(
                    DefectRegion(
                        left = gx.toFloat() / gridX,
                        top = gy.toFloat() / gridY,
                        right = (gx + 1).toFloat() / gridX,
                        bottom = (gy + 1).toFloat() / gridY
                    )
                )
            }
        }
    }

    refScaled.recycle()
    capScaled.recycle()
    return defects
}

fun playDefectSound() {
    try {
        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
        // ToneGenerator releases itself after delay is impractical; short-lived is fine
    } catch (_: Exception) {
    }
}
