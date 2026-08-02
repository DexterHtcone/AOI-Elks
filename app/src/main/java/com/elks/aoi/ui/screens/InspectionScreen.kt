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
            statusText = "Анализ..."
            statusColor = Color.Yellow
            defectRegions = emptyList()

            scope.launch {
                val defects = withContext(Dispatchers.Default) {
                    detectDefects(referenceBitmap, captured)
                }
                defectRegions = defects
                isAnalyzing = false

                if (defects.isEmpty()) {
                    statusText = "✓ Брак не обнаружен"
                    statusColor = Color(0xFF4CAF50)
                } else {
                    statusText = "⚠ Найдено зон: ${defects.size}"
                    statusColor = Color.Red
                    playDefectSound()
                }
            }
        },
        onBack = onBack,
        titleText = board.name,
        defectRegions = defectRegions,
        statusText = statusText,
        statusColor = statusColor,
        autoCaptureWhenReady = false
    )
}
