package com.facelapse.app.ui.project

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.facelapse.app.R
import com.facelapse.app.domain.FaceDetectionResult
import com.facelapse.app.domain.model.Photo
import kotlinx.coroutines.launch

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
                        Text(stringResource(R.string.face_audit_title))
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
                Text(stringResource(R.string.face_audit_no_photos))
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
                        Text(stringResource(R.string.action_previous))
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        enabled = pagerState.currentPage < photos.size - 1
                    ) {
                        Text(stringResource(R.string.action_next))
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
    var detectionResult by remember { mutableStateOf<FaceDetectionResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(photo.id) {
        isLoading = true
        detectionResult = viewModel.getFacesForPhoto(photo)
        isLoading = false
    }

    val detectedFaces = detectionResult?.faces ?: emptyList()

    // Determine currently selected face based on DB values
    val currentSelectedFace = remember(detectedFaces, photo) {
        findMatchingFace(detectedFaces, photo)
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            FaceOverlay(
                photo = photo,
                faces = detectedFaces,
                selectedFace = currentSelectedFace,
                onFaceClick = { clickedFace ->
                    viewModel.updateFaceSelection(photo, clickedFace)
                },
                detectionWidth = detectionResult?.width ?: 0,
                detectionHeight = detectionResult?.height ?: 0,
                modifier = Modifier.fillMaxSize()
            )

            if (detectedFaces.isEmpty()) {
                 Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium)
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                         Icon(Icons.Default.Warning, "Warning", tint = MaterialTheme.colorScheme.onErrorContainer)
                         Spacer(modifier = Modifier.width(8.dp))
                         Text(stringResource(R.string.face_audit_no_faces_detected), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            } else if (currentSelectedFace == null) {
                 Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium)
                        .padding(8.dp)
                ) {
                     Text(stringResource(R.string.face_audit_tap_instruction), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}
