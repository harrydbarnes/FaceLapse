package com.facelapse.app.ui.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import android.os.Build
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.facelapse.app.R
import com.facelapse.app.data.local.entity.PhotoEntity
import com.facelapse.app.data.local.entity.ProjectEntity
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    viewModel: ProjectViewModel,
    onBackClick: () -> Unit,
    onNavigateToFaceAudit: (String) -> Unit = {}
) {
    val project by viewModel.project.collectAsState(initial = null)
    val photos by viewModel.photos.collectAsState(initial = emptyList())
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val selectedPhotoIds by viewModel.selectedPhotoIds.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addPhotos(uris)
    }

    var showMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // State for Face Selection Dialog
    var selectedPhotoForEditing by remember { mutableStateOf<PhotoEntity?>(null) }

    // Logic for Floating Button: Show "Detect" if photos exist but none are processed
    val showDetectFab = photos.isNotEmpty() && photos.none { it.isProcessed }

    Scaffold(
        topBar = {
            if (selectedPhotoIds.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selectedPhotoIds.size} Selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.deleteSelected() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = project?.name ?: "Project",
                            modifier = Modifier.clickable { showRenameDialog = true }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Standard Top Bar Actions
                        ActionTooltip(tooltip = stringResource(R.string.action_align_faces)) {
                            IconButton(
                                onClick = { viewModel.processFaces() },
                                enabled = !isProcessing && photos.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.Face,
                                    contentDescription = stringResource(R.string.action_align_faces)
                                )
                            }
                        }
                        // Face Audit
                        ActionTooltip(tooltip = stringResource(R.string.action_view_detected_faces)) {
                            IconButton(
                                onClick = { project?.id?.let { onNavigateToFaceAudit(it) } },
                                enabled = !isProcessing && photos.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.AccountBox,
                                    contentDescription = stringResource(R.string.action_view_detected_faces)
                                )
                            }
                        }
                        ActionTooltip(tooltip = stringResource(R.string.action_project_settings)) {
                            IconButton(
                                onClick = { showSettingsDialog = true },
                                enabled = !isGenerating && !isProcessing && photos.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.action_project_settings)
                                )
                            }
                        }
                        ActionTooltip(tooltip = stringResource(R.string.action_share_project)) {
                            IconButton(
                                onClick = { viewModel.exportVideo(context) },
                                enabled = !isGenerating && !isProcessing && photos.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = stringResource(R.string.action_share_project)
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (showDetectFab) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.processFaces() },
                    icon = { Icon(Icons.Default.Face, stringResource(R.string.action_detect_faces)) },
                    text = { Text(stringResource(R.string.action_detect_faces)) }
                )
            } else {
                FloatingActionButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add_photos))
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (photos.isEmpty()) {
                EmptyPhotosState(modifier = Modifier.fillMaxSize())
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
                        val isSelected = selectedPhotoIds.contains(photo.id)
                        PhotoItem(
                            photo = photo,
                            isFirst = index == 0,
                            isLast = index == photos.lastIndex,
                            isSelected = isSelected,
                            inSelectionMode = selectedPhotoIds.isNotEmpty(),
                            onMoveUp = { viewModel.movePhoto(photo, true) },
                            onMoveDown = { viewModel.movePhoto(photo, false) },
                            onDelete = { viewModel.deletePhoto(photo) },
                            onToggleSelection = { viewModel.toggleSelection(photo.id) },
                            onClick = { selectedPhotoForEditing = photo }
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

    if (showSettingsDialog) {
        project?.let { p ->
            ProjectSettingsDialog(
                project = p,
                onDismiss = { showSettingsDialog = false },
                onSave = { fps, isGif, isOverlay ->
                    viewModel.updateProjectSettings(fps, isGif, isOverlay)
                },
                onExport = {
                    viewModel.exportVideo(context)
                }
            )
        }
    }

    if (showRenameDialog) {
        project?.let { p ->
            RenameProjectDialog(
                currentName = p.name,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    viewModel.renameProject(newName)
                }
            )
        }
    }

    // Preview Dialog
    exportResult?.let { result ->
        PreviewDialog(
            result = result,
            onDismiss = { viewModel.clearExportResult() },
            onShare = {
                viewModel.shareFile(context, result.uri, result.mimeType)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionTooltip(
    tooltip: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(tooltip)
            }
        },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        content()
    }
}

@Composable
fun ProjectSettingsDialog(
    project: ProjectEntity,
    onDismiss: () -> Unit,
    onSave: (Int, Boolean, Boolean) -> Unit,
    onExport: () -> Unit
) {
    var fps: Float by remember { mutableStateOf(project.fps.toFloat()) }
    var exportAsGif: Boolean by remember { mutableStateOf(project.exportAsGif) }
    var isDateOverlayEnabled: Boolean by remember { mutableStateOf(project.isDateOverlayEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Animation Speed
                Column {
                    Text("Animation Speed: ${fps.toInt()} FPS")
                    Slider(
                        value = fps,
                        onValueChange = { fps = it },
                        valueRange = 1f..60f,
                        steps = 59
                    )
                }

                // Export Format
                Column {
                    Text("Export Format")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
                                .clip(MaterialTheme.shapes.small)
                                .selectable(
                                    selected = !exportAsGif,
                                    onClick = { exportAsGif = false },
                                    role = Role.RadioButton
                                )
                        ) {
                            RadioButton(
                                selected = !exportAsGif,
                                onClick = null
                            )
                            Text("Video (MP4)")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
                                .clip(MaterialTheme.shapes.small)
                                .selectable(
                                    selected = exportAsGif,
                                    onClick = { exportAsGif = true },
                                    role = Role.RadioButton
                                )
                        ) {
                            RadioButton(
                                selected = exportAsGif,
                                onClick = null
                            )
                            Text("GIF")
                        }
                    }
                }

                // Date Overlay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .toggleable(
                            value = isDateOverlayEnabled,
                            onValueChange = { isDateOverlayEnabled = it },
                            role = Role.Switch
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Date Overlay", modifier = Modifier.padding(start = 8.dp))
                    Switch(
                        checked = isDateOverlayEnabled,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun performSave() = onSave(fps.toInt(), exportAsGif, isDateOverlayEnabled)

                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(onClick = {
                    performSave()
                    onDismiss()
                }) {
                    Text("Save")
                }
                Button(onClick = {
                    performSave()
                    onExport()
                    onDismiss()
                }) {
                    Text("Share")
                }
            }
        },
        dismissButton = null
    )
}

