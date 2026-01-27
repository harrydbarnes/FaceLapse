package com.facelapse.app.domain.model

import java.time.LocalDateTime

data class Project(
    val id: String,
    val name: String,
    val createdAt: LocalDateTime,
    val isDateOverlayEnabled: Boolean,
    val fps: Float,
    val exportAsGif: Boolean,
    val faceScale: Float,
    val aspectRatio: String,
    val targetEmbedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Project

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

    companion object {
        const val RATIO_9_16 = "9:16"
        const val RATIO_1_1 = "1:1"
        const val RATIO_16_9 = "16:9"
        const val RATIO_4_5 = "4:5"

        const val MIN_FACE_SCALE = 0.1f
        const val MAX_FACE_SCALE = 0.8f

        const val DEFAULT_FACE_SCALE = 0.4f
        const val DEFAULT_ASPECT_RATIO = RATIO_9_16
        val SUPPORTED_ASPECT_RATIOS = listOf(RATIO_9_16, RATIO_1_1, RATIO_16_9, RATIO_4_5)

        fun getDimensionsForAspectRatio(ratio: String): Pair<Int, Int> {
            return when (ratio) {
                RATIO_16_9 -> 1920 to 1080
                RATIO_1_1 -> 1080 to 1080
                RATIO_4_5 -> 1080 to 1350
                RATIO_9_16 -> 1080 to 1920
                else -> 1080 to 1920
            }
        }
    }
}
