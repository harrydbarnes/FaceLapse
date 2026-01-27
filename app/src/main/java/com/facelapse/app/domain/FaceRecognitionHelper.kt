package com.facelapse.app.domain

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

class FaceRecognitionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val mutex = Mutex()

    companion object {
        private const val MODEL_FILENAME = "mobilefacenet.tflite"
        private const val INPUT_SIZE = 112
        private const val OUTPUT_SIZE = 192
        // Threshold is used by consumer, but defined here as per requirements
        const val THRESHOLD = 0.6f
    }

    suspend fun init() {
        mutex.withLock {
            if (interpreter != null) return
            try {
                val options = Interpreter.Options()
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                } else {
                    options.setNumThreads(NUM_CPU_THREADS)
                }

                val modelFile = FileUtil.loadMappedFile(context, MODEL_FILENAME)
                interpreter = Interpreter(modelFile, options)
            } catch (e: Exception) {
                android.util.Log.e("FaceRecognitionHelper", "Error initializing TFLite", e)
            }
        }
    }

    suspend fun getFaceEmbedding(bitmap: Bitmap, face: Face): FloatArray? {
         if (interpreter == null) {
             init()
             if (interpreter == null) return null
         }

         return mutex.withLock {
             var cropped: Bitmap? = null
             try {
                val box = face.boundingBox
                val left = box.left.coerceAtLeast(0)
                val top = box.top.coerceAtLeast(0)
                val width = box.width().coerceAtMost(bitmap.width - left)
                val height = box.height().coerceAtMost(bitmap.height - top)

                if (width <= 0 || height <= 0) return@withLock null

                cropped = Bitmap.createBitmap(bitmap, left, top, width, height)

                val imageProcessor = ImageProcessor.Builder()
                    .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                    .add(NormalizeOp(128.0f, 128.0f))
                    .build()

                var tensorImage = TensorImage.fromBitmap(cropped)
                tensorImage = imageProcessor.process(tensorImage)

                val outputArray = Array(1) { FloatArray(OUTPUT_SIZE) }

                interpreter?.run(tensorImage.buffer, outputArray)

                outputArray[0]
             } catch (e: Exception) {
                 android.util.Log.e("FaceRecognitionHelper", "Error getting embedding", e)
                 null
             } finally {
                 if (cropped != null && !cropped.isRecycled) {
                     cropped.recycle()
                 }
             }
         }
    }

    fun calculateCosineSimilarity(embed1: FloatArray, embed2: FloatArray): Float {
        if (embed1.size != embed2.size) return 0f
        var dot = 0f
        var mag1 = 0f
        var mag2 = 0f
        for (i in embed1.indices) {
            dot += embed1[i] * embed2[i]
            mag1 += embed1[i] * embed1[i]
            mag2 += embed2[i] * embed2[i]
        }
        val mag = (sqrt(mag1) * sqrt(mag2))
        return if (mag > 0) dot / mag else 0f
    }

    suspend fun close() {
        mutex.withLock {
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
        }
    }
}
