package com.elks.aoi.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.elks.aoi.data.BoardEntity
import com.elks.aoi.data.BoardRepository
import com.elks.aoi.ui.screens.*

enum class Screen {
    Catalog,
    AddBoard,
    BoardDetail,
    Inspection,
    Recalibrate,
    About
}

@Composable
fun AOIApp(
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { BoardRepository(context) }

    var currentScreen by remember { mutableStateOf(Screen.Catalog) }
    var selectedBoard by remember { mutableStateOf<BoardEntity?>(null) }

    when (currentScreen) {
        Screen.Catalog -> CatalogScreen(
            repository = repository,
            hasCameraPermission = hasCameraPermission,
            onRequestPermission = onRequestPermission,
            onAddBoard = { currentScreen = Screen.AddBoard },
            onBoardClick = { board ->
                selectedBoard = board
                currentScreen = Screen.BoardDetail
            },
            onAbout = { currentScreen = Screen.About }
        )

        Screen.AddBoard -> AddBoardScreen(
            repository = repository,
            hasCameraPermission = hasCameraPermission,
            onRequestPermission = onRequestPermission,
            onBack = { currentScreen = Screen.Catalog },
            onSaved = { currentScreen = Screen.Catalog }
        )

        Screen.BoardDetail -> {
            val board = selectedBoard
            if (board != null) {
                BoardDetailScreen(
                    board = board,
                    repository = repository,
                    onBack = { currentScreen = Screen.Catalog },
                    onStartInspection = {
                        currentScreen = Screen.Inspection
                    },
                    onRecalibrate = {
                        currentScreen = Screen.Recalibrate
                    },
                    onDeleted = {
                        selectedBoard = null
                        currentScreen = Screen.Catalog
                    }
                )
            } else {
                currentScreen = Screen.Catalog
            }
        }

        Screen.Inspection -> {
            val board = selectedBoard
            if (board != null) {
                InspectionScreen(
                    board = board,
                    repository = repository,
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermission = onRequestPermission,
                    onBack = { currentScreen = Screen.BoardDetail }
                )
            } else {
                currentScreen = Screen.Catalog
            }
        }

        Screen.Recalibrate -> {
            val board = selectedBoard
            if (board != null) {
                RecalibrateScreen(
                    board = board,
                    repository = repository,
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermission = onRequestPermission,
                    onBack = { currentScreen = Screen.BoardDetail },
                    onSaved = { currentScreen = Screen.BoardDetail }
                )
            } else {
                currentScreen = Screen.Catalog
            }
        }

        Screen.About -> AboutScreen(
            onBack = { currentScreen = Screen.Catalog }
        )
    }
}
