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
    val targetEmbedding: String? = null
) {
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
