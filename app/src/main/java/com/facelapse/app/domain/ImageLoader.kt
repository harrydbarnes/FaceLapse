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

@Singleton
class ImageLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun loadUprightBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Buffer the stream to allow mark/reset, which is needed to read EXIF and then decode
                // from the same stream without reopening it.
                val bufferedStream = inputStream.buffered()
                bufferedStream.mark(Integer.MAX_VALUE) // Mark the beginning.

                val rotationInDegrees = try {
                    val exifInterface = ExifInterface(bufferedStream)
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

                try {
                    bufferedStream.reset() // Rewind the stream to the beginning.
                } catch (e: java.io.IOException) {
                    Log.e("ImageLoader", "Failed to reset stream, cannot decode bitmap.", e)
                    return@use null
                }

                val bitmap = BitmapFactory.decodeStream(bufferedStream) ?: return@use null

                if (rotationInDegrees != 0) {
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
        } catch (e: Exception) {
            Log.e("ImageLoader", "Error loading bitmap from URI: $uri", e)
            null
        }
    }
}
