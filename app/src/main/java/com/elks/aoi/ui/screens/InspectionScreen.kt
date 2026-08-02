package com.elks.aoi.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.elks.aoi.camera.*
import com.elks.aoi.data.BoardEntity
import com.elks.aoi.data.BoardRepository
import com.elks.aoi.vision.OpenCvInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InspectionScreen(
    board: BoardEntity,
    repository: BoardRepository,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    var defectRegions by remember { mutableStateOf<List<DefectRegion>>(emptyList()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var statusColor by remember { mutableStateOf(Color.White) }
    var isAnalyzing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val referenceBitmap = remember(board.id) {
        repository.loadBitmap(board.referenceImagePath)
    }

    // Frame aspect = эталон (width/height) — рамка на экране совпадает с формой платы
    val frameAspect = remember(referenceBitmap) {
        val bmp = referenceBitmap
        if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat()
        else 1.6f
    }

    if (!hasCameraPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Нужен доступ к камере")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequestPermission) { Text("Разрешить") }
                TextButton(onClick = onBack) { Text("Назад") }
            }
        }
        return
    }

    if (referenceBitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Эталон не найден")
                TextButton(onClick = onBack) { Text("Назад") }
            }
        }
        return
    }

    CameraCaptureScreen(
        onCapture = { captured ->
            if (isAnalyzing) return@CameraCaptureScreen
            isAnalyzing = true
            statusText = "OpenCV: выравнивание и анализ..."
            statusColor = Color.Yellow
            defectRegions = emptyList()

            scope.launch {
                val result = withContext(Dispatchers.Default) {
                    OpenCvInspector.inspect(referenceBitmap, captured)
                }
                defectRegions = result.defects
                isAnalyzing = false
                statusText = result.message
                statusColor = if (result.defects.isEmpty()) Color(0xFF4CAF50) else Color.Red
                if (result.defects.isNotEmpty()) {
                    playDefectSound()
                }
            }
        },
        onBack = onBack,
        titleText = board.name,
        defectRegions = defectRegions,
        statusText = statusText,
        statusColor = statusColor,
        autoCaptureWhenReady = true,
        frameAspectRatio = frameAspect
    )
}
