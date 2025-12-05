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
    @ApplicationContext private val context: Context
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
            try {
                if (outputFile.exists()) outputFile.delete()

                // 1. Enforce 16-byte alignment to prevent diagonal shearing
                val width = roundTo16(targetWidth)
                val height = roundTo16(targetHeight)

                val mediaMuxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // 2. Setup MediaFormat with standard NV12 (YUV420SemiPlanar)
                // Note: COLOR_FormatYUV420SemiPlanar is typically NV12 (Y followed by UV interleaved).
                val mime = MediaFormat.MIMETYPE_VIDEO_AVC
                val format = MediaFormat.createVideoFormat(mime, width, height)

                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                val encoder = MediaCodec.createEncoderByType(mime)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                var trackIndex = -1
                var muxerStarted = false
                val bufferInfo = MediaCodec.BufferInfo()

                // Local function to handle draining the encoder
                fun drainEncoder(endOfStream: Boolean) {
                    val timeoutUs = if (endOfStream) 10_000L else 0L
                    var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)

                    while (outputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (muxerStarted) {
                                throw RuntimeException("format changed twice")
                            }
                            val newFormat = encoder.outputFormat
                            trackIndex = mediaMuxer.addTrack(newFormat)
                            mediaMuxer.start()
                            muxerStarted = true
                        } else if (outputBufferIndex >= 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                // The codec config data was pulled out and fed to the muxer when we got
                                // the INFO_OUTPUT_FORMAT_CHANGED status. Ignore it.
                                bufferInfo.size = 0
                            }

                            if (bufferInfo.size != 0) {
                                if (!muxerStarted) {
                                    // Should not happen if INFO_OUTPUT_FORMAT_CHANGED is handled correctly
                                    val newFormat = encoder.outputFormat
                                    trackIndex = mediaMuxer.addTrack(newFormat)
                                    mediaMuxer.start()
                                    muxerStarted = true
                                }
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputBufferIndex, false)
                        } else {
                            // Break on unexpected index (e.g. negative but not handled above)
                            if (outputBufferIndex < 0) break
                        }
                        outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
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
                        val inputBufferIndex = encoder.dequeueInputBuffer(10_000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
                            inputBuffer.clear()
                            inputBuffer.put(yuvBuffer)
                            encoder.queueInputBuffer(inputBufferIndex, 0, yuvBuffer.size, presentationTimeUs, 0)
                            presentationTimeUs += frameDurationUs
                        }
                        bitmap.recycle()
                    }

                    // Regular Drain
                    drainEncoder(endOfStream = false)
                }

                // End of Stream
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }

                // Final Drain
                drainEncoder(endOfStream = true)

                encoder.stop()
                encoder.release()
                if (muxerStarted) mediaMuxer.stop()
                mediaMuxer.release()

                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
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
                val encoder = AnimatedGifEncoder()
                encoder.start(java.io.FileOutputStream(outputFile))
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
             var rotationInDegrees = 0
             val bitmap = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                 val fileDescriptor = pfd.fileDescriptor
                 try {
                     val exifInterface = ExifInterface(fileDescriptor)
                     val orientation = exifInterface.getAttributeInt(
                         ExifInterface.TAG_ORIENTATION,
                         ExifInterface.ORIENTATION_NORMAL
                     )
                     rotationInDegrees = when (orientation) {
                         ExifInterface.ORIENTATION_ROTATE_90 -> 90
                         ExifInterface.ORIENTATION_ROTATE_180 -> 180
                         ExifInterface.ORIENTATION_ROTATE_270 -> 270
                         else -> 0
                     }
                 } catch (e: Exception) {
                     android.util.Log.e("VideoGenerator", "Error reading Exif", e)
                 }
                 BitmapFactory.decodeFileDescriptor(fileDescriptor)
             } ?: return null

             val rotatedBitmap = if (rotationInDegrees != 0) {
                 val matrix = android.graphics.Matrix()
                 matrix.postRotate(rotationInDegrees.toFloat())
                 val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                 if (rotated != bitmap) {
                     bitmap.recycle()
                 }
                 rotated
             } else {
                 bitmap
             }

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
        // NV12 conversion (YUV 4:2:0 Semi-Planar, U then V)
        // Made internal/visible for testing
        internal fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
            val frameSize = width * height
            var yIndex = 0
            var uvIndex = frameSize
            var r: Int
            var g: Int
            var b: Int
            var Y: Int
            var U: Int
            var V: Int
            var index = 0

            for (j in 0 until height) {
                for (i in 0 until width) {
                    val pixel = argb[index]
                    r = (pixel and 0xff0000) shr 16
                    g = (pixel and 0xff00) shr 8
                    b = (pixel and 0xff)

                    // Standard BT.601 conversion
                    Y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    U = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    V = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                    yuv420sp[yIndex++] = Y.coerceIn(0, 255).toByte()

                    // NV12 interleaves U and V (U first)
                    if (j % 2 == 0 && index % 2 == 0) {
                        yuv420sp[uvIndex++] = U.coerceIn(0, 255).toByte()
                        yuv420sp[uvIndex++] = V.coerceIn(0, 255).toByte()
                    }
                    index++
                }
            }
        }
    }
}
