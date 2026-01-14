package com.facelapse.app.domain

import com.google.mlkit.vision.face.Face

data class FaceDetectionResult(
    val faces: List<Face>,
    val width: Int,
    val height: Int
)
