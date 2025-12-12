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
                    Log.e("ImageLoader", "Error reading Exif", e)
                }
                BitmapFactory.decodeFileDescriptor(fileDescriptor)
            } ?: return null

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
        } catch (e: Exception) {
            Log.e("ImageLoader", "Error loading bitmap", e)
            null
        }
    }
}
