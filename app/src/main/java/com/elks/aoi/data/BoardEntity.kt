package com.elks.aoi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "boards")
data class BoardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val referenceImagePath: String,
    val thumbnailPath: String = "",
    /** JSON path of BoardOpinion (structured features / "AI opinion"). Empty if not extracted. */
    val featuresPath: String = "",
    /** Board-specific scale override (mm/px). 0 = use global AppSettings.mmPerPixel. */
    val mmPerPixel: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
