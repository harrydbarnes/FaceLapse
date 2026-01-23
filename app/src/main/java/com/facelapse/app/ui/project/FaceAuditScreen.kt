package com.facelapse.app.ui.project

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.facelapse.app.domain.model.Photo
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FaceAuditScreen(
    viewModel: ProjectViewModel,
    onBackClick: () -> Unit
) {
    val photos by viewModel.photos.collectAsState(initial = emptyList())
    val pagerState = rememberPagerState(pageCount = { photos.size })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Face Audit")
                        if (photos.isNotEmpty()) {
                            Text(
                                "${pagerState.currentPage + 1} / ${photos.size}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (photos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No photos in project.")
            }
        } else {
            Column(modifier = Modifier.padding(padding)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    if (page < photos.size) {
                        FaceAuditItem(
                            photo = photos[page],
                            viewModel = viewModel
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        enabled = pagerState.currentPage > 0
                    ) {
                        Text("Previous")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        enabled = pagerState.currentPage < photos.size - 1
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

@Composable
fun FaceAuditItem(
    photo: Photo,
    viewModel: ProjectViewModel
) {
    var detectedFaces by remember { mutableStateOf<List<Face>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(photo.id) {
        isLoading = true
        detectedFaces = viewModel.getFacesForPhoto(photo)
        isLoading = false
    }

    // Determine currently selected face based on DB values
    val currentSelectedFace = remember(detectedFaces, photo) {
        findMatchingFace(detectedFaces, photo)
    }

    // Define colors for selected (highlight) vs unselected (outline) faces
    val highlightColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            val imagePainter = rememberAsyncImagePainter(model = photo.originalUri)
            val painterState = imagePainter.state

            Image(
                painter = imagePainter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            if (painterState is AsyncImagePainter.State.Success && containerSize != IntSize.Zero) {
                val intrinsicSize = painterState.result.drawable.intrinsicWidth.toFloat() to painterState.result.drawable.intrinsicHeight.toFloat()
                val (imgW, imgH) = intrinsicSize
                val (viewW, viewH) = containerSize.width.toFloat() to containerSize.height.toFloat()

                val scale = min(viewW / imgW, viewH / imgH)
                val displayedW = imgW * scale
                val displayedH = imgH * scale
                val offsetX = (viewW - displayedW) / 2
                val offsetY = (viewH - displayedH) / 2

                val mappedFaces = detectedFaces.map { face ->
                    val rect = face.boundingBox
                    val mappedRect = Rect(
                        left = rect.left * scale + offsetX,
                        top = rect.top * scale + offsetY,
                        right = rect.right * scale + offsetX,
                        bottom = rect.bottom * scale + offsetY
                    )
                    face to mappedRect
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(mappedFaces) {
                            detectTapGestures { tapOffset ->
                                val clickedFace = mappedFaces.find { (_, rect) ->
                                    rect.contains(tapOffset)
                                }?.first

                                if (clickedFace != null) {
                                    viewModel.updateFaceSelection(photo, clickedFace)
                                }
                            }
                        }
                ) {
                    mappedFaces.forEach { (face, rect) ->
                        val isSelected = face == currentSelectedFace

                        val strokeColor = if (isSelected) highlightColor else outlineColor
                        val strokeWidth = if (isSelected) 6.dp.toPx() else 3.dp.toPx()

                        drawRect(
                            color = strokeColor,
                            topLeft = rect.topLeft,
                            size = rect.size,
                            style = Stroke(width = strokeWidth)
                        )
                    }
                }
            }

            if (!isLoading && detectedFaces.isEmpty()) {
                 Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium)
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                         Icon(Icons.Default.Warning, "No faces", tint = MaterialTheme.colorScheme.onErrorContainer)
                         Spacer(modifier = Modifier.width(8.dp))
                         Text("No faces detected", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            } else if (!isLoading && currentSelectedFace == null && detectedFaces.isNotEmpty()) {
                 Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium)
                        .padding(8.dp)
                ) {
                     Text("Tap a face to select it", color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}
