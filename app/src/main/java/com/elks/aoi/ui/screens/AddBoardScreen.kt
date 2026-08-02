package com.elks.aoi.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elks.aoi.camera.CameraCaptureScreen
import com.elks.aoi.data.BoardRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBoardScreen(
    repository: BoardRepository,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    var step by remember { mutableStateOf(0) } // 0=camera, 1=name
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isFlashOn by remember { mutableStateOf(false) }
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

    when (step) {
        0 -> {
            CameraCaptureScreen(
                isFlashOn = isFlashOn,
                onFlashToggle = { isFlashOn = !isFlashOn },
                onCapture = { bmp ->
                    capturedBitmap = bmp
                    step = 1
                },
                onBack = onBack,
                guidanceText = "Сфотографируйте ИСПРАВНУЮ плату (эталон)\nВключите фонарик при плохом свете"
            )
        }

        1 -> {
            val bmp = capturedBitmap
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Новая плата") },
                        navigationIcon = {
                            IconButton(onClick = { step = 0 }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentScale = ContentScale.Crop
                        )
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Название платы *") },
                        placeholder = { Text("например: Элекс242 ОД2503") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Описание (необязательно)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = {
                            if (name.isBlank() || bmp == null || isSaving) return@Button
                            isSaving = true
                            scope.launch {
                                repository.addBoard(name.trim(), description.trim(), bmp)
                                isSaving = false
                                onSaved()
                            }
                        },
                        enabled = name.isNotBlank() && !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Сохранить в каталог", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
