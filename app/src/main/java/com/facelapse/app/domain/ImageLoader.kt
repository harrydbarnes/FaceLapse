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
        return try {
            val rotationInDegrees = getRotation(uri)

            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            } ?: return null

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

            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            } ?: return null

            val rotated = if (rotationInDegrees != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotationInDegrees.toFloat())
                val r = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (r != bitmap) {
                    bitmap.recycle()
                }
                r
            } else {
                bitmap
            }
            LoadedBitmap(rotated, inSampleSize)
        } catch (t: Throwable) {
            Log.e("ImageLoader", "Error loading bitmap from URI: $uri", t)
            null
        }
    }

    private fun getRotation(uri: Uri): Int {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            try {
                val exifInterface = ExifInterface(inputStream)
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
                Log.w("ImageLoader", "Could not read EXIF data from image: $uri", e)
                0
            }
        } ?: 0
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
