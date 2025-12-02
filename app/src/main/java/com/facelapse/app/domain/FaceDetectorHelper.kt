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
    @ApplicationContext private val context: Context
) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    // Changed return type to List<Face>
    suspend fun detectFaces(uri: Uri): List<Face> {
        return withContext(Dispatchers.IO) {
            try {
                val inputImage = InputImage.fromFilePath(context, uri)
                val task = detector.process(inputImage)
                Tasks.await(task) // Return list of faces
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
}