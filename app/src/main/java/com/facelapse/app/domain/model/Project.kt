package com.facelapse.app.domain.model

import java.time.LocalDateTime

data class Project(
    val id: String,
    val name: String,
    val createdAt: LocalDateTime,
    val isDateOverlayEnabled: Boolean,
    val fps: Float,
    val exportAsGif: Boolean
)
