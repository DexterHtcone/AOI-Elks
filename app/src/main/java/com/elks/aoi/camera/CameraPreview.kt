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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.elks.aoi.vision.BoardContourDetector
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

data class DefectRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

enum class GuidanceHint {
    OK, TOO_DARK, TOO_BRIGHT, MOVE_CLOSER, MOVE_FARTHER, CENTER_BOARD, HOLD_STEADY
}

data class FrameAnalysis(
    val hint: GuidanceHint,
    val brightness: Float,
    val fillRatio: Float,
    val centered: Boolean,
    /** Normalized [0..1] polygon of detected board outline (image space). */
    val boardContour: List<BoardContourDetector.NormPoint> = emptyList(),
    /** width/height of upright analysis frame (for FILL_CENTER mapping). */
    val contourImageAspect: Float = 1f
)

fun guidanceFrameRect(
    screenW: Float, screenH: Float, aspectRatio: Float = 1.6f
): androidx.compose.ui.geometry.Rect {
    val aspect = aspectRatio.coerceIn(0.4f, 3.5f)
    val maxW = screenW * 0.92f
    val maxH = screenH * 0.58f
    var frameW = maxW
    var frameH = frameW / aspect
    if (frameH > maxH) { frameH = maxH; frameW = frameH * aspect }
    val left = (screenW - frameW) / 2f
    val top = (screenH - frameH) / 2f - screenH * 0.03f
    return androidx.compose.ui.geometry.Rect(left, top, left + frameW, top + frameH)
}

