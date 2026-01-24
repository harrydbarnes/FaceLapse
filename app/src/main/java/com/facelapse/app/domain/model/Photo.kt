package com.facelapse.app.domain.model

import java.time.LocalDateTime

data class Photo(
    val id: String,
    val projectId: String,
    val originalUri: String,
    val timestamp: LocalDateTime,
    val sortOrder: Int,
    val isProcessed: Boolean,
    val faceX: Float?,
    val faceY: Float?,
    val faceWidth: Float?,
    val faceHeight: Float?,
    val rotation: Int
)
