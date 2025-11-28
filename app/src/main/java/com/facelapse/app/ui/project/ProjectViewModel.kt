package com.facelapse.app.ui.project

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facelapse.app.data.local.entity.PhotoEntity
import com.facelapse.app.data.local.entity.ProjectEntity
import com.facelapse.app.data.repository.ProjectRepository
import com.facelapse.app.data.repository.SettingsRepository
import com.facelapse.app.domain.FaceDetectorHelper
import com.facelapse.app.domain.VideoGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val faceDetectorHelper: FaceDetectorHelper,
    private val videoGenerator: VideoGenerator,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val projectId: String = checkNotNull(savedStateHandle["projectId"])

    val project: Flow<ProjectEntity?> = kotlinx.coroutines.flow.flow {
        emit(repository.getProject(projectId))
    }

    val photos = repository.getPhotosForProject(projectId)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    fun addPhotos(uris: List<Uri>) {
        viewModelScope.launch {
            val currentCount = repository.getPhotosList(projectId).size
            val newPhotos = uris.mapIndexed { index, uri ->
                PhotoEntity(
                    projectId = projectId,
                    originalUri = uri.toString(),
                    timestamp = System.currentTimeMillis(),
                    sortOrder = currentCount + index
                )
            }
            repository.addPhotos(newPhotos)
        }
    }

    fun processFaces() {
        viewModelScope.launch {
            _isProcessing.value = true
            val currentPhotos = repository.getPhotosList(projectId)
            currentPhotos.forEach { photo ->
                if (!photo.isProcessed) {
                    val face = faceDetectorHelper.detectFace(Uri.parse(photo.originalUri))
                    if (face != null) {
                        val updatedPhoto = photo.copy(
                            isProcessed = true,
                            faceX = face.boundingBox.left.toFloat(),
                            faceY = face.boundingBox.top.toFloat(),
                            faceWidth = face.boundingBox.width().toFloat(),
                            faceHeight = face.boundingBox.height().toFloat(),
                            rotation = 0 // Assuming upright for now
                        )
                        repository.updatePhoto(updatedPhoto)
                    } else {
                         // Mark processed even if no face found to avoid reprocessing loop
                         repository.updatePhoto(photo.copy(isProcessed = true))
                    }
                }
            }
            _isProcessing.value = false
        }
    }

    fun exportVideo(context: Context) {
        viewModelScope.launch {
            _isGenerating.value = true
            val currentPhotos = repository.getPhotosList(projectId)
            val outputFile = File(context.cacheDir, "facelapse_${projectId}.mp4")

            val isDateOverlayEnabled = settingsRepository.isDateOverlayEnabled.first()
            val dateFontSize = settingsRepository.dateFontSize.first()
            val dateFormat = settingsRepository.dateFormat.first()

            val success = videoGenerator.generateVideo(
                photos = currentPhotos,
                outputFile = outputFile,
                isDateOverlayEnabled = isDateOverlayEnabled,
                dateFontSize = dateFontSize,
                dateFormat = dateFormat
            )

            if (success) {
                shareVideo(context, outputFile)
            }
            _isGenerating.value = false
        }
    }

    fun deletePhoto(photo: PhotoEntity) {
        viewModelScope.launch {
            repository.deletePhoto(photo)
        }
    }

    private fun shareVideo(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Time-lapse")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
