package com.facelapse.app.ui.project

import com.facelapse.app.data.local.entity.PhotoEntity
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

fun findMatchingFace(faces: List<Face>, photo: PhotoEntity): Face? {
    val epsilon = 1.0f
    return faces.find { face ->
        val box = face.boundingBox
        (photo.faceX?.let { abs(it - box.left.toFloat()) < epsilon } ?: false) &&
        (photo.faceY?.let { abs(it - box.top.toFloat()) < epsilon } ?: false) &&
        (photo.faceWidth?.let { abs(it - box.width().toFloat()) < epsilon } ?: false) &&
        (photo.faceHeight?.let { abs(it - box.height().toFloat()) < epsilon } ?: false)
    }
}
