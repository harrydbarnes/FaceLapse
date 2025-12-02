package com.facelapse.app.ui.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.facelapse.app.data.local.entity.PhotoEntity
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.launch
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    viewModel: ProjectViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val project by viewModel.project.collectAsState(initial = null)
    val photos by viewModel.photos.collectAsState(initial = emptyList())
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addPhotos(uris)
    }

    var showMenu by remember { mutableStateOf(false) }

    // State for Face Selection Dialog
    var selectedPhotoForEditing by remember { mutableStateOf<PhotoEntity?>(null) }

    // Logic for Floating Button: Show "Detect" if photos exist but none are processed
    val showDetectFab = photos.isNotEmpty() && photos.none { it.isProcessed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "Project") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Standard Top Bar Actions
                    IconButton(onClick = { viewModel.processFaces() }, enabled = !isProcessing && photos.isNotEmpty()) {
                        Icon(Icons.Default.Face, contentDescription = "Align Faces")
                    }
                    IconButton(onClick = { viewModel.exportVideo(context) }, enabled = !isGenerating && !isProcessing && photos.isNotEmpty()) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        // FIX: Separated Click Logic
                        DropdownMenuItem(
                            text = { Text("Date Overlay") },
                            trailingIcon = {
                                Switch(
                                    checked = project?.isDateOverlayEnabled == true,
                                    onCheckedChange = { viewModel.toggleDateOverlay(it) }
                                )
                            },
                            onClick = {
                                // Do nothing here, let the Switch handle it to avoid conflicts
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (showDetectFab) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.processFaces() },
                    icon = { Icon(Icons.Default.Face, "Detect") },
                    text = { Text("Detect Faces") }
                )
            } else {
                FloatingActionButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Photos")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (photos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No photos added yet.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(photos) { index, photo ->
                        PhotoItem(
                            photo = photo,
                            isFirst = index == 0,
                            isLast = index == photos.lastIndex,
                            onMoveUp = { viewModel.movePhoto(photo, true) },
                            onMoveDown = { viewModel.movePhoto(photo, false) },
                            onDelete = { viewModel.deletePhoto(photo) },
                            onClick = { selectedPhotoForEditing = photo } // Open selection dialog
                        )
                    }
                }
            }

            if (isProcessing || isGenerating) {
                 Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    // FACE SELECTION DIALOG
    if (selectedPhotoForEditing != null) {
        FaceSelectionDialog(
            photo = selectedPhotoForEditing!!,
            viewModel = viewModel,
            onDismiss = { selectedPhotoForEditing = null }
        )
    }
}

@Composable
fun FaceSelectionDialog(
    photo: PhotoEntity,
    viewModel: ProjectViewModel,
    onDismiss: () -> Unit
) {
    var detectedFaces by remember { mutableStateOf<List<Face>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Load faces when dialog opens
    LaunchedEffect(photo) {
        detectedFaces = viewModel.getFacesForPhoto(photo)
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    "Select Face to Center",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { containerSize = it } // Capture container size
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

                            // Calculate ContentScale.Fit logic
                            val scale = min(viewW / imgW, viewH / imgH)
                            val displayedW = imgW * scale
                            val displayedH = imgH * scale
                            val offsetX = (viewW - displayedW) / 2
                            val offsetY = (viewH - displayedH) / 2

                            // Map Faces
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
                                            // Check if tap is inside any face rect
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
                                    // Highlight if selected
                                    val isSelected = photo.faceX == face.boundingBox.left.toFloat()
                                    val strokeColor = if (isSelected) Color.Green else Color.White
                                    val strokeWidth = if (isSelected) 8.dp.toPx() else 4.dp.toPx()

                                    drawRect(
                                        color = strokeColor,
                                        topLeft = rect.topLeft,
                                        size = rect.size,
                                        style = Stroke(width = strokeWidth)
                                    )
                                }
                            }
                        }
                    }
                }

                // Selection List / Legend
                Text("Detected Faces: ${detectedFaces.size} (Tap box to select)", modifier = Modifier.padding(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().height(80.dp).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                     // Kept for fallback or quick access
                     detectedFaces.forEachIndexed { index, face ->
                         Button(
                             onClick = {
                                 viewModel.updateFaceSelection(photo, face)
                                 onDismiss()
                             },
                             modifier = Modifier.fillMaxHeight()
                         ) {
                             Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                 Text("Face ${index + 1}")
                                 if (photo.faceX == face.boundingBox.left.toFloat()) {
                                     Text("(Selected)", style = MaterialTheme.typography.labelSmall)
                                 }
                             }
                         }
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun PhotoItem(
    photo: PhotoEntity,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick) // Open Edit/Selection Dialog
    ) {
        Image(
            painter = rememberAsyncImagePainter(model = photo.originalUri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Indicator for processed face
        if (photo.isProcessed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, Color.Green.copy(alpha = 0.5f))
            )
        }

        // ... existing Move/Delete buttons ...
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (!isFirst) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = Color.White)
                }
            }
            if (!isLast) {
                IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Forward", tint = Color.White)
                }
            }
        }
        Row(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
             if (photo.isProcessed) {
                Icon(Icons.Default.Face, "Face Detected", tint = Color.Green, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Delete, "Delete", tint = Color.Red)
            }
        }
    }
}