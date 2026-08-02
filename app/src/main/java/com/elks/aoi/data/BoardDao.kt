package com.elks.aoi.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BoardDao {
    @Query("SELECT * FROM boards ORDER BY updatedAt DESC")
    fun getAllBoards(): Flow<List<BoardEntity>>

    @Query("SELECT * FROM boards WHERE id = :id")
    suspend fun getBoardById(id: Long): BoardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(board: BoardEntity): Long

    @Update
    suspend fun update(board: BoardEntity)

    @Delete
    suspend fun delete(board: BoardEntity)

    @Query("DELETE FROM boards WHERE id = :id")
    suspend fun deleteById(id: Long)
}
