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
import com.google.mlkit.vision.face.Face
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

    fun movePhoto(photo: PhotoEntity, moveUp: Boolean) {
        viewModelScope.launch {
            val currentPhotos = repository.getPhotosList(projectId).toMutableList()
            val index = currentPhotos.indexOfFirst { it.id == photo.id }
            if (index == -1) return@launch

            val newIndex = if (moveUp) index - 1 else index + 1
            if (newIndex in 0 until currentPhotos.size) {
                val otherPhoto = currentPhotos[newIndex]

                // Swap sort orders
                val updatedPhoto = photo.copy(sortOrder = otherPhoto.sortOrder)
                val updatedOther = otherPhoto.copy(sortOrder = photo.sortOrder)

                // Update in DB
                repository.updatePhoto(updatedPhoto)
                repository.updatePhoto(updatedOther)
            }
        }
    }

    // Helper for UI to get faces for a specific photo on demand
    suspend fun getFacesForPhoto(photo: PhotoEntity): List<Face> {
        return faceDetectorHelper.detectFaces(Uri.parse(photo.originalUri))
    }

    fun updateFaceSelection(photo: PhotoEntity, face: Face) {
        viewModelScope.launch {
            val updatedPhoto = photo.copy(
                isProcessed = true,
                faceX = face.boundingBox.left.toFloat(),
                faceY = face.boundingBox.top.toFloat(),
                faceWidth = face.boundingBox.width().toFloat(),
                faceHeight = face.boundingBox.height().toFloat()
            )
            repository.updatePhoto(updatedPhoto)
        }
    }

    fun processFaces() {
        viewModelScope.launch {
            _isProcessing.value = true
            val currentPhotos = repository.getPhotosList(projectId)
            currentPhotos.forEach { photo ->
                if (!photo.isProcessed) {
                    // Get ALL faces
                    val faces = faceDetectorHelper.detectFaces(Uri.parse(photo.originalUri))

                    // Default to largest face initially
                    val bestFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }

                    if (bestFace != null) {
                        val updatedPhoto = photo.copy(
                            isProcessed = true,
                            faceX = bestFace.boundingBox.left.toFloat(),
                            faceY = bestFace.boundingBox.top.toFloat(),
                            faceWidth = bestFace.boundingBox.width().toFloat(),
                            faceHeight = bestFace.boundingBox.height().toFloat()
                        )
                        repository.updatePhoto(updatedPhoto)
                    } else {
                         // Mark processed to stop auto-retry
                         repository.updatePhoto(photo.copy(isProcessed = true))
                    }
                }
            }
            _isProcessing.value = false
        }
    }

    fun toggleDateOverlay(enabled: Boolean) {
        viewModelScope.launch {
            val currentProject = repository.getProject(projectId)
            if (currentProject != null) {
                repository.updateProject(currentProject.copy(isDateOverlayEnabled = enabled))
            }
        }
    }

    fun exportVideo(context: Context) {
        viewModelScope.launch {
            _isGenerating.value = true
            val currentPhotos = repository.getPhotosList(projectId)
            val outputFile = File(context.cacheDir, "facelapse_${projectId}.mp4")

            // Use project specific setting for on/off
            val projectEntity = repository.getProject(projectId)
            val isDateOverlayEnabled = projectEntity?.isDateOverlayEnabled ?: true

            // Use global settings for styling
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
