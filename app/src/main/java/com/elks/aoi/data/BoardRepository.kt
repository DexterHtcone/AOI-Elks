package com.elks.aoi.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BoardRepository(context: Context) {
    private val dao = AppDatabase.getInstance(context).boardDao()
    private val filesDir = context.filesDir

    fun getAllBoards(): Flow<List<BoardEntity>> = dao.getAllBoards()

    suspend fun getBoard(id: Long): BoardEntity? = dao.getBoardById(id)

    suspend fun addBoard(name: String, description: String, bitmap: Bitmap): Long {
        return withContext(Dispatchers.IO) {
            val id = System.currentTimeMillis()
            val refFile = File(filesDir, "ref_$id.jpg")
            val thumbFile = File(filesDir, "thumb_$id.jpg")

            FileOutputStream(refFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            // Thumbnail
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
            FileOutputStream(File(board.referenceImagePath)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            val thumb = Bitmap.createScaledBitmap(bitmap, 200, 200, true)
            FileOutputStream(File(board.thumbnailPath)).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            dao.update(board.copy(updatedAt = System.currentTimeMillis()))
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
