package com.facelapse.app.ui.project

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.rememberAsyncImagePainter
import com.facelapse.app.data.local.entity.PhotoEntity
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext

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

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPhotos(uris)
        }
    }

    var showMenu by remember { mutableStateOf(false) }

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
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Date Overlay")
                                    Switch(
                                        checked = project?.isDateOverlayEnabled == true,
                                        onCheckedChange = {
                                            viewModel.toggleDateOverlay(it)
                                            // Keep menu open? No, standard behavior is close.
                                        },
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            },
                            onClick = {
                                val current = project?.isDateOverlayEnabled == true
                                viewModel.toggleDateOverlay(!current)
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
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
                            onDelete = { viewModel.deletePhoto(photo) }
                        )
                    }
                }
            }

            if (isProcessing || isGenerating) {
                 Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
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
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier.aspectRatio(1f)
    ) {
        Image(
            painter = rememberAsyncImagePainter(model = photo.originalUri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (!isFirst) {
                IconButton(
                    onClick = onMoveUp,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft, // Used as "Move Backward"
                        contentDescription = "Move Backward",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
            if (!isLast) {
                IconButton(
                    onClick = onMoveDown,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, // Used as "Move Forward"
                        contentDescription = "Move Forward",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
        ) {
             if (photo.isProcessed) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = "Face Detected",
                    tint = androidx.compose.ui.graphics.Color.Green,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = androidx.compose.ui.graphics.Color.Red
                )
            }
        }
    }
}
