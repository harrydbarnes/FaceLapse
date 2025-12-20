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
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedBitmap(
    val bitmap: Bitmap,
    val sampleSize: Int
)

@Singleton
class ImageLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun loadUprightBitmap(uri: Uri): Bitmap? {
        return loadOptimizedBitmap(uri)?.bitmap
    }

    fun loadOptimizedBitmap(uri: Uri, reqWidth: Int? = null, reqHeight: Int? = null): LoadedBitmap? {
        val tempFile = try {
            File.createTempFile("image_load_", ".tmp", context.cacheDir)
        } catch (e: Exception) {
            Log.e("ImageLoader", "Failed to create temp file", e)
            return null
        }

        return try {
            // Copy URI content to temp file to avoid opening multiple input streams
            // (crucial for cloud-backed URIs)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

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

            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, options) ?: return null

            val rotated = if (rotationInDegrees != 0) {
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
            LoadedBitmap(rotated, inSampleSize)

        } catch (t: Throwable) {
            Log.e("ImageLoader", "Error loading bitmap from URI: $uri", t)
            null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
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
