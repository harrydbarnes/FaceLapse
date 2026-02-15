package com.facelapse.app.ui.project

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.facelapse.app.domain.model.Photo
import com.google.mlkit.vision.face.Face
import kotlin.math.min

@Composable
fun FaceOverlay(
    photo: Photo,
    faces: List<Face>,
    selectedFace: Face?,
    onFaceClick: (Face) -> Unit,
    detectionWidth: Int,
    detectionHeight: Int,
    modifier: Modifier = Modifier
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    // Use semi-transparent white for unselected faces to ensure visibility on dark images
    // but not be too distracting.
    val outlineColor = Color.White.copy(alpha = 0.7f)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier = modifier) {
        val imagePainter = rememberAsyncImagePainter(model = photo.originalUri)
        val painterState = imagePainter.state

        Image(
            painter = imagePainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        if (painterState is AsyncImagePainter.State.Success) {
            val intrinsicSize = painterState.result.drawable.intrinsicWidth.toFloat() to painterState.result.drawable.intrinsicHeight.toFloat()
            val (imgW, imgH) = intrinsicSize

            val originalW = if (detectionWidth > 0) detectionWidth.toFloat() else imgW
            val originalH = if (detectionHeight > 0) detectionHeight.toFloat() else imgH

            val scaleX = imgW / originalW
            val scaleY = imgH / originalH

            Canvas(
                modifier = Modifier
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(faces, imgW, imgH) {
                        detectTapGestures { tapOffset ->
                            val (w, h) = canvasSize.width.toFloat() to canvasSize.height.toFloat()
                            val scale = min(w / imgW, h / imgH)
                            val displayedW = imgW * scale
                            val displayedH = imgH * scale
                            val offsetX = (w - displayedW) / 2
                            val offsetY = (h - displayedH) / 2

                            val clickedFace = faces.find { face ->
                                val rect = face.boundingBox
                                val mappedRect = Rect(
                                    left = rect.left * scaleX * scale + offsetX,
                                    top = rect.top * scaleY * scale + offsetY,
                                    right = rect.right * scaleX * scale + offsetX,
                                    bottom = rect.bottom * scaleY * scale + offsetY
                                )
                                mappedRect.contains(tapOffset)
                            }

                            if (clickedFace != null) {
                                onFaceClick(clickedFace)
                            }
                        }
                    }
            ) {
                val (w, h) = size.width to size.height
                val scale = min(w / imgW, h / imgH)
                val displayedW = imgW * scale
                val displayedH = imgH * scale
                val offsetX = (w - displayedW) / 2
                val offsetY = (h - displayedH) / 2

                faces.forEach { face ->
                    val isSelected = face == selectedFace
                    val rect = face.boundingBox

                    val mappedRect = Rect(
                        left = rect.left * scaleX * scale + offsetX,
                        top = rect.top * scaleY * scale + offsetY,
                        right = rect.right * scaleX * scale + offsetX,
                        bottom = rect.bottom * scaleY * scale + offsetY
                    )

                    val strokeColor = if (isSelected) highlightColor else outlineColor
                    val strokeWidth = if (isSelected) 5.dp.toPx() else 2.dp.toPx()

                    drawRect(
                        color = strokeColor,
                        topLeft = mappedRect.topLeft,
                        size = mappedRect.size,
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
        }
    }
}
