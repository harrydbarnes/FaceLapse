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

                // 2. Setup MediaFormat with standard NV21/YUV420SemiPlanar
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

                // 3. Pre-allocate buffer for YUV data (Y size + UV size)
                val yuvBuffer = ByteArray(width * height * 3 / 2)
                var presentationTimeUs = 0L
                val frameDurationUs = 1_000_000L / fps

                for (photo in photos) {
                    if (!isActive()) break

                    // Load and process bitmap
                    // Updated to pass faceHeight for better centering
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

                        // Convert ARGB Bitmap to YUV420SP (NV21)
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

                    // Drain Output
                    var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    while (outputBufferIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0) {
                            if (!muxerStarted) {
                                trackIndex = mediaMuxer.addTrack(encoder.outputFormat)
                                mediaMuxer.start()
                                muxerStarted = true
                            }
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    }
                }

                // End of Stream
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }

                // Final Drain
                var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outputIndex >= 0) {
                     val outputBuffer = encoder.getOutputBuffer(outputIndex)!!
                     if (bufferInfo.size != 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                     }
                     encoder.releaseOutputBuffer(outputIndex, false)
                     outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }

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

    private fun roundTo16(x: Int): Int {
        var res = x
        if (res % 16 != 0) {
            res = ((x / 16) + 1) * 16
        }
        return res
    }

    // Standard NV21 conversion with Blue Tint Fix (Swapped R and B usage)
    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
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
                // Extract R, G, B assuming ARGB_8888
                val pixel = argb[index]
                r = (pixel and 0xff0000) shr 16
                g = (pixel and 0xff00) shr 8
                b = (pixel and 0xff)

                // Fix: Swap R and B in calculations to fix Blue Tint issue
                // BT.601 conversion using B where R was expected, and R where B was expected
                // Original: Y = 66*R + 129*G + 25*B ...
                // Swapped:  Y = 66*B + 129*G + 25*R ...

                val R = b // Swap logic
                val B = r
                val G = g

                Y = ((66 * R + 129 * G + 25 * B + 128) shr 8) + 16
                U = ((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128
                V = ((112 * R - 94 * G - 18 * B + 128) shr 8) + 128

                yuv420sp[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()

                // NV21 interleaves V and U (V first)
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                    yuv420sp[uvIndex++] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
                }
                index++
            }
        }
    }

    private fun isActive(): Boolean = true

    private fun loadBitmap(
        uri: Uri,
        targetW: Int,
        targetH: Int,
        faceX: Float?,
        faceY: Float?,
        faceW: Float?,
        faceH: Float? // Added faceHeight
    ): Bitmap? {
        return try {
             val inputStream = context.contentResolver.openInputStream(uri)
             val bitmap = BitmapFactory.decodeStream(inputStream)
             inputStream?.close() ?: return null

             // Calculate scale needed to fill target dimensions
             val scale = Math.max(targetW.toFloat() / bitmap.width, targetH.toFloat() / bitmap.height)
             val scaledW = (bitmap.width * scale).roundToInt()
             val scaledH = (bitmap.height * scale).roundToInt()

             val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)

             // Default: Center Crop
             var cropX = (scaledW - targetW) / 2
             var cropY = (scaledH - targetH) / 2

             // If Face data exists, Center on Face
             if (faceX != null && faceY != null && faceW != null && faceH != null) {
                  val sFaceX = faceX * scale
                  val sFaceY = faceY * scale
                  val sFaceW = faceW * scale
                  val sFaceH = faceH * scale // Use height

                  val faceCenterX = sFaceX + (sFaceW / 2)
                  val faceCenterY = sFaceY + (sFaceH / 2) // Use height for Y center

                  cropX = (faceCenterX - targetW / 2).toInt().coerceIn(0, scaledW - targetW)
                  cropY = (faceCenterY - targetH / 2).toInt().coerceIn(0, scaledH - targetH)
             } else if (faceX != null && faceY != null && faceW != null) {
                  // Fallback if faceH missing (old data?)
                  val sFaceX = faceX * scale
                  val sFaceY = faceY * scale
                  val sFaceW = faceW * scale

                  val faceCenterX = sFaceX + (sFaceW / 2)
                  val faceCenterY = sFaceY + (sFaceW / 2)

                  cropX = (faceCenterX - targetW / 2).toInt().coerceIn(0, scaledW - targetW)
                  cropY = (faceCenterY - targetH / 2).toInt().coerceIn(0, scaledH - targetH)
             }

             val finalBitmap = Bitmap.createBitmap(scaledBitmap, cropX, cropY, targetW, targetH)
             if (finalBitmap != scaledBitmap && finalBitmap != bitmap) scaledBitmap.recycle()
             if (finalBitmap != bitmap) bitmap.recycle()

             // Ensure mutable for Canvas drawing
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
            textSize = if (fontSize > 0) fontSize.toFloat() else 60f // Ensure reasonable default
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
}
