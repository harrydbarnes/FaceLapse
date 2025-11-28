package com.facelapse.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["projectId"])]
)
data class PhotoEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val originalUri: String,
    val timestamp: Long,
    val sortOrder: Int,

    // Face Detection Data
    val isProcessed: Boolean = false,
    val faceX: Float? = null,
    val faceY: Float? = null,
    val faceWidth: Float? = null,
    val faceHeight: Float? = null,
    val rotation: Int = 0
)
