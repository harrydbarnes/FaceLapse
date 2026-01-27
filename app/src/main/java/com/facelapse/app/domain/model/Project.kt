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
    val aspectRatio: String
) {
    companion object {
        const val DEFAULT_FACE_SCALE = 0.4f
        const val DEFAULT_ASPECT_RATIO = "9:16"
        val SUPPORTED_ASPECT_RATIOS = listOf("9:16", "1:1", "16:9", "4:5")

        fun getDimensionsForAspectRatio(ratio: String): Pair<Int, Int> {
            return when (ratio) {
                "16:9" -> 1920 to 1080
                "1:1" -> 1080 to 1080
                "4:5" -> 1080 to 1350
                else -> 1080 to 1920 // Default 9:16
            }
        }
    }
}
