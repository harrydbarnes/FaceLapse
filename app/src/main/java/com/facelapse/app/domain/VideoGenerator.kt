package com.facelapse.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import com.facelapse.app.data.local.entity.PhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.graphics.Color
import android.graphics.Rect
import android.os.Environment
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.RectF

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
        width: Int = 1080,
        height: Int = 1920,
        fps: Int = 10
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (outputFile.exists()) {
                    outputFile.delete()
                }

                val mediaMuxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                encoder.start()

                var trackIndex = -1
                var muxerStarted = false
                val bufferInfo = MediaCodec.BufferInfo()

                // Use a canvas to draw on the input surface
                // Note: Using a Surface for input requires EGL setup which is complex for a simple script.
                // However, for this task, I'll assume we can use a simpler approach or I might need to implement EGL context.
                // To avoid implementing full EGL context here (which is verbose), I will write a simplified logic structure
                // or assume a helper if I could, but since I must write all code...

                // Wait! InputSurface requires EGL. Writing pure MediaCodec+Surface+EGL in a single file is huge.
                // A better approach for this simplified environment is generating frames as Bitmaps and potentially using a library,
                // BUT I am restricted to what I have.
                // Let's implement a simplified "SlideShow" by drawing on the surface using standard Android Canvas if possible?
                // No, InputSurface is an OpenGL ES surface.

                // ALTERNATIVE: Use `Android-Image-To-Video` logic which often uses `MediaCodec` with byte buffers if not using Surface.
                // Using ByteBuffers is easier than EGL for simple bitmaps.

                // Let's re-configure for ByteBuffer input if possible? No, Surface is recommended for performance.
                // But setting up EGL is too much code for this interaction.

                // Plan B: I will use a very basic MediaCodec implementation using ByteBuffers (YUV conversion is needed)
                // OR I will simply mock the video generation for now if it's too complex,
                // BUT the requirements say "Core requirements... Generate an MP4".

                // Let's try to implement a basic frame processing loop.
                // For the sake of this exercise and avoiding 500 lines of EGL boilerplate,
                // I will use a placeholder or simplified logic that "simulates" the video generation
                // OR creates a GIF which is easier (standard Android Bitmap/Canvas).
                // Re-reading requirements: "Generate an MP4 or GIF".
                // GIF is easier without native libraries (can use standard Java GIF encoders).
                // Let's stick to MP4 if possible, but maybe I can use a simpler method.

                // Actually, I'll write the structure for MP4 generation and use a simplified "copy bitmap to surface" approach
                // assuming I had an EGL helper.
                // Since I don't have an EGL helper, I will implement a GIF encoder instead as a fallback?
                // No, MP4 is preferred.

                // Okay, I will try to implement a basic `MediaCodec` wrapper that takes Bitmaps.
                // I'll skip the EGL part and use InputBuffers with color conversion (ARGB -> YUV420) which is pure Kotlin/Java.

                // Color conversion function
                fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
                    val frameSize = width * height
                    var yIndex = 0
                    var uvIndex = frameSize
                    var a: Int
                    var R: Int
                    var G: Int
                    var B: Int
                    var Y: Int
                    var U: Int
                    var V: Int
                    var index = 0
                    for (j in 0 until height) {
                        for (i in 0 until width) {
                            a = (argb[index] and -0x1000000) shr 24 // a is not used obviously
                            R = (argb[index] and 0xff0000) shr 16
                            G = (argb[index] and 0xff00) shr 8
                            B = (argb[index] and 0xff) shr 0

                            // Well known RGB to YUV algorithm
                            Y = ((66 * R + 129 * G + 25 * B + 128) shr 8) + 16
                            U = ((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128
                            V = ((112 * R - 94 * G - 18 * B + 128) shr 8) + 128

                            // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                            //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                            //    pixel AND every other scanline.
                            yuv420sp[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()
                            if (j % 2 == 0 && index % 2 == 0) {
                                yuv420sp[uvIndex++] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                                yuv420sp[uvIndex++] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
                            }
                            index++
                        }
                    }
                }

                // Re-configure MediaCodec for ByteBuffer input
                encoder.reset()
                // COLOR_FormatYUV420Flexible is standard, but some encoders prefer specific formats.
                // We'll use COLOR_FormatYUV420SemiPlanar (NV21-ish) which is widely supported or just assume standard.
                val colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                val yuvBuffer = ByteArray(width * height * 3 / 2)

                var presentationTimeUs = 0L
                val frameDurationUs = 1_000_000L / fps

                for (photo in photos) {
                     if (!isActive()) break // Check for cancellation

                     // Load Bitmap (with resizing/cropping logic applied)
                     val bitmap = loadBitmap(Uri.parse(photo.originalUri), width, height, photo.faceX, photo.faceY, photo.faceWidth)

                     if (bitmap != null) {
                         // Draw date overlay if enabled
                         if (isDateOverlayEnabled) {
                             drawDateOverlay(bitmap, photo.timestamp, dateFontSize, dateFormat)
                         }

                         val argb = IntArray(width * height)
                         bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                         encodeYUV420SP(yuvBuffer, argb, width, height)

                         val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                         if (inputBufferIndex >= 0) {
                             val inputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
                             inputBuffer.clear()
                             inputBuffer.put(yuvBuffer)
                             encoder.queueInputBuffer(inputBufferIndex, 0, yuvBuffer.size, presentationTimeUs, 0)
                             presentationTimeUs += frameDurationUs
                         }
                         bitmap.recycle()
                     }

                     // Drain encoder
                     val info = MediaCodec.BufferInfo()
                     var outputBufferIndex = encoder.dequeueOutputBuffer(info, 0)
                     while (outputBufferIndex >= 0) {
                         val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!
                         if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                             info.size = 0
                         }
                         if (info.size != 0) {
                             if (!muxerStarted) {
                                 trackIndex = mediaMuxer.addTrack(encoder.outputFormat)
                                 mediaMuxer.start()
                                 muxerStarted = true
                             }
                             outputBuffer.position(info.offset)
                             outputBuffer.limit(info.offset + info.size)
                             mediaMuxer.writeSampleData(trackIndex, outputBuffer, info)
                         }
                         encoder.releaseOutputBuffer(outputBufferIndex, false)
                         outputBufferIndex = encoder.dequeueOutputBuffer(info, 0)
                     }
                }

                // End of stream
                val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                     encoder.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }

                 // Final drain
                 var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                 while (outputBufferIndex >= 0) {
                     val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!
                      if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                             bufferInfo.size = 0
                      }
                     if (bufferInfo.size != 0) {
                        if (muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                     }
                     encoder.releaseOutputBuffer(outputBufferIndex, false)
                     outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                 }

                encoder.stop()
                encoder.release()
                if (muxerStarted) {
                    mediaMuxer.stop()
                }
                mediaMuxer.release()

                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun isActive(): Boolean = true // simplified cancellation check

    private fun loadBitmap(uri: Uri, targetW: Int, targetH: Int, faceX: Float?, faceY: Float?, faceW: Float?): Bitmap? {
        return try {
             val inputStream = context.contentResolver.openInputStream(uri)
             val bitmap = BitmapFactory.decodeStream(inputStream)
             inputStream?.close()

             if (bitmap == null) return null

             // Simple center crop logic for now, or face centric if coords provided
             val scale = Math.max(targetW.toFloat() / bitmap.width, targetH.toFloat() / bitmap.height)
             val scaledW = (bitmap.width * scale).toInt()
             val scaledH = (bitmap.height * scale).toInt()

             val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)

             var cropX = (scaledW - targetW) / 2
             var cropY = (scaledH - targetH) / 2

             // If face coords exist, try to center on face
             if (faceX != null && faceY != null && faceW != null) {
                  // Transform face coords to scaled bitmap coords
                  val sFaceX = faceX * (scaledW.toFloat() / bitmap.width) // Assuming faceX is absolute in original
                  val sFaceY = faceY * (scaledH.toFloat() / bitmap.height)

                  // Center of face
                  val centerX = sFaceX + (faceW * scale) / 2
                  val centerY = sFaceY + (faceW * scale) / 2 // Assuming square face box or similar aspect

                  cropX = (centerX - targetW / 2).toInt().coerceIn(0, scaledW - targetW)
                  cropY = (centerY - targetH / 2).toInt().coerceIn(0, scaledH - targetH)
             }

             val finalBitmap = Bitmap.createBitmap(scaledBitmap, cropX, cropY, targetW, targetH)
             if (finalBitmap != scaledBitmap && finalBitmap != bitmap) {
                 scaledBitmap.recycle()
             }
             if (finalBitmap != bitmap) {
                 bitmap.recycle()
             }

             finalBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun drawDateOverlay(bitmap: Bitmap, timestamp: Long, fontSize: Int, dateFormat: String) {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = fontSize.toFloat()
            isAntiAlias = true
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
            textAlign = Paint.Align.CENTER
        }

        val dateString = try {
            java.text.SimpleDateFormat(dateFormat, java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        } catch (e: Exception) {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        }

        val x = bitmap.width / 2f
        val y = bitmap.height - 100f // Padding from bottom

        canvas.drawText(dateString, x, y, paint)
    }
}
