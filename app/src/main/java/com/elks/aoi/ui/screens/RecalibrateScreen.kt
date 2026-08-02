package com.elks.aoi.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.elks.aoi.camera.CameraCaptureScreen
import com.elks.aoi.data.BoardEntity
import com.elks.aoi.data.BoardRepository
import kotlinx.coroutines.launch

@Composable
fun RecalibrateScreen(
    board: BoardEntity,
    repository: BoardRepository,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    var isFlashOn by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    CameraCaptureScreen(
        isFlashOn = isFlashOn,
        onFlashToggle = { isFlashOn = !isFlashOn },
        onCapture = { bmp ->
            if (isSaving) return@CameraCaptureScreen
            isSaving = true
            statusText = "Сохранение эталона..."
            scope.launch {
                repository.updateReference(board.id, bmp)
                isSaving = false
                onSaved()
            }
        },
        onBack = onBack,
        guidanceText = "Перекалибровка: ${board.name}\nСфотографируйте исправную плату заново",
        statusText = statusText,
        statusColor = Color.Yellow
    )
}
