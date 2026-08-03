package com.elks.aoi.ui.screens

import androidx.camera.core.CameraSelector
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import kotlin.math.hypot

private enum class CalibMode { Overview, Camera }

/**
 * Калибровка масштаба мм/px.
 * - Ручной ввод значения
 * - Две точки на кадре + известное расстояние в мм (линейка)
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

                    HorizontalDivider()

                    Text(text = "По линейке (2 точки)", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        text = "Наведите камеру на линейку, отметьте два деления и укажите расстояние между ними в мм.",
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
                                mode = CalibMode.Camera
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Straighten, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть камеру")
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

        CalibMode.Camera -> {
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
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
                    detectTapGestures { onTap(it) }
                }
        ) {
            fun drawCross(c: Offset, color: Color) {
                val arm = 28f
                drawLine(
                    color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y),
                    strokeWidth = 4f, cap = StrokeCap.Round
                )
                drawLine(
                    color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm),
                    strokeWidth = 4f, cap = StrokeCap.Round
                )
                drawCircle(color, radius = 10f, center = c)
            }
            point1?.let { drawCross(it, Color(0xFF4CAF50)) }
            point2?.let { drawCross(it, Color(0xFF2196F3)) }
            if (point1 != null && point2 != null) {
                drawLine(
                    Color.Yellow, point1, point2,
                    strokeWidth = 3f, cap = StrokeCap.Round
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
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Отметьте 2 точки на линейке",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val status = when {
                point1 == null -> "Нажмите 1-ю точку"
                point2 == null -> "Нажмите 2-ю точку"
                else -> {
                    val px = hypot(
                        (point2.x - point1.x).toDouble(),
                        (point2.y - point1.y).toDouble()
                    )
                    String.format("Расстояние: %.0f px — введите мм", px)
                }
            }
            Text(text = status, color = Color.White, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = distanceMmText,
                onValueChange = onDistanceChange,
                label = { Text("Расстояние, мм", color = Color.White.copy(alpha = 0.8f)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                    cursorColor = Color.White,
                    focusedLabelColor = Color.White,
                    unfocusedLabelColor = Color.White.copy(alpha = 0.7f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = onApply,
                enabled = point1 != null && point2 != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Применить калибровку")
            }
        }
    }
}
