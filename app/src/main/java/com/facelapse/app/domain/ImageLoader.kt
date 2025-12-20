package com.facelapse.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class LoadedBitmap(val bitmap: Bitmap, val sampleSize: Int)

@Singleton
class ImageLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun loadUprightBitmap(uri: Uri): Bitmap? {
        return try {
            val rotationInDegrees = getRotationInDegrees(uri)

            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            } ?: return null

            rotateBitmapIfNeeded(bitmap, rotationInDegrees)
        } catch (t: Throwable) {
            Log.e("ImageLoader", "Error loading bitmap from URI: $uri", t)
            null
        }
    }

    fun loadOptimizedBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): LoadedBitmap? {
        return try {
            val rotationInDegrees = getRotationInDegrees(uri)

            // Get Dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // Calculate inSampleSize
            // If rotated 90/270, effective width/height are swapped relative to the raw file
            val (srcWidth, srcHeight) = if (rotationInDegrees == 90 || rotationInDegrees == 270) {
                options.outHeight to options.outWidth
            } else {
                options.outWidth to options.outHeight
            }

            val sampleSize = calculateInSampleSize(srcWidth, srcHeight, reqWidth, reqHeight)

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            } ?: return null

            val rotatedBitmap = rotateBitmapIfNeeded(bitmap, rotationInDegrees)

            LoadedBitmap(rotatedBitmap, sampleSize)
        } catch (e: Exception) {
            Log.e("ImageLoader", "Error loading optimized bitmap", e)
            null
        }
    }

    private fun getRotationInDegrees(uri: Uri): Int {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            try {
                val exifInterface = ExifInterface(inputStream)
                val orientation = exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (e: Exception) {
                Log.w("ImageLoader", "Could not read EXIF data from image: $uri", e)
                0
            }
        } ?: 0
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, rotationInDegrees: Int): Bitmap {
        return if (rotationInDegrees != 0) {
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
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight: Int = srcHeight / 2
            val halfWidth: Int = srcWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
