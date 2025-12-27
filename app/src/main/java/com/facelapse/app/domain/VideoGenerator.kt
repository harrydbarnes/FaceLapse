package com.facelapse.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.facelapse.app.data.local.entity.PhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class VideoGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader
) {

    suspend fun generateVideo(
        photos: List<PhotoEntity>,
        outputFile: File,
        isDateOverlayEnabled: Boolean,
        dateFontSize: Int,
        dateFormat: String,
        targetWidth: Int = 1080,
        targetHeight: Int = 1920,
        fps: Float = 10f
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var encoder: MediaCodec? = null
            var mediaMuxer: MediaMuxer? = null
            var trackIndex = -1
            var muxerStarted = false
            var success = false
            var frameBuffer: FrameBuffer? = null

            try {
                if (outputFile.exists()) outputFile.delete()

                // 1. Enforce 16-byte alignment to prevent diagonal shearing
                val width = roundTo16(targetWidth)
                val height = roundTo16(targetHeight)

                mediaMuxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // 2. Setup MediaFormat with standard NV12 (YUV420SemiPlanar)
                // Note: COLOR_FormatYUV420SemiPlanar is typically NV12 (Y followed by UV interleaved).
                val mime = MediaFormat.MIMETYPE_VIDEO_AVC
                val format = MediaFormat.createVideoFormat(mime, width, height)

                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                format.setFloat(MediaFormat.KEY_FRAME_RATE, fps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                encoder = MediaCodec.createEncoderByType(mime)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                // Non-null local references for use in the loop and helper function
                val localEncoder = encoder!!
                val localMuxer = mediaMuxer!!
                val bufferInfo = MediaCodec.BufferInfo()

                // Local function to handle draining the encoder
                fun drainEncoder(endOfStream: Boolean) {
                    val timeoutUs = if (endOfStream) 10_000L else 0L
                    var outputBufferIndex = localEncoder.dequeueOutputBuffer(bufferInfo, timeoutUs)

                    while (outputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (muxerStarted) {
                                throw IllegalStateException("format changed twice")
                            }
                            val newFormat = localEncoder.outputFormat
                            trackIndex = localMuxer.addTrack(newFormat)
                            localMuxer.start()
                            muxerStarted = true
                        } else if (outputBufferIndex >= 0) {
                            val outputBuffer = localEncoder.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    // The codec config data was pulled out and fed to the muxer when we got
                                    // the INFO_OUTPUT_FORMAT_CHANGED status. Ignore it.
                                    bufferInfo.size = 0
                                }

                                if (bufferInfo.size != 0) {
                                    if (!muxerStarted) {
                                        throw IllegalStateException("Muxer not started before writing sample data.")
                                    }
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    localMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                                }
                            }
                            localEncoder.releaseOutputBuffer(outputBufferIndex, false)
                        }
                        // Ignore other status codes (e.g. INFO_OUTPUT_BUFFERS_CHANGED)
                        outputBufferIndex = localEncoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    }
                }

                // 3. Pre-allocate buffer for YUV data (Y size + UV size)
                val yuvBuffer = ByteArray(width * height * 3 / 2)
                var presentationTimeUs = 0L
                val frameDurationUs = (1_000_000.0 / fps).toLong()

                // Pre-allocate buffers and objects to avoid allocation in loop
                frameBuffer = FrameBuffer(width, height)

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
                        java.text.SimpleDateFormat(dateFormat, java.util.Locale.getDefault())
                    } catch (e: Exception) {
                        null
                    }
                } else null

                for (photo in photos) {
                    if (!isActive()) break

                    // Load and process bitmap directly into reused outBitmap
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

                        // Convert ARGB Bitmap to YUV420SP (NV12)
                        // Reads pixels directly from Bitmap in native code to avoid copy
                        encodeYUV420SP(yuvBuffer, frameBuffer.bitmap, width, height)

                        // Feed to Encoder
                        val inputBufferIndex = localEncoder.dequeueInputBuffer(10_000)
                        if (inputBufferIndex >= 0) {
                            localEncoder.getInputBuffer(inputBufferIndex)?.let { inputBuffer ->
                                inputBuffer.clear()
                                inputBuffer.put(yuvBuffer)
                                localEncoder.queueInputBuffer(inputBufferIndex, 0, yuvBuffer.size, presentationTimeUs, 0)
                                presentationTimeUs += frameDurationUs
                            }
                        }
                        // Do NOT recycle outBitmap here, it is reused
                    }

                    // Regular Drain
                    drainEncoder(endOfStream = false)
                }

                // End of Stream
                val inputIndex = localEncoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    localEncoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }

                // Final Drain
                drainEncoder(endOfStream = true)

                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Error generating video", e)
                success = false
            } finally {
                safeCleanup({ encoder?.stop() }, "Error stopping encoder")
                safeCleanup({ encoder?.release() }, "Error releasing encoder")
                safeCleanup({
                    if (muxerStarted) {
                        mediaMuxer?.stop()
                    }
                }, "Error stopping muxer")
                safeCleanup({ mediaMuxer?.release() }, "Error releasing muxer")
                frameBuffer?.recycle()
            }
            success
        }
    }

    suspend fun generateGif(
        photos: List<PhotoEntity>,
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

                // Pre-allocate reused bitmap
                frameBuffer = FrameBuffer(targetWidth, targetHeight)

                java.io.FileOutputStream(outputFile).use { fos ->
                    val encoder = AnimatedGifEncoder()
                    encoder.start(fos)
                    encoder.setFrameRate(fps)
                    encoder.setRepeat(0) // 0 = loop indefinitely
                    encoder.setQuality(10) // default

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
                            java.text.SimpleDateFormat(dateFormat, java.util.Locale.getDefault())
                        } catch (e: Exception) {
                            null
                        }
                    } else null

                    for (photo in photos) {
                        if (!isActive()) break

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
                            // Do NOT recycle outBitmap here
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

    private fun roundTo16(x: Int): Int {
        var res = x
        if (res % 16 != 0) {
            res = ((x / 16) + 1) * 16
        }
        return res
    }

    private fun isActive(): Boolean = true

    /**
     * Loads and renders the bitmap directly onto the provided [frameBuffer].
     * Avoids intermediate allocations for scaled/cropped bitmaps.
     */
    private fun loadBitmapToCanvas(
        uri: Uri,
        frameBuffer: FrameBuffer,
        faceX: Float?,
        faceY: Float?,
        faceW: Float?,
        faceH: Float?
    ): Boolean {
        return try {
             // Clear the canvas to avoid artifacts from previous frames if the new image has transparency
             frameBuffer.bitmap.eraseColor(Color.TRANSPARENT)

             val targetW = frameBuffer.bitmap.width
             val targetH = frameBuffer.bitmap.height

             // Use optimized loading to reduce memory usage during export
             val result = imageLoader.loadOptimizedBitmap(uri, targetW, targetH) ?: return false
             val rotatedBitmap = result.bitmap
             val sampleSize = result.sampleSize

             // Calculate scale to cover the target area (CenterCrop)
             val scale = kotlin.math.max(targetW.toFloat() / rotatedBitmap.width, targetH.toFloat() / rotatedBitmap.height)

             // Calculate virtual scaled dimensions (if we were to scale the whole image)
             val scaledW = (rotatedBitmap.width * scale)
             val scaledH = (rotatedBitmap.height * scale)

             var cropX = (scaledW - targetW) / 2
             var cropY = (scaledH - targetH) / 2

             if (faceX != null && faceY != null && faceW != null && faceH != null) {
                  // Adjust full-resolution face coordinates to the loaded (potentially subsampled) bitmap coordinate system
                  // faceX (Full) -> faceX / sampleSize (Loaded) -> * scale (Target)
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

                  // Fallback: If faceHeight is missing, assume a square face box (height = width).
                  val faceCenterX = sFaceX + (sFaceW / 2)
                  val faceCenterY = sFaceY + (sFaceW / 2)

                  cropX = (faceCenterX - targetW / 2).coerceIn(0f, scaledW - targetW)
                  cropY = (faceCenterY - targetH / 2).coerceIn(0f, scaledH - targetH)
             }

             // Render directly to outBitmap using reused Matrix
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
        timestamp: Long,
        paint: Paint,
        dateFormat: java.text.SimpleDateFormat?
    ) {
        val canvas = Canvas(bitmap)
        val dateString = try {
            dateFormat?.format(java.util.Date(timestamp)) ?: "Error"
        } catch (e: Exception) {
             "Error"
        }
        val x = bitmap.width / 2f
        val y = bitmap.height - 100f
        canvas.drawText(dateString, x, y, paint)
    }

    private fun safeCleanup(action: () -> Unit, errorMessage: String) {
        try {
            action()
        } catch (e: Exception) {
            Log.e(TAG, errorMessage, e)
        }
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
        private const val BT601_Y_R = 66
        private const val BT601_Y_G = 129
        private const val BT601_Y_B = 25
        private const val BT601_U_R = -38
        private const val BT601_U_G = -74
        private const val BT601_U_B = 112
        private const val BT601_V_R = 112
        private const val BT601_V_G = -94
        private const val BT601_V_B = -18

        // NV12 conversion (YUV 4:2:0 Semi-Planar, U then V)
        // Made internal/visible for testing
        // Implemented in native code for performance.
        @JvmStatic
        external fun encodeYUV420SP(yuv420sp: ByteArray, bitmap: Bitmap, width: Int, height: Int)

        init {
            try {
                System.loadLibrary("yuv_encoder")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library 'yuv_encoder' failed to load.", e)
                throw e
            }
        }
    }
}