@Composable
fun CameraCaptureScreen(
    onCapture: (Bitmap) -> Unit,
    onBack: () -> Unit,
    titleText: String = "",
    defectRegions: List<DefectRegion> = emptyList(),
    statusText: String? = null,
    statusColor: Color = Color.White,
    autoCaptureWhenReady: Boolean = false,
    frameAspectRatio: Float = 1.6f,
    autoTorch: Boolean = true,
    showGuidance: Boolean = true,
    /** When true, OpenCV detects PCB outline and draws a live contour frame (for calibration). */
    detectBoardContour: Boolean = false
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
    val okStreak = remember { AtomicInteger(0) }
    val lastAutoCaptureMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val detectContourFlag = remember { AtomicBoolean(detectBoardContour) }
    detectContourFlag.set(detectBoardContour)

    LaunchedEffect(camera, autoTorch) {
        camera?.let { cam ->
            try {
                if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(autoTorch)
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(guidance.hint, autoCaptureWhenReady, isCapturing) {
        if (!autoCaptureWhenReady || isCapturing) return@LaunchedEffect
        if (guidance.hint == GuidanceHint.OK) {
            val streak = okStreak.incrementAndGet()
            if (streak >= 3) {
                val now = System.currentTimeMillis()
                if (now - lastAutoCaptureMs.get() > 4500L) {
                    lastAutoCaptureMs.set(now)
                    okStreak.set(0)
                    kotlinx.coroutines.delay(350)
                    if (guidance.hint == GuidanceHint.OK && !isCapturing) {
                        triggerCapture(imageCapture, scope, onCapture) { isCapturing = it }
                    }
                }
            }
        } else okStreak.set(0)
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
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                    analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        val now = System.currentTimeMillis()
                        if (now - lastAnalyzeMs.get() < 200 || analyzing.get()) {
                            imageProxy.close(); return@setAnalyzer
                        }
                        lastAnalyzeMs.set(now)
                        analyzing.set(true)
                        try {
                            val base = analyzeFrame(imageProxy)
                            if (detectContourFlag.get()) {
                                val det = BoardContourDetector.detect(imageProxy)
                                guidance = base.copy(
                                    boardContour = det.points,
                                    contourImageAspect = det.imageAspect
                                )
                            } else {
                                guidance = base
                            }
                        } catch (_: Exception) {
                        } finally {
                            analyzing.set(false)
                            imageProxy.close()
                        }
                    }
                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, capture, analysis
                        )
                        camera?.let { cam ->
                            if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(autoTorch)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        if (showGuidance) {
            GuidanceOverlay(guidance = guidance, aspectRatio = frameAspectRatio)
        }

        if (detectBoardContour && guidance.boardContour.size >= 3) {
            BoardContourOverlay(
                points = guidance.boardContour,
                imageAspect = guidance.contourImageAspect
            )
        }

        if (defectRegions.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val frame = guidanceFrameRect(size.width, size.height, frameAspectRatio)
                clipRect(frame.left, frame.top, frame.right, frame.bottom) {
                    defectRegions.forEach { r ->
                        val l = frame.left + r.left * frame.width
                        val t = frame.top + r.top * frame.height
                        val w = (r.right - r.left) * frame.width
                        val h = (r.bottom - r.top) * frame.height
                        drawRect(Color.Red.copy(alpha = 0.28f), Offset(l, t), Size(w, h))
                        drawRect(Color.Red.copy(alpha = 0.95f), Offset(l, t), Size(w, h), style = Stroke(width = 5f))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Назад", tint = Color.White)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                .padding(top = 56.dp).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (titleText.isNotEmpty()) {
                Text(
                    titleText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            if (statusText != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    statusText, color = statusColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            if (detectBoardContour) {
                Spacer(modifier = Modifier.height(6.dp))
                val contourHint = if (guidance.boardContour.size >= 4)
                    "Контур платы найден — выровняйте и снимите"
                else
                    "Ищем контур платы…"
                Text(
                    contourHint,
                    color = if (guidance.boardContour.size >= 4) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        val hintLabel = when (guidance.hint) {
            GuidanceHint.OK -> if (autoCaptureWhenReady) "✓ Держите ровно — съёмка автоматически" else "✓ Готово — можно снимать"
            GuidanceHint.TOO_DARK -> "Слишком темно — подождите авто-подстройку"
            GuidanceHint.TOO_BRIGHT -> "Слишком ярко — чуть отдалите или смените угол"
            GuidanceHint.MOVE_CLOSER -> "Приблизьте: плата должна заполнить рамку"
            GuidanceHint.MOVE_FARTHER -> "Отдалите: плата выходит за рамку"
            GuidanceHint.CENTER_BOARD -> "Сместите плату в центр рамки"
            GuidanceHint.HOLD_STEADY -> "Держите телефон ровно"
        }
        val hintColor = when (guidance.hint) {
            GuidanceHint.OK -> Color(0xFF4CAF50)
            GuidanceHint.TOO_DARK, GuidanceHint.TOO_BRIGHT -> Color(0xFFFFC107)
            else -> Color(0xFFFF9800)
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = 110.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = hintLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.background(hintColor.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)) {
            FloatingActionButton(
                onClick = {
                    if (isCapturing) return@FloatingActionButton
                    okStreak.set(0)
                    lastAutoCaptureMs.set(System.currentTimeMillis())
                    triggerCapture(imageCapture, scope, onCapture) { isCapturing = it }
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                if (isCapturing) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                else Icon(Icons.Default.Camera, contentDescription = "Снять", modifier = Modifier.size(36.dp), tint = Color.White)
            }
        }
    }
}

/**
 * Map normalized upright-image point to PreviewView FILL_CENTER screen coordinates.
 */
fun mapContourToScreen(
    nx: Float, ny: Float,
    imageAspect: Float,
    viewW: Float, viewH: Float
): Offset {
    val aspect = imageAspect.coerceIn(0.2f, 5f)
    val viewAspect = viewW / viewH.coerceAtLeast(1f)
    val (dispW, dispH, offX, offY) = if (aspect > viewAspect) {
        val w = viewW
        val h = w / aspect
        val oy = (viewH - h) / 2f
        arrayOf(w, h, 0f, oy)
    } else {
        val h = viewH
        val w = h * aspect
        val ox = (viewW - w) / 2f
        arrayOf(w, h, ox, 0f)
    }
    return Offset(offX + nx * dispW, offY + ny * dispH)
}

@Composable
fun BoardContourOverlay(
    points: List<BoardContourDetector.NormPoint>,
    imageAspect: Float = 1f
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (points.size < 3) return@Canvas
        val mapped = points.map { mapContourToScreen(it.x, it.y, imageAspect, size.width, size.height) }
        val path = Path()
        path.moveTo(mapped[0].x, mapped[0].y)
        for (i in 1 until mapped.size) path.lineTo(mapped[i].x, mapped[i].y)
        path.close()
        drawPath(path, Color(0xFF00E676).copy(alpha = 0.12f))
        drawPath(path, Color(0xFF00E676), style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        mapped.forEach { o ->
            drawCircle(Color.White, radius = 8f, center = o)
            drawCircle(Color(0xFF00E676), radius = 5f, center = o)
        }
    }
}

@Composable
fun GuidanceOverlay(guidance: FrameAnalysis, aspectRatio: Float = 1.6f) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val frame = guidanceFrameRect(size.width, size.height, aspectRatio)
        val left = frame.left; val top = frame.top; val frameW = frame.width; val frameH = frame.height
        val frameColor = when (guidance.hint) {
            GuidanceHint.OK -> Color(0xFF4CAF50)
            GuidanceHint.TOO_DARK, GuidanceHint.TOO_BRIGHT -> Color(0xFFFFC107)
            else -> Color(0xFFFF9800)
        }
        drawRect(Color.Black.copy(alpha = 0.35f), Offset.Zero, Size(size.width, top))
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, top + frameH), Size(size.width, size.height - top - frameH))
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, top), Size(left, frameH))
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(left + frameW, top), Size(size.width - left - frameW, frameH))
        drawRect(color = frameColor, topLeft = Offset(left, top), size = Size(frameW, frameH), style = Stroke(width = 5f))
        val c = 48f; val sw = 10f
        drawLine(Color.White, Offset(left, top), Offset(left + c, top), sw)
        drawLine(Color.White, Offset(left, top), Offset(left, top + c), sw)
        drawLine(Color.White, Offset(left + frameW - c, top), Offset(left + frameW, top), sw)
        drawLine(Color.White, Offset(left + frameW, top), Offset(left + frameW, top + c), sw)
        drawLine(Color.White, Offset(left, top + frameH - c), Offset(left, top + frameH), sw)
        drawLine(Color.White, Offset(left, top + frameH), Offset(left + c, top + frameH), sw)
        drawLine(Color.White, Offset(left + frameW, top + frameH - c), Offset(left + frameW, top + frameH), sw)
        drawLine(Color.White, Offset(left + frameW - c, top + frameH), Offset(left + frameW, top + frameH), sw)
        val cx = size.width / 2; val cy = top + frameH / 2
        when (guidance.hint) {
            GuidanceHint.MOVE_CLOSER -> {
                drawArrow(Offset(cx, top + 30f), 0f, Color.White)
                drawArrow(Offset(cx, top + frameH - 30f), 180f, Color.White)
                drawArrow(Offset(left + 40f, cy), 90f, Color.White)
                drawArrow(Offset(left + frameW - 40f, cy), 270f, Color.White)
            }
            GuidanceHint.MOVE_FARTHER -> {
                drawArrow(Offset(cx, top + 30f), 180f, Color.White)
                drawArrow(Offset(cx, top + frameH - 30f), 0f, Color.White)
                drawArrow(Offset(left + 40f, cy), 270f, Color.White)
                drawArrow(Offset(left + frameW - 40f, cy), 90f, Color.White)
            }
            GuidanceHint.CENTER_BOARD -> {
                drawLine(Color.White.copy(alpha = 0.7f), Offset(cx - 30f, cy), Offset(cx + 30f, cy), 3f)
                drawLine(Color.White.copy(alpha = 0.7f), Offset(cx, cy - 30f), Offset(cx, cy + 30f), 3f)
            }
            else -> {}
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(tip: Offset, rotationDeg: Float, color: Color) {
    rotate(rotationDeg, tip) {
        val path = Path().apply {
            moveTo(tip.x, tip.y); lineTo(tip.x - 22f, tip.y - 35f); lineTo(tip.x + 22f, tip.y - 35f); close()
        }
        drawPath(path, color)
    }
}

fun analyzeFrame(image: ImageProxy): FrameAnalysis {
    val yPlane = image.planes[0]
    val yBuffer = yPlane.buffer
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val width = image.width; val height = image.height
    val step = 8
    var sum = 0L; var count = 0
    val cx0 = width / 4; val cx1 = width * 3 / 4; val cy0 = height / 4; val cy1 = height * 3 / 4
    var centerSum = 0L; var centerCount = 0; var borderSum = 0L; var borderCount = 0
    var edgeSum = 0L; var edgeCount = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val idx = y * rowStride + x * pixelStride
            if (idx < yBuffer.capacity()) {
                val v = yBuffer.get(idx).toInt() and 0xFF
                sum += v; count++
                if (x in cx0 until cx1 && y in cy0 until cy1) { centerSum += v; centerCount++ }
                else { borderSum += v; borderCount++ }
                if (x + step < width) {
                    val idx2 = y * rowStride + (x + step) * pixelStride
                    if (idx2 < yBuffer.capacity()) {
                        edgeSum += abs(v - (yBuffer.get(idx2).toInt() and 0xFF)); edgeCount++
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
    val contrast = abs(centerBright - borderBright)
    val fillRatio = min(1f, edgeDensity * 4f + contrast * 2f)
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
            scope.launch { onCapture(bitmap); setCapturing(false) }
        }
        override fun onError(exception: ImageCaptureException) {
            exception.printStackTrace(); setCapturing(false)
        }
    })
}

fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    if (image.format == ImageFormat.JPEG) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer
    val ySize = yBuffer.remaining(); val uSize = uBuffer.remaining(); val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize); vBuffer.get(nv21, ySize, vSize); uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
    val jpegBytes = out.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun playDefectSound() {
    try {
        ToneGenerator(AudioManager.STREAM_ALARM, 90).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
    } catch (_: Exception) {}
}

fun playRetrySound() {
    try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
    } catch (_: Exception) {}
}
