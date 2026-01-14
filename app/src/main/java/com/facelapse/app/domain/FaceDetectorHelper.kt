package com.facelapse.app.domain

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceDetectorHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader
) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    // Changed return type to FaceDetectionResult
    suspend fun detectFaces(uri: Uri): FaceDetectionResult {
        return withContext(Dispatchers.IO) {
            val bitmap = imageLoader.loadUprightBitmap(uri) ?: return@withContext FaceDetectionResult(emptyList(), 0, 0)
            val width = bitmap.width
            val height = bitmap.height
            try {
                // InputImage.fromBitmap(bitmap, 0) because the bitmap is already upright
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val task = detector.process(inputImage)
                val faces = Tasks.await(task) // Return list of faces
                FaceDetectionResult(faces, width, height)
            } catch (e: Exception) {
                android.util.Log.e("FaceDetectorHelper", "Error detecting faces", e)
                FaceDetectionResult(emptyList(), width, height)
            } finally {
                bitmap.recycle()
            }
        }
    }
}