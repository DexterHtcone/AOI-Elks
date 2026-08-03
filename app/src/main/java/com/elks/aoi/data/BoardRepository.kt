package com.elks.aoi.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.elks.aoi.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BoardRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(context).boardDao()
    private val filesDir = context.filesDir
    private val settings get() = AppSettings.get(appContext)

    fun getAllBoards(): Flow<List<BoardEntity>> = dao.getAllBoards()

    suspend fun getBoard(id: Long): BoardEntity? = dao.getBoardById(id)

    suspend fun addBoard(name: String, description: String, bitmap: Bitmap): Long {
        return withContext(Dispatchers.IO) {
            val id = System.currentTimeMillis()
            val usePng = settings.savePng
            val ext = if (usePng) "png" else "jpg"
            val refFile = File(filesDir, "ref_$id.$ext")
            val thumbFile = File(filesDir, "thumb_$id.jpg")

            FileOutputStream(refFile).use { out ->
                if (usePng) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                else bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            val thumb = Bitmap.createScaledBitmap(bitmap, 200, 200, true)
            FileOutputStream(thumbFile).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }

            val board = BoardEntity(
                name = name,
                description = description,
                referenceImagePath = refFile.absolutePath,
                thumbnailPath = thumbFile.absolutePath
            )
            dao.insert(board)
        }
    }

    suspend fun updateReference(boardId: Long, bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            val board = dao.getBoardById(boardId) ?: return@withContext
            val usePng = settings.savePng
            // Prefer rewriting path with correct extension if format changed
            val target = if (usePng && !board.referenceImagePath.endsWith(".png")) {
                File(filesDir, "ref_${boardId}_v${System.currentTimeMillis()}.png")
            } else if (!usePng && board.referenceImagePath.endsWith(".png")) {
                File(filesDir, "ref_${boardId}_v${System.currentTimeMillis()}.jpg")
            } else {
                File(board.referenceImagePath)
            }
            FileOutputStream(target).use { out ->
                if (usePng) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                else bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            val thumb = Bitmap.createScaledBitmap(bitmap, 200, 200, true)
            FileOutputStream(File(board.thumbnailPath)).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            dao.update(
                board.copy(
                    referenceImagePath = target.absolutePath,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun deleteBoard(board: BoardEntity) {
        withContext(Dispatchers.IO) {
            File(board.referenceImagePath).delete()
            File(board.thumbnailPath).delete()
            dao.delete(board)
        }
    }

    fun loadBitmap(path: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            null
        }
    }
}
