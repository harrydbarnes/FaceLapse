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
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.facelapse.app.domain.model.Photo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

    suspend fun generateVideo(
        photos: List<Photo>,
        outputFile: File,
        isDateOverlayEnabled: Boolean,
        dateFontSize: Int,
        dateFormat: String,
        targetWidth: Int = 1080,
        targetHeight: Int = 1920,
        fps: Float = 10f
    ): Boolean = withContext(Dispatchers.IO) {
        if (outputFile.exists()) outputFile.delete()

        val dateFormatter = if (isDateOverlayEnabled) {
             try {
                 DateTimeFormatter.ofPattern(dateFormat, java.util.Locale.getDefault())
             } catch (e: Exception) {
                 null
             }
        } else null

        val editedMediaItems = photos.mapNotNull { photo ->
            val dims = imageLoader.getDimensions(Uri.parse(photo.originalUri)) ?: return@mapNotNull null
            val (w, h) = dims

            // Calculate Crop
            val scale = kotlin.math.max(targetWidth.toFloat() / w, targetHeight.toFloat() / h)
            val cropW = targetWidth / scale
            val cropH = targetHeight / scale

            var centerX = w / 2f
            var centerY = h / 2f

            if (photo.faceX != null && photo.faceWidth != null) {
                val fh = photo.faceHeight ?: photo.faceWidth
                centerX = photo.faceX + photo.faceWidth / 2f
                centerY = (photo.faceY ?: 0f) + fh / 2f
            }

            val left = (centerX - cropW / 2).coerceIn(0f, w - cropW)
            val top = (centerY - cropH / 2).coerceIn(0f, h - cropH)
            val right = left + cropW
            val bottom = top + cropH

            val ndcLeft = (left / w) * 2 - 1
            val ndcRight = (right / w) * 2 - 1
            val ndcTop = 1 - (top / h) * 2
            val ndcBottom = 1 - (bottom / h) * 2

            val cropEffect = Crop(ndcLeft, ndcRight, ndcBottom, ndcTop)

            val effects = mutableListOf<Effect>(cropEffect)
            effects.add(Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT))

            if (isDateOverlayEnabled && dateFormatter != null) {
                 val dateBitmap = createDateBitmap(photo.timestamp, dateFormatter, dateFontSize, targetWidth, targetHeight)
                 val overlay = BitmapOverlay.createStaticBitmapOverlay(dateBitmap)
                 effects.add(OverlayEffect(listOf(overlay)))
            }

            val durationMs = (1000f / fps).toLong()

            EditedMediaItem.Builder(MediaItem.fromUri(photo.originalUri))
                .setDurationUs(durationMs * 1000)
                .setEffects(Effects(effects, listOf()))
                .setFrameRate(fps.toInt())
                .build()
        }

        if (editedMediaItems.isEmpty()) {
            return@withContext false
        }

        val composition = Composition.Builder(editedMediaItems).build()

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
    }

    suspend fun generateGif(
        photos: List<Photo>,
        outputFile: File,
        isDateOverlayEnabled: Boolean,
        dateFontSize: Int,
        dateFormat: String,
        targetWidth: Int = 480,
        targetHeight: Int = 854,
        fps: Float = 10f
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var frameBuffer: FrameBuffer? = null
            try {
                if (outputFile.exists()) outputFile.delete()

                frameBuffer = FrameBuffer(targetWidth, targetHeight)

                java.io.FileOutputStream(outputFile).use { fos ->
                    val encoder = AnimatedGifEncoder()
                    encoder.start(fos)
                    encoder.setFrameRate(fps)
                    encoder.setRepeat(0)
                    encoder.setQuality(10)

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
                            null
                        }
                    } else null

                    for (photo in photos) {
                        val successLoad = loadBitmapToCanvas(
                            Uri.parse(photo.originalUri),
                            frameBuffer,
                            photo.faceX,
                            photo.faceY,
                            photo.faceWidth,
                            photo.faceHeight
                        )

                        if (successLoad) {
                            if (isDateOverlayEnabled && datePaint != null) {
                                drawDateOverlay(frameBuffer.bitmap, photo.timestamp, datePaint, dateFormatter)
                            }
                            encoder.addFrame(frameBuffer.bitmap)
                        }
                    }
                    encoder.finish()
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
        timestamp: LocalDateTime,
        formatter: DateTimeFormatter,
        fontSize: Int,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = if (fontSize > 0) fontSize.toFloat() else 60f
            isAntiAlias = true
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
            textAlign = Paint.Align.CENTER
            alpha = 255
        }
        val dateString = try {
            formatter.format(timestamp)
        } catch (e: Exception) {
            "Error"
        }
        val x = width / 2f
        val y = height - 100f
        canvas.drawText(dateString, x, y, paint)
        return bitmap
    }

    private fun loadBitmapToCanvas(
        uri: Uri,
        frameBuffer: FrameBuffer,
        faceX: Float?,
        faceY: Float?,
        faceW: Float?,
        faceH: Float?
    ): Boolean {
        return try {
             frameBuffer.bitmap.eraseColor(Color.TRANSPARENT)
             val targetW = frameBuffer.bitmap.width
             val targetH = frameBuffer.bitmap.height
             val result = imageLoader.loadOptimizedBitmap(uri, targetW, targetH) ?: return false
             val rotatedBitmap = result.bitmap
             val sampleSize = result.sampleSize
             val scale = kotlin.math.max(targetW.toFloat() / rotatedBitmap.width, targetH.toFloat() / rotatedBitmap.height)
             val scaledW = (rotatedBitmap.width * scale)
             val scaledH = (rotatedBitmap.height * scale)
             var cropX = (scaledW - targetW) / 2
             var cropY = (scaledH - targetH) / 2

             if (faceX != null && faceY != null && faceW != null && faceH != null) {
                  val sFaceX = (faceX / sampleSize) * scale
                  val sFaceY = (faceY / sampleSize) * scale
                  val sFaceW = (faceW / sampleSize) * scale
                  val sFaceH = (faceH / sampleSize) * scale
                  val faceCenterX = sFaceX + (sFaceW / 2)
                  val faceCenterY = sFaceY + (sFaceH / 2)
                  cropX = (faceCenterX - targetW / 2).coerceIn(0f, scaledW - targetW)
                  cropY = (faceCenterY - targetH / 2).coerceIn(0f, scaledH - targetH)
             } else if (faceX != null && faceY != null && faceW != null) {
                  val sFaceX = (faceX / sampleSize) * scale
                  val sFaceY = (faceY / sampleSize) * scale
                  val sFaceW = (faceW / sampleSize) * scale
                  val faceCenterX = sFaceX + (sFaceW / 2)
                  val faceCenterY = sFaceY + (sFaceW / 2)
                  cropX = (faceCenterX - targetW / 2).coerceIn(0f, scaledW - targetW)
                  cropY = (faceCenterY - targetH / 2).coerceIn(0f, scaledH - targetH)
             }

             frameBuffer.matrix.reset()
             frameBuffer.matrix.setScale(scale, scale)
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
