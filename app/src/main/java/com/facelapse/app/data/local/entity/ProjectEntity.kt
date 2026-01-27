package com.facelapse.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isDateOverlayEnabled: Boolean = true,
    val fps: Float = 10f,
    val exportAsGif: Boolean = false,
    val faceScale: Float = 0.4f,
    val aspectRatio: String = "9:16"
)
