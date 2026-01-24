package com.facelapse.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LoadedBitmap(
    val bitmap: Bitmap,
    val sampleSize: Int
)

data class ExifData(
    val timestamp: LocalDateTime,
    val rotation: Int
)

@Singleton
class ImageLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun getExifData(uri: Uri): ExifData? = withContext(Dispatchers.IO) {
        val tempFile = copyToTemp(uri) ?: return@withContext null
        try {
            val exifInterface = ExifInterface(tempFile.absolutePath)

            // Rotation
            val rotation = when (exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            // Timestamp
            val dateTimeString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exifInterface.getAttribute(ExifInterface.TAG_DATETIME)

            val timestamp = if (dateTimeString != null) {
                try {
                    // EXIF format is typically "yyyy:MM:dd HH:mm:ss"
                    val formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
                    LocalDateTime.parse(dateTimeString, formatter)
                } catch (e: Exception) {
                    Log.w("ImageLoader", "Failed to parse EXIF timestamp '$dateTimeString'", e)
                    LocalDateTime.now()
                }
            } else {
                LocalDateTime.now()
            }

            ExifData(timestamp, rotation)
        } catch (e: Exception) {
            Log.e("ImageLoader", "Failed to read EXIF data", e)
            null
        } finally {
            if (tempFile != null && tempFile.exists()) tempFile.delete()
        }
    }

    suspend fun loadUprightBitmap(uri: Uri): Bitmap? {
        return loadOptimizedBitmap(uri)?.bitmap
    }

    suspend fun getDimensions(uri: Uri): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val tempFile = copyToTemp(uri) ?: return@withContext null
        try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(tempFile.absolutePath, options)

            val rotation = getRotation(tempFile)

            val w = options.outWidth
            val h = options.outHeight

            if (rotation == 90 || rotation == 270) {
                h to w
            } else {
                w to h
            }
        } catch (e: Exception) {
             Log.e("ImageLoader", "Error getting dimensions", e)
             null
        } finally {
            if (tempFile != null && tempFile.exists()) tempFile.delete()
        }
    }

    suspend fun loadOptimizedBitmap(uri: Uri, reqWidth: Int? = null, reqHeight: Int? = null): LoadedBitmap? = withContext(Dispatchers.IO) {
        val tempFile = copyToTemp(uri) ?: return@withContext null

        try {
            val rotationInDegrees = getRotation(tempFile)

            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(tempFile.absolutePath, options)

            var inSampleSize = 1
            if (reqWidth != null && reqHeight != null) {
                // If rotated 90/270, swap dimensions to compare with visual target
                val (srcWidth, srcHeight) = if (rotationInDegrees == 90 || rotationInDegrees == 270) {
                    options.outHeight to options.outWidth
                } else {
                    options.outWidth to options.outHeight
                }
                inSampleSize = calculateInSampleSize(srcWidth, srcHeight, reqWidth, reqHeight)
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize

            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, options) ?: return@withContext null

            val rotated = try {
                if (rotationInDegrees != 0) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotationInDegrees.toFloat())
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotatedBitmap != bitmap) {
                        bitmap.recycle()
                    }
                    rotatedBitmap
                } else {
                    bitmap
                }
            } catch (t: Throwable) {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
                throw t
            }
            LoadedBitmap(rotated, inSampleSize)

        } catch (t: Throwable) {
            Log.e("ImageLoader", "Error loading bitmap from URI: $uri", t)
            null
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private suspend fun copyToTemp(uri: Uri): File? = withContext(Dispatchers.IO) {
        val tempFile = try {
            File.createTempFile("image_load_", ".tmp", context.cacheDir)
        } catch (e: Exception) {
            Log.e("ImageLoader", "Failed to create temp file", e)
            return@withContext null
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null
            tempFile
        } catch (e: Exception) {
             Log.e("ImageLoader", "Failed to copy content to temp file: $uri", e)
             if (tempFile.exists()) tempFile.delete()
             null
        }
    }

    private fun getRotation(file: File): Int {
        return try {
            val exifInterface = ExifInterface(file.absolutePath)
            when (exifInterface.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.w("ImageLoader", "Could not read EXIF data from file: ${file.absolutePath}", e)
            0
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
