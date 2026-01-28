package com.facelapse.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.facelapse.app.domain.model.Project
import java.util.UUID

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isDateOverlayEnabled: Boolean = true,
    val fps: Float = 10f,
    val exportAsGif: Boolean = false,
    val faceScale: Float = Project.DEFAULT_FACE_SCALE,
    val aspectRatio: String = Project.DEFAULT_ASPECT_RATIO,
    val targetEmbedding: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProjectEntity

        if (id != other.id) return false
        if (name != other.name) return false
        if (createdAt != other.createdAt) return false
        if (isDateOverlayEnabled != other.isDateOverlayEnabled) return false
        if (fps != other.fps) return false
        if (exportAsGif != other.exportAsGif) return false
        if (faceScale != other.faceScale) return false
        if (aspectRatio != other.aspectRatio) return false
        if (targetEmbedding != null) {
            if (other.targetEmbedding == null) return false
            if (!targetEmbedding.contentEquals(other.targetEmbedding)) return false
        } else if (other.targetEmbedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + isDateOverlayEnabled.hashCode()
        result = 31 * result + fps.hashCode()
        result = 31 * result + exportAsGif.hashCode()
        result = 31 * result + faceScale.hashCode()
        result = 31 * result + aspectRatio.hashCode()
        result = 31 * result + (targetEmbedding?.contentHashCode() ?: 0)
        return result
    }
}
