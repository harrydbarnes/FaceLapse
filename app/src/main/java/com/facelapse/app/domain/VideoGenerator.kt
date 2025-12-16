package com.facelapse.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
        fps: Int = 10
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var encoder: MediaCodec? = null
            var mediaMuxer: MediaMuxer? = null
            var trackIndex = -1
            var muxerStarted = false
            var success = false

            fun safeCleanup(action: () -> Unit, errorMessage: String) {
                try {
                    action()
                } catch (e: Exception) {
                    Log.e("VideoGenerator", errorMessage, e)
                }
            }

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
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
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
                val frameDurationUs = 1_000_000L / fps

                for (photo in photos) {
                    if (!isActive()) break

                    // Load and process bitmap
                    val bitmap = loadBitmap(
                        Uri.parse(photo.originalUri),
                        width,
                        height,
                        photo.faceX,
                        photo.faceY,
                        photo.faceWidth,
                        photo.faceHeight
                    )

                    if (bitmap != null) {
                        if (isDateOverlayEnabled) {
                            drawDateOverlay(bitmap, photo.timestamp, dateFontSize, dateFormat)
                        }

                        // Convert ARGB Bitmap to YUV420SP (NV12)
                        val argb = IntArray(width * height)
                        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                        encodeYUV420SP(yuvBuffer, argb, width, height)

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
                        bitmap.recycle()
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
                Log.e("VideoGenerator", "Error generating video", e)
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
        fps: Int = 10
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (outputFile.exists()) outputFile.delete()
                java.io.FileOutputStream(outputFile).use { fos ->
                    val encoder = AnimatedGifEncoder()
                    encoder.start(fos)
                    encoder.setFrameRate(fps.toFloat())
                    encoder.setRepeat(0) // 0 = loop indefinitely
                    encoder.setQuality(10) // default

                    for (photo in photos) {
                        if (!isActive()) break

                        val bitmap = loadBitmap(
                            Uri.parse(photo.originalUri),
                            targetWidth,
                            targetHeight,
                            photo.faceX,
                            photo.faceY,
                            photo.faceWidth,
                            photo.faceHeight
                        )

                        if (bitmap != null) {
                            if (isDateOverlayEnabled) {
                                drawDateOverlay(bitmap, photo.timestamp, dateFontSize, dateFormat)
                            }
                            encoder.addFrame(bitmap)
                            bitmap.recycle()
                        }
                    }
                    encoder.finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
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

    private fun loadBitmap(
        uri: Uri,
        targetW: Int,
        targetH: Int,
        faceX: Float?,
        faceY: Float?,
        faceW: Float?,
        faceH: Float?
    ): Bitmap? {
        return try {
             // Use shared ImageLoader to get upright bitmap
             val rotatedBitmap = imageLoader.loadUprightBitmap(uri) ?: return null

             val scale = Math.max(targetW.toFloat() / rotatedBitmap.width, targetH.toFloat() / rotatedBitmap.height)
             val scaledW = (rotatedBitmap.width * scale).roundToInt()
             val scaledH = (rotatedBitmap.height * scale).roundToInt()

             val scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, scaledW, scaledH, true)

             var cropX = (scaledW - targetW) / 2
             var cropY = (scaledH - targetH) / 2

             if (faceX != null && faceY != null && faceW != null && faceH != null) {
                  val sFaceX = faceX * scale
                  val sFaceY = faceY * scale
                  val sFaceW = faceW * scale
                  val sFaceH = faceH * scale

                  val faceCenterX = sFaceX + (sFaceW / 2)
                  val faceCenterY = sFaceY + (sFaceH / 2)

                  cropX = (faceCenterX - targetW / 2).toInt().coerceIn(0, scaledW - targetW)
                  cropY = (faceCenterY - targetH / 2).toInt().coerceIn(0, scaledH - targetH)
             } else if (faceX != null && faceY != null && faceW != null) {
                  val sFaceX = faceX * scale
                  val sFaceY = faceY * scale
                  val sFaceW = faceW * scale

                  val faceCenterX = sFaceX + (sFaceW / 2)
                  val faceCenterY = sFaceY + (sFaceW / 2)

                  cropX = (faceCenterX - targetW / 2).toInt().coerceIn(0, scaledW - targetW)
                  cropY = (faceCenterY - targetH / 2).toInt().coerceIn(0, scaledH - targetH)
             }

             val finalBitmap = Bitmap.createBitmap(scaledBitmap, cropX, cropY, targetW, targetH)
             if (finalBitmap != scaledBitmap && scaledBitmap != rotatedBitmap) scaledBitmap.recycle()
             if (finalBitmap != rotatedBitmap) rotatedBitmap.recycle()

             val mutableBitmap = finalBitmap.copy(Bitmap.Config.ARGB_8888, true)
             if (mutableBitmap != finalBitmap) finalBitmap.recycle()

             mutableBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun drawDateOverlay(bitmap: Bitmap, timestamp: Long, fontSize: Int, dateFormat: String) {
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
            java.text.SimpleDateFormat(dateFormat, java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        } catch (e: Exception) {
             "Error"
        }
        val x = bitmap.width / 2f
        val y = bitmap.height - 100f
        canvas.drawText(dateString, x, y, paint)
    }

    companion object {
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
        external fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int)

        init {
            try {
                System.loadLibrary("yuv_encoder")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("VideoGenerator", "Native library 'yuv_encoder' failed to load.", e)
                throw e
            }
        }
    }
}
