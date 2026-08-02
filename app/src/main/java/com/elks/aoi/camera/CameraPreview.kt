package com.elks.aoi.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DefectRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

enum class GuidanceHint {
    OK,
    TOO_DARK,
    TOO_BRIGHT,
    MOVE_CLOSER,
    MOVE_FARTHER,
    CENTER_BOARD,
    HOLD_STEADY
}

data class FrameAnalysis(
    val hint: GuidanceHint,
    val brightness: Float,      // 0..1
    val fillRatio: Float,       // estimated board fill 0..1
    val centered: Boolean
)

@Composable
fun CameraCaptureScreen(
    onCapture: (Bitmap) -> Unit,
    onBack: () -> Unit,
    titleText: String = "",
    defectRegions: List<DefectRegion> = emptyList(),
    statusText: String? = null,
    statusColor: Color = Color.White,
    autoCaptureWhenReady: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var guidance by remember { mutableStateOf(FrameAnalysis(GuidanceHint.HOLD_STEADY, 0.5f, 0.5f, false)) }

    val analyzing = remember { AtomicBoolean(false) }
    val lastAnalyzeMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // Always enable torch when camera is ready
    LaunchedEffect(camera) {
        camera?.let { cam ->
            try {
                if (cam.cameraInfo.hasFlashUnit()) {
                    cam.cameraControl.enableTorch(true)
                }
            } catch (_: Exception) {}
        }
    }

    // Auto-capture when positioning is OK
    LaunchedEffect(guidance.hint, autoCaptureWhenReady) {
        if (autoCaptureWhenReady && guidance.hint == GuidanceHint.OK && !isCapturing) {
            kotlinx.coroutines.delay(600)
            if (guidance.hint == GuidanceHint.OK && !isCapturing) {
                triggerCapture(imageCapture, scope, onCapture) { isCapturing = it }
            }
        }
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

                    // Continuous analysis for guidance
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()

                    analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        val now = System.currentTimeMillis()
                        if (now - lastAnalyzeMs.get() < 250 || analyzing.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        lastAnalyzeMs.set(now)
                        analyzing.set(true)
                        try {
                            val result = analyzeFrame(imageProxy)
                            // Update on main via atomic + recomposition trigger
                            guidance = result
                        } catch (_: Exception) {
                        } finally {
                            analyzing.set(false)
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                            analysis
                        )
                        // Auto torch
                        camera?.let { cam ->
                            if (cam.cameraInfo.hasFlashUnit()) {
                                cam.cameraControl.enableTorch(true)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Graphical guidance overlay
        GuidanceOverlay(guidance = guidance)

        // Defect red rectangles
        if (defectRegions.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                defectRegions.forEach { r ->
                    drawRect(
                        color = Color.Red.copy(alpha = 0.75f),
                        topLeft = Offset(r.left * size.width, r.top * size.height),
                        size = Size(
                            (r.right - r.left) * size.width,
                            (r.bottom - r.top) * size.height
                        ),
                        style = Stroke(width = 7f)
                    )
                }
            }
        }

        // Top bar — only close button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = "Назад", tint = Color.White)
            }
        }

        // Title + status
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (titleText.isNotEmpty()) {
                Text(
                    titleText,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            if (statusText != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    statusText,
                    color = statusColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // Hint banner at bottom (above capture button)
        val hintLabel = when (guidance.hint) {
            GuidanceHint.OK -> "✓ Готово — можно снимать"
            GuidanceHint.TOO_DARK -> "Слишком темно — подождите авто-подстройку"
            GuidanceHint.TOO_BRIGHT -> "Слишком ярко — чуть отдалите или смените угол"
            GuidanceHint.MOVE_CLOSER -> "Приблизьте телефон к плате"
            GuidanceHint.MOVE_FARTHER -> "Отдалите телефон от платы"
            GuidanceHint.CENTER_BOARD -> "Сместите плату в центр рамки"
            GuidanceHint.HOLD_STEADY -> "Держите телефон ровно"
        }
        val hintColor = when (guidance.hint) {
            GuidanceHint.OK -> Color(0xFF4CAF50)
            GuidanceHint.TOO_DARK, GuidanceHint.TOO_BRIGHT -> Color(0xFFFFC107)
            else -> Color(0xFFFF9800)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 110.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = hintLabel,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(hintColor.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        // Capture button — only active when OK (or always for calibration)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
        ) {
            val ready = guidance.hint == GuidanceHint.OK || !autoCaptureWhenReady
            FloatingActionButton(
                onClick = {
                    if (isCapturing) return@FloatingActionButton
                    triggerCapture(imageCapture, scope, onCapture) { isCapturing = it }
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                containerColor = if (ready) MaterialTheme.colorScheme.primary
                else Color.Gray
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Camera,
                        contentDescription = "Снять",
                        modifier = Modifier.size(36.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun GuidanceOverlay(guidance: FrameAnalysis) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val frameW = size.width * 0.88f
        val frameH = size.height * 0.52f
        val left = (size.width - frameW) / 2
        val top = (size.height - frameH) / 2 - 30f

        val frameColor = when (guidance.hint) {
            GuidanceHint.OK -> Color(0xFF4CAF50)
            GuidanceHint.TOO_DARK, GuidanceHint.TOO_BRIGHT -> Color(0xFFFFC107)
            else -> Color(0xFFFF9800)
        }

        // Dim outside frame
        drawRect(Color.Black.copy(alpha = 0.32f), Offset.Zero, Size(size.width, top))
        drawRect(
            Color.Black.copy(alpha = 0.32f),
            Offset(0f, top + frameH),
            Size(size.width, size.height - top - frameH)
        )
        drawRect(Color.Black.copy(alpha = 0.32f), Offset(0f, top), Size(left, frameH))
        drawRect(
            Color.Black.copy(alpha = 0.32f),
            Offset(left + frameW, top),
            Size(size.width - left - frameW, frameH)
        )

        // Frame
        drawRect(
            color = frameColor,
            topLeft = Offset(left, top),
            size = Size(frameW, frameH),
            style = Stroke(width = 5f)
        )

        // Corners
        val c = 48f
        val sw = 10f
        drawLine(Color.White, Offset(left, top), Offset(left + c, top), sw)
        drawLine(Color.White, Offset(left, top), Offset(left, top + c), sw)
        drawLine(Color.White, Offset(left + frameW - c, top), Offset(left + frameW, top), sw)
        drawLine(Color.White, Offset(left + frameW, top), Offset(left + frameW, top + c), sw)
        drawLine(Color.White, Offset(left, top + frameH - c), Offset(left, top + frameH), sw)
        drawLine(Color.White, Offset(left, top + frameH), Offset(left + c, top + frameH), sw)
        drawLine(Color.White, Offset(left + frameW, top + frameH - c), Offset(left + frameW, top + frameH), sw)
        drawLine(Color.White, Offset(left + frameW - c, top + frameH), Offset(left + frameW, top + frameH), sw)

        val cx = size.width / 2
        val cy = top + frameH / 2

        // Directional arrows based on hint
        when (guidance.hint) {
            GuidanceHint.MOVE_CLOSER -> {
                // Four arrows pointing inward
                drawArrow(Offset(cx, top + 30f), 0f, Color.White)      // down
                drawArrow(Offset(cx, top + frameH - 30f), 180f, Color.White) // up
                drawArrow(Offset(left + 40f, cy), 90f, Color.White)     // right
                drawArrow(Offset(left + frameW - 40f, cy), 270f, Color.White) // left
            }
            GuidanceHint.MOVE_FARTHER -> {
                // Four arrows pointing outward
                drawArrow(Offset(cx, top + 30f), 180f, Color.White)
                drawArrow(Offset(cx, top + frameH - 30f), 0f, Color.White)
                drawArrow(Offset(left + 40f, cy), 270f, Color.White)
                drawArrow(Offset(left + frameW - 40f, cy), 90f, Color.White)
            }
            GuidanceHint.CENTER_BOARD -> {
                // Crosshair center
                drawLine(Color.White.copy(alpha = 0.7f), Offset(cx - 30f, cy), Offset(cx + 30f, cy), 3f)
                drawLine(Color.White.copy(alpha = 0.7f), Offset(cx, cy - 30f), Offset(cx, cy + 30f), 3f)
            }
            else -> {}
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(
    tip: Offset,
    rotationDeg: Float,
    color: Color
) {
    rotate(rotationDeg, tip) {
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - 22f, tip.y - 35f)
            lineTo(tip.x + 22f, tip.y - 35f)
            close()
        }
        drawPath(path, color)
    }
}

/**
 * Analyze YUV frame for brightness and approximate fill/centering.
 * Heuristic without OpenCV — good enough for operator guidance.
 */
fun analyzeFrame(image: ImageProxy): FrameAnalysis {
    val yPlane = image.planes[0]
    val yBuffer = yPlane.buffer
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val width = image.width
    val height = image.height

    // Sample every Nth pixel for speed
    val step = 8
    var sum = 0L
    var count = 0
    var edgeSum = 0L
    var edgeCount = 0

    // Center region vs border
    val cx0 = width / 4
    val cx1 = width * 3 / 4
    val cy0 = height / 4
    val cy1 = height * 3 / 4
    var centerSum = 0L
    var centerCount = 0
    var borderSum = 0L
    var borderCount = 0

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val idx = y * rowStride + x * pixelStride
            if (idx < yBuffer.capacity()) {
                val v = yBuffer.get(idx).toInt() and 0xFF
                sum += v
                count++

                val inCenter = x in cx0 until cx1 && y in cy0 until cy1
                if (inCenter) {
                    centerSum += v
                    centerCount++
                } else {
                    borderSum += v
                    borderCount++
                }

                // Simple horizontal gradient as edge proxy
                if (x + step < width) {
                    val idx2 = y * rowStride + (x + step) * pixelStride
                    if (idx2 < yBuffer.capacity()) {
                        val v2 = yBuffer.get(idx2).toInt() and 0xFF
                        edgeSum += abs(v - v2)
                        edgeCount++
                    }
                }
            }
            x += step
        }
        y += step
    }

    val brightness = if (count > 0) (sum.toFloat() / count) / 255f else 0.5f
    val centerBright = if (centerCount > 0) (centerSum.toFloat() / centerCount) / 255f else 0.5f
    val borderBright = if (borderCount > 0) (borderSum.toFloat() / borderCount) / 255f else 0.5f
    val edgeDensity = if (edgeCount > 0) (edgeSum.toFloat() / edgeCount) / 255f else 0f

    // fillRatio heuristic: more edges + contrast between center and border ≈ board present
    val contrast = abs(centerBright - borderBright)
    val fillRatio = min(1f, edgeDensity * 4f + contrast * 2f)

    // Centering: if center is significantly different from border, board is likely centered
    val centered = contrast > 0.05f && edgeDensity > 0.04f

    val hint = when {
        brightness < 0.18f -> GuidanceHint.TOO_DARK
        brightness > 0.88f -> GuidanceHint.TOO_BRIGHT
        fillRatio < 0.12f -> GuidanceHint.MOVE_CLOSER
        fillRatio > 0.85f && edgeDensity > 0.15f -> GuidanceHint.MOVE_FARTHER
        !centered -> GuidanceHint.CENTER_BOARD
        fillRatio in 0.2f..0.75f && brightness in 0.22f..0.82f -> GuidanceHint.OK
        else -> GuidanceHint.HOLD_STEADY
    }

    return FrameAnalysis(hint, brightness, fillRatio, centered)
}

private fun triggerCapture(
    imageCapture: ImageCapture?,
    scope: kotlinx.coroutines.CoroutineScope,
    onCapture: (Bitmap) -> Unit,
    setCapturing: (Boolean) -> Unit
) {
    val capture = imageCapture ?: return
    setCapturing(true)
    val executor = Executors.newSingleThreadExecutor()
    capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val bitmap = imageProxyToBitmap(image)
            image.close()
            scope.launch {
                onCapture(bitmap)
                setCapturing(false)
            }
        }

        override fun onError(exception: ImageCaptureException) {
            exception.printStackTrace()
            setCapturing(false)
        }
    })
}

fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    // Prefer JPEG if available
    if (image.format == ImageFormat.JPEG) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // YUV → NV21 → JPEG → Bitmap
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
    val jpegBytes = out.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

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

            for (y in y0 until min(y0 + cellH, refScaled.height)) {
                for (x in x0 until min(x0 + cellW, refScaled.width)) {
                    val p1 = refScaled.getPixel(x, y)
                    val p2 = capScaled.getPixel(x, y)
                    val l1 = 0.299 * ((p1 shr 16) and 0xFF) + 0.587 * ((p1 shr 8) and 0xFF) + 0.114 * (p1 and 0xFF)
                    val l2 = 0.299 * ((p2 shr 16) and 0xFF) + 0.587 * ((p2 shr 8) and 0xFF) + 0.114 * (p2 and 0xFF)
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
        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 90)
        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
    } catch (_: Exception) {}
}
