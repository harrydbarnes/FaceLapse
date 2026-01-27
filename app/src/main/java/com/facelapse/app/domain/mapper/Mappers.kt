package com.facelapse.app.domain.mapper

import com.facelapse.app.data.local.entity.PhotoEntity
import com.facelapse.app.data.local.entity.ProjectEntity
import com.facelapse.app.domain.model.Photo
import com.facelapse.app.domain.model.Project
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

fun ProjectEntity.toDomain(): Project {
    return Project(
        id = id,
        name = name,
        createdAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAt), ZoneId.systemDefault()),
        isDateOverlayEnabled = isDateOverlayEnabled,
        fps = fps,
        exportAsGif = exportAsGif,
        faceScale = faceScale,
        aspectRatio = aspectRatio,
        targetEmbedding = targetEmbedding
    )
}

fun Project.toEntity(): ProjectEntity {
    return ProjectEntity(
        id = id,
        name = name,
        createdAt = createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        isDateOverlayEnabled = isDateOverlayEnabled,
        fps = fps,
        exportAsGif = exportAsGif,
        faceScale = faceScale,
        aspectRatio = aspectRatio,
        targetEmbedding = targetEmbedding
    )
}

fun PhotoEntity.toDomain(): Photo {
    return Photo(
        id = id,
        projectId = projectId,
        originalUri = originalUri,
        timestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()),
        sortOrder = sortOrder,
        isProcessed = isProcessed,
        faceX = faceX,
        faceY = faceY,
        faceWidth = faceWidth,
        faceHeight = faceHeight,
        rotation = rotation
    )
}

fun Photo.toEntity(): PhotoEntity {
    return PhotoEntity(
        id = id,
        projectId = projectId,
        originalUri = originalUri,
        timestamp = timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        sortOrder = sortOrder,
        isProcessed = isProcessed,
        faceX = faceX,
        faceY = faceY,
        faceWidth = faceWidth,
        faceHeight = faceHeight,
        rotation = rotation
    )
}
