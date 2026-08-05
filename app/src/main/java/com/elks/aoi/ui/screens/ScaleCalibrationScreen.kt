package com.elks.aoi.ui.screens

import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.elks.aoi.settings.AppSettings
import com.elks.aoi.vision.RulerScaleDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

private enum class CalibMode { Overview, AutoCamera, ManualCamera }

/**
 * Калибровка масштаба мм/px.
 * 1) Авто: CV ищет деления линейки; пользователь подтверждает «Сохранить».
 * 2) Вручную: две точки + известное расстояние.
 * 3) Ручной ввод мм/px.
 *
 * Экран камеры НЕ закрывается сам — только по «Сохранить» / «Назад» / «Отмена».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleCalibrationScreen(
    settings: AppSettings,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    var mode by remember { mutableStateOf(CalibMode.Overview) }
    var point1 by remember { mutableStateOf<Offset?>(null) }
    var point2 by remember { mutableStateOf<Offset?>(null) }
    var distanceMmText by remember { mutableStateOf("10") }
    var manualMmPxText by remember {
        mutableStateOf(
            if (settings.mmPerPixel > 0f) String.format("%.5f", settings.mmPerPixel) else ""
        )
    }
    var message by remember { mutableStateOf<String?>(null) }

    when (mode) {
        CalibMode.Overview -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Калибровка мм/px") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Назад"
                                )
                            }
                        }
                    )
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Масштаб используется для отображения размеров зон брака в миллиметрах.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "Текущее значение", fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (settings.mmPerPixel > 0f) {
                                    String.format("%.5f мм/px", settings.mmPerPixel)
                                } else {
                                    "не задано"
                                },
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (settings.mmPerPixel > 0f) {
                                val pxPerMm = 1f / settings.mmPerPixel
                                Text(
                                    text = String.format("≈ %.1f px на 1 мм", pxPerMm),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    Text(text = "Авто по линейке", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        text = "Положите миллиметровую линейку в кадр. " +
                            "CV найдёт деления (1 мм). Сохраните результат кнопкой — окно само не закроется.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Button(
                        onClick = {
                            if (!hasCameraPermission) {
                                onRequestPermission()
                            } else {
                                message = null
                                mode = CalibMode.AutoCamera
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Straighten, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Авто-калибровка (камера)")
                    }

                    HorizontalDivider()

                    Text(text = "Вручную: 2 точки", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        text = "Отметьте два деления на линейке и укажите расстояние между ними в мм.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Button(
                        onClick = {
                            if (!hasCameraPermission) {
                                onRequestPermission()
                            } else {
                                point1 = null
                                point2 = null
                                message = null
                                mode = CalibMode.ManualCamera
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Straighten, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть камеру (2 точки)")
                    }

                    HorizontalDivider()

                    Text(text = "Ручной ввод", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    OutlinedTextField(
                        value = manualMmPxText,
                        onValueChange = { manualMmPxText = it.replace(',', '.') },
                        label = { Text("мм на пиксель") },
                        placeholder = { Text("0.025") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val v = manualMmPxText.toFloatOrNull()
                            if (v != null && v > 0f && v < 10f) {
                                settings.setMmPerPixel(v)
                                message = "Сохранено: ${String.format("%.5f", v)} мм/px"
                            } else {
                                message = "Введите положительное число (например 0.025)"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сохранить")
                    }

                    OutlinedButton(
                        onClick = {
                            settings.setMmPerPixel(0f)
                            manualMmPxText = ""
                            message = "Масштаб сброшен"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сбросить масштаб")
                    }

                    message?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        CalibMode.AutoCamera -> {
            AutoRulerCalibrationCamera(
                onBack = { mode = CalibMode.Overview },
                onSave = { result ->
                    settings.setMmPerPixel(result.mmPerPixel)
                    manualMmPxText = String.format("%.5f", result.mmPerPixel)
                    message = result.message.ifBlank {
                        String.format("Сохранено: %.5f мм/px", result.mmPerPixel)
                    }
                    mode = CalibMode.Overview
                }
            )
        }

        CalibMode.ManualCamera -> {
            TwoPointCalibrationCamera(
                point1 = point1,
                point2 = point2,
                distanceMmText = distanceMmText,
                onDistanceChange = { distanceMmText = it.replace(',', '.') },
                onTap = { offset ->
                    when {
                        point1 == null -> point1 = offset
                        point2 == null -> point2 = offset
                        else -> {
                            point1 = offset
                            point2 = null
                        }
                    }
                },
                onBack = {
                    mode = CalibMode.Overview
                    point1 = null
                    point2 = null
                },
                onApply = {
                    val p1 = point1
                    val p2 = point2
                    val mm = distanceMmText.toFloatOrNull()
                    if (p1 != null && p2 != null && mm != null && mm > 0f) {
                        val px = hypot(
                            (p2.x - p1.x).toDouble(),
                            (p2.y - p1.y).toDouble()
                        ).toFloat()
                        if (px > 5f) {
                            val mmPx = mm / px
                            settings.setMmPerPixel(mmPx)
                            manualMmPxText = String.format("%.5f", mmPx)
                            message = String.format(
                                "Сохранено: %.5f мм/px (%.0f px ≈ %.1f мм)",
                                mmPx, px, mm
                            )
                            mode = CalibMode.Overview
                            point1 = null
                            point2 = null
                        } else {
                            message = "Точки слишком близко — выберите дальше"
                        }
                    } else {
                        message = "Отметьте 2 точки и введите расстояние в мм"
                    }
                }
            )
        }
    }
}

/**
 * Live CV ruler detection. Does NOT auto-dismiss.
 * User must press «Сохранить» when a stable reading is shown.
 */