@Composable
fun RenameProjectDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Project") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Project Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(text)
                onDismiss()
            }) { Text("Rename") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PreviewDialog(
    result: ExportResult,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (result.mimeType.startsWith("video/")) {
                        val context = LocalContext.current
                        val videoView = remember { android.widget.VideoView(context) }
                        AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize())
                        DisposableEffect(result.uri) {
                            videoView.setVideoURI(result.uri)
                            videoView.setOnCompletionListener { it.start() }
                            videoView.start()
                            onDispose {
                                videoView.stopPlayback()
                            }
                        }
                    } else {
                        // GIF
                         Image(
                            painter = rememberAsyncImagePainter(
                                ImageRequest.Builder(LocalContext.current)
                                    .data(result.uri)
                                    .decoderFactory(
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                            ImageDecoderDecoder.Factory()
                                        } else {
                                            GifDecoder.Factory()
                                        }
                                    )
                                    .build()
                            ),
                            contentDescription = "Preview",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                         colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Close")
                    }
                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPhotosState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Face,
            contentDescription = stringResource(R.string.empty_photos_icon_description),
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_photos_state_title),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_photos_state_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
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

    // Track locally selected face for UI feedback before saving
    var selectedFace by remember { mutableStateOf<Face?>(null) }

    // Load faces when dialog opens
    LaunchedEffect(photo) {
        val faces = viewModel.getFacesForPhoto(photo)
        detectedFaces = faces
        selectedFace = findMatchingFace(faces, photo)
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
                                                selectedFace = clickedFace
                                            }
                                        }
                                    }
                            ) {
                                mappedFaces.forEach { (face, rect) ->
                                    // Highlight if selected
                                    val isSelected = face == selectedFace

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
                if (detectedFaces.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No faces detected.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(
                        text = "Detected Faces: ${detectedFaces.size} (Tap box or button to select)",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Kept for fallback or quick access
                        detectedFaces.forEachIndexed { index, face ->
                            val isSelected = face == selectedFace

                            Button(
                                onClick = {
                                    selectedFace = face
                                },
                                modifier = Modifier.fillMaxHeight(),
                                colors = if (isSelected) ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ) else ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Face ${index + 1}")
                                    if (isSelected) {
                                        Text("(Selected)", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            selectedFace?.let { viewModel.updateFaceSelection(photo, it) }
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = selectedFace != null
                    ) {
                        Text("Save Selection")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoItem(
    photo: PhotoEntity,
    isFirst: Boolean,
    isLast: Boolean,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = {
                    if (inSelectionMode) {
                        onToggleSelection()
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    onToggleSelection()
                }
            )
            .border(
                width = if (isSelected) 4.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
            )
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

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(24.dp)
            )
        }

        if (!inSelectionMode) {
            // ... existing Move/Delete buttons ...
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
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
            Row(modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)) {
                if (photo.isProcessed) {
                    Icon(
                        Icons.Default.Face,
                        "Face Detected",
                        tint = Color.Green,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Delete, "Delete", tint = Color.Red)
                }
            }
        }
    }
}
