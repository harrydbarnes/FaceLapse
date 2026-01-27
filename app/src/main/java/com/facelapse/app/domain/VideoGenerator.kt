package com.facelapse.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.facelapse.app.domain.model.Photo
import com.facelapse.app.domain.model.Project
import com.google.common.collect.ImmutableList
import dagger.hilt.android.qualifiers.ApplicationContext
import com.waynejo.androidndkgif.GifEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class VideoGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader
) {

    private data class CropParams(
        val centerX: Float,
        val centerY: Float,
        val scale: Float,
        val originalWidth: Int,
        val originalHeight: Int
    )

    private suspend fun calculateSmoothedCropParams(
        photos: List<Photo>,
        targetWidth: Int,
        targetHeight: Int,
        faceScale: Float
    ): List<CropParams?> {
        val rawParams = photos.map { photo ->
            val dims = imageLoader.getDimensions(Uri.parse(photo.originalUri)) ?: return@map null
            val (w, h) = dims

            // Base scale to fill screen
            val minScale = kotlin.math.max(targetWidth.toFloat() / w, targetHeight.toFloat() / h)
            var scale = minScale

            // Zoom effect (Face Normalization)
            if (photo.faceWidth != null && photo.faceWidth > 0) {
                val targetFaceWidth = targetWidth * faceScale
                val faceScaleFactor = targetFaceWidth / photo.faceWidth
                scale = kotlin.math.max(minScale, faceScaleFactor)
            }

            var centerX = w / 2f
            var centerY = h / 2f

            if (photo.faceX != null && photo.faceWidth != null) {
                val fh = photo.faceHeight ?: photo.faceWidth ?: 0f
                centerX = photo.faceX + photo.faceWidth / 2f
                centerY = (photo.faceY ?: 0f) + fh / 2f
            }

            CropParams(centerX, centerY, scale, w, h)
        }

        val smoothed = mutableListOf<CropParams?>()
        var current: CropParams? = null

        for (raw in rawParams) {
            if (raw == null) {
                smoothed.add(null)
                continue
            }

            if (current == null) {
                current = raw
            } else {
                val smoothX = (raw.centerX * 0.2f) + (current!!.centerX * 0.8f)
                val smoothY = (raw.centerY * 0.2f) + (current!!.centerY * 0.8f)
                val smoothScale = (raw.scale * 0.2f) + (current!!.scale * 0.8f)
                current = CropParams(smoothX, smoothY, smoothScale, raw.originalWidth, raw.originalHeight)
            }
            smoothed.add(current)
        }
        return smoothed
    }

    suspend fun generateVideo(
        photos: List<Photo>,
        outputFile: File,
        isDateOverlayEnabled: Boolean,
        dateFontSize: Int,
        dateFormat: String,
        targetWidth: Int = 1080,
        targetHeight: Int = 1920,
        fps: Float = 10f,
        audioUri: Uri? = null,
        faceScale: Float = Project.DEFAULT_FACE_SCALE
    ): Boolean = withContext(Dispatchers.IO) {
        if (outputFile.exists()) outputFile.delete()

        val dateFormatter = if (isDateOverlayEnabled) {
             try {
                 DateTimeFormatter.ofPattern(dateFormat, java.util.Locale.getDefault())
             } catch (e: Exception) {
                 Log.w(TAG, "Invalid date format provided: $dateFormat", e)
                 null
             }
        } else null

        val datePaint = if (isDateOverlayEnabled) {
            Paint().apply {
                color = Color.WHITE
                textSize = if (dateFontSize > 0) dateFontSize.toFloat() else 60f
                isAntiAlias = true
                setShadowLayer(5f, 0f, 0f, Color.BLACK)
                textAlign = Paint.Align.CENTER
                alpha = 255
            }
        } else null

        val dateBitmapCache = mutableMapOf<String, Bitmap>()
        val dateOverlayCache = mutableMapOf<String, OverlayEffect>()

        try {
            val cropParamsList = calculateSmoothedCropParams(photos, targetWidth, targetHeight, faceScale)

            val editedMediaItems = photos.zip(cropParamsList).mapNotNull { (photo, params) ->
                currentCoroutineContext().ensureActive()

                if (params == null) return@mapNotNull null

                val w = params.originalWidth
                val h = params.originalHeight
                val scale = params.scale
                val centerX = params.centerX
                val centerY = params.centerY

                val cropW = targetWidth / scale
                val cropH = targetHeight / scale

                val left = (centerX - cropW / 2).coerceIn(0f, w - cropW)
                val top = (centerY - cropH / 2).coerceIn(0f, h - cropH)
                val right = left + cropW
                val bottom = top + cropH

                val ndcLeft = (left / w) * 2 - 1
                val ndcRight = (right / w) * 2 - 1
                val ndcTop = 1 - (top / h) * 2
                val ndcBottom = 1 - (bottom / h) * 2

                // Create Crop effect using NDC coordinates
                val cropEffect = Crop(ndcLeft, ndcRight, ndcBottom, ndcTop)

                val effects = mutableListOf<Effect>(cropEffect)
                effects.add(Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT))

                if (isDateOverlayEnabled && dateFormatter != null && datePaint != null) {
                    val dateString = try {
                        dateFormatter.format(photo.timestamp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to format timestamp: ${photo.timestamp}", e)
                        "Error"
                    }
                    val overlayEffect = dateOverlayCache.getOrPut(dateString) {
                        val dateBitmap = createDateBitmap(dateString, datePaint, targetWidth, targetHeight)
                        dateBitmapCache[dateString] = dateBitmap
                        val overlay = BitmapOverlay.createStaticBitmapOverlay(dateBitmap)
                        OverlayEffect(ImmutableList.copyOf(listOf(overlay)))
                    }
                    effects.add(overlayEffect)
                }

                val durationMs = (1000f / fps).toLong()

                EditedMediaItem.Builder(MediaItem.fromUri(photo.originalUri))
                    .setDurationUs(durationMs * 1000)
                    .setEffects(Effects(listOf(), effects.toList()))
                    .setFrameRate(fps.toInt())
                    .build()
            }

            if (editedMediaItems.isEmpty()) {
                return@withContext false
            }

            val videoSequence = EditedMediaItemSequence(editedMediaItems)
            val sequences = mutableListOf(videoSequence)

            if (audioUri != null) {
                val totalDurationUs = editedMediaItems.sumOf { it.durationUs }
                val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(audioUri))
                    .setRemoveVideo(true)
                    .setDurationUs(totalDurationUs)
                    .build()
                sequences.add(EditedMediaItemSequence(ImmutableList.of(audioItem)))
            }

            val composition = Composition.Builder(sequences).build()

            // Execute Transformer on Main thread as it requires a Looper
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                continuation.resume(true)
                            }
                            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                                Log.e(TAG, "Export error", exception)
                                continuation.resume(false)
                            }
                        })
                        .build()

                    transformer.start(composition, outputFile.path)

                    continuation.invokeOnCancellation {
                        transformer.cancel()
                    }
                }
            }
        } finally {
            // Ensure all cached bitmaps are recycled to prevent memory leaks.
            dateBitmapCache.values.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    suspend fun generateGif(
        photos: List<Photo>,
        outputFile: File,
        isDateOverlayEnabled: Boolean,
        dateFontSize: Int,
        dateFormat: String,
        targetWidth: Int = 480,
        targetHeight: Int = 854,
        fps: Float = 10f,
        faceScale: Float = Project.DEFAULT_FACE_SCALE
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var frameBuffer: FrameBuffer? = null
            try {
                if (outputFile.exists()) outputFile.delete()

                frameBuffer = FrameBuffer(targetWidth, targetHeight)

                val delayMs = (1000f / fps).toInt()
                val encoder = GifEncoder()
                encoder.init(
                    targetWidth,
                    targetHeight,
                    outputFile.absolutePath,
                    GifEncoder.EncodingType.ENCODING_TYPE_NORMAL_LOW_MEMORY
                )

                try {
                    val datePaint = if (isDateOverlayEnabled) {
                        Paint().apply {
                            color = Color.WHITE
                            textSize = if (dateFontSize > 0) dateFontSize.toFloat() else 60f
                            isAntiAlias = true
                            setShadowLayer(5f, 0f, 0f, Color.BLACK)
                            textAlign = Paint.Align.CENTER
                            alpha = 255
                        }
                    } else null

                    val dateFormatter = if (isDateOverlayEnabled) {
                        try {
                            DateTimeFormatter.ofPattern(dateFormat, java.util.Locale.getDefault())
                        } catch (e: Exception) {
                            Log.w(TAG, "Invalid date format for GIF: $dateFormat", e)
                            null
                        }
                    } else null

                    val cropParamsList = calculateSmoothedCropParams(photos, targetWidth, targetHeight, faceScale)

                    for ((photo, params) in photos.zip(cropParamsList)) {
                        currentCoroutineContext().ensureActive()

                        if (params != null) {
                            val successLoad = loadBitmapToCanvas(
                                Uri.parse(photo.originalUri),
                                frameBuffer,
                                params
                            )

                            if (successLoad) {
                                if (isDateOverlayEnabled && datePaint != null) {
                                    drawDateOverlay(frameBuffer.bitmap, photo.timestamp, datePaint, dateFormatter)
                                }
                                encoder.encodeFrame(frameBuffer.bitmap, delayMs)
                            }
                        }
                    }
                } finally {
                    encoder.close()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error generating GIF", e)
                false
            } finally {
                frameBuffer?.recycle()
            }
        }
    }

    private fun createDateBitmap(
        dateString: String,
        paint: Paint,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val x = width / 2f
        // Dynamic Y position: 5% padding from bottom + text height/descent adjustment
        val y = height - (height * 0.05f) - paint.descent()
        canvas.drawText(dateString, x, y, paint)
        return bitmap
    }

    private suspend fun loadBitmapToCanvas(
        uri: Uri,
        frameBuffer: FrameBuffer,
        cropParams: CropParams
    ): Boolean {
        return try {
             frameBuffer.bitmap.eraseColor(Color.TRANSPARENT)
             val targetW = frameBuffer.bitmap.width
             val targetH = frameBuffer.bitmap.height
             val result = imageLoader.loadOptimizedBitmap(uri, targetW, targetH) ?: return false
             val rotatedBitmap = result.bitmap
             val sampleSize = result.sampleSize

             // params are in Original coordinates.
             // We need to map them to Loaded coordinates (Original / sampleSize)
             // and then to Target scale.

             // The cropParams.scale is Target / Original.
             // We want sScale = Target / Loaded = Target / (Original / SampleSize) = cropParams.scale * sampleSize
             val sScale = cropParams.scale * sampleSize

             // Center in Original coords
             val centerX = cropParams.centerX
             val centerY = cropParams.centerY

             // Center in Loaded coords
             val loadedCenterX = centerX / sampleSize
             val loadedCenterY = centerY / sampleSize

             // Center in ScaledLoaded coords (pixels in the virtual scaled image)
             val scaledCenterX = loadedCenterX * sScale
             val scaledCenterY = loadedCenterY * sScale

             // We want the scaledCenterX to be at targetW/2
             // The crop rectangle's top-left in ScaledLoaded coords:
             // cropX = scaledCenterX - targetW/2
             var cropX = (scaledCenterX - targetW / 2)
             var cropY = (scaledCenterY - targetH / 2)

             // Dimensions of the full scaled image
             val scaledW = rotatedBitmap.width * sScale
             val scaledH = rotatedBitmap.height * sScale

             cropX = cropX.coerceIn(0f, scaledW - targetW)
             cropY = cropY.coerceIn(0f, scaledH - targetH)

             frameBuffer.matrix.reset()
             frameBuffer.matrix.setScale(sScale, sScale)
             frameBuffer.matrix.postTranslate(-cropX, -cropY)
             frameBuffer.canvas.drawBitmap(rotatedBitmap, frameBuffer.matrix, frameBuffer.paint)
             rotatedBitmap.recycle()
             true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap", e)
            false
        }
    }

    private fun drawDateOverlay(
        bitmap: Bitmap,
        timestamp: LocalDateTime,
        paint: Paint,
        dateFormatter: DateTimeFormatter?
    ) {
        val canvas = Canvas(bitmap)
        val dateString = try {
            dateFormatter?.format(timestamp) ?: "Error"
        } catch (e: Exception) {
             Log.e(TAG, "Failed to format timestamp for GIF overlay: $timestamp", e)
             "Error"
        }
        val x = bitmap.width / 2f
        val y = bitmap.height - 100f
        canvas.drawText(dateString, x, y, paint)
    }

    private class FrameBuffer(val width: Int, val height: Int) {
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            ?: throw IllegalStateException("Failed to create bitmap")
        val canvas: Canvas = Canvas(bitmap)
        val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val matrix: Matrix = Matrix()

        fun recycle() {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    companion object {
        private val TAG = VideoGenerator::class.java.simpleName
    }
}