@Composable
private fun AutoRulerCalibrationCamera(
    onBack: () -> Unit,
    onSave: (RulerScaleDetector.Result) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf("Наведите на линейку…") }
    var lastConf by remember { mutableStateOf(0f) }
    var pending by remember { mutableStateOf<RulerScaleDetector.Result?>(null) }
    val busy = remember { AtomicBoolean(false) }
    val stableHits = remember { intArrayOf(0) }
    val lastMmPx = remember { floatArrayOf(0f) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        if (busy.getAndSet(true)) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        try {
                            val result = RulerScaleDetector.detect(imageProxy)
                            if (result != null &&
                                result.confidence >= 0.50f &&
                                result.tickCount >= 8 &&
                                result.mmPerPixel > 0.0005f &&
                                result.mmPerPixel < 1f
                            ) {
                                val same = absRel(lastMmPx[0], result.mmPerPixel) < 0.10f
                                if (same) stableHits[0]++ else {
                                    stableHits[0] = 1
                                    lastMmPx[0] = result.mmPerPixel
                                }
                                val hits = stableHits[0]
                                mainHandler.post {
                                    status = if (hits >= 3) {
                                        result.message + " — можно сохранить"
                                    } else {
                                        result.message + " (стабилизация $hits/3)"
                                    }
                                    lastConf = result.confidence
                                    if (hits >= 3) pending = result
                                }
                            } else {
                                stableHits[0] = 0
                                mainHandler.post {
                                    status = "Ищу деления линейки… держите ровно"
                                    lastConf = 0f
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            busy.set(false)
                            imageProxy.close()
                        }
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "Авто по линейке",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = status,
                        color = if (pending != null) Color(0xFF69F0AE) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Положите линейку горизонтально, заполните ~1/2–2/3 кадра. " +
                            "Когда появится зелёный статус — нажмите «Сохранить».",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Отмена", color = Color.White)
                }
                Button(
                    onClick = {
                        val r = pending
                        if (r != null) onSave(r)
                    },
                    enabled = pending != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Сохранить")
                }
            }
        }
    }
}

private fun absRel(a: Float, b: Float): Float {
    if (a <= 0f || b <= 0f) return 1f
    return kotlin.math.abs(a - b) / maxOf(a, b)
}

@Composable
private fun TwoPointCalibrationCamera(
    point1: Offset?,
    point2: Offset?,
    distanceMmText: String,
    onDistanceChange: (String) -> Unit,
    onTap: (Offset) -> Unit,
    onBack: () -> Unit,
    onApply: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onTap(offset) }
                }
        ) {
            val p1 = point1
            val p2 = point2
            if (p1 != null) {
                drawCircle(Color(0xFF00E676), radius = 14f, center = p1)
                drawCircle(Color.White, radius = 6f, center = p1)
            }
            if (p2 != null) {
                drawCircle(Color(0xFF00E676), radius = 14f, center = p2)
                drawCircle(Color.White, radius = 6f, center = p2)
            }
            if (p1 != null && p2 != null) {
                drawLine(
                    Color(0xFF00E676),
                    p1,
                    p2,
                    strokeWidth = 4f,
                    cap = StrokeCap.Round
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "2 точки на линейке",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp)
        ) {
            Text(
                text = when {
                    point1 == null -> "Коснитесь первого деления"
                    point2 == null -> "Коснитесь второго деления"
                    else -> "Введите расстояние между точками (мм)"
                },
                color = Color.White,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = distanceMmText,
                onValueChange = onDistanceChange,
                label = { Text("Расстояние, мм", color = Color.White.copy(alpha = 0.7f)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00E676),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f)
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Отмена", color = Color.White)
                }
                Button(
                    onClick = onApply,
                    enabled = point1 != null && point2 != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Применить")
                }
            }
        }
    }
}
