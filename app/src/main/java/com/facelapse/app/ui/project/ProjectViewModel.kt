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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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

    val project: Flow<ProjectEntity?> = repository.getProjectFlow(projectId)

    val photos = repository.getPhotosForProject(projectId)

    private val _selectedPhotoIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedPhotoIds = _selectedPhotoIds.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    fun toggleSelection(photoId: String) {
        _selectedPhotoIds.update { currentIds ->
            if (photoId in currentIds) {
                currentIds - photoId
            } else {
                currentIds + photoId
            }
        }
    }

    fun clearSelection() {
        _selectedPhotoIds.value = emptySet()
    }

    fun deleteSelected() {
        viewModelScope.launch {
            repository.deletePhotos(_selectedPhotoIds.value.toList())
            clearSelection()
        }
    }

    fun renameProject(name: String) {
        viewModelScope.launch {
            repository.renameProject(projectId, name)
        }
    }

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

    fun updateProjectSettings(fps: Int, exportAsGif: Boolean, isDateOverlayEnabled: Boolean) {
        viewModelScope.launch {
            val currentProject = repository.getProject(projectId)
            if (currentProject != null) {
                repository.updateProject(
                    currentProject.copy(
                        fps = fps,
                        exportAsGif = exportAsGif,
                        isDateOverlayEnabled = isDateOverlayEnabled
                    )
                )
            }
        }
    }

    fun exportVideo(context: Context) {
        viewModelScope.launch {
            _isGenerating.value = true
            val currentPhotos = repository.getPhotosList(projectId)
            val projectEntity = repository.getProject(projectId) ?: return@launch

            val safeName = projectEntity.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())

            // Use project specific setting for on/off
            val isDateOverlayEnabled = projectEntity.isDateOverlayEnabled

            // Use global settings for styling
            val dateFontSize = settingsRepository.dateFontSize.first()
            val dateFormat = settingsRepository.dateFormat.first()

            var success = false
            val outputFile: File

            if (projectEntity.exportAsGif) {
                outputFile = File(context.cacheDir, "facelapse_${safeName}_${timestamp}.gif")
                success = videoGenerator.generateGif(
                    photos = currentPhotos,
                    outputFile = outputFile,
                    isDateOverlayEnabled = isDateOverlayEnabled,
                    dateFontSize = dateFontSize,
                    dateFormat = dateFormat,
                    fps = projectEntity.fps
                )
            } else {
                outputFile = File(context.cacheDir, "facelapse_${safeName}_${timestamp}.mp4")
                success = videoGenerator.generateVideo(
                    photos = currentPhotos,
                    outputFile = outputFile,
                    isDateOverlayEnabled = isDateOverlayEnabled,
                    dateFontSize = dateFontSize,
                    dateFormat = dateFormat,
                    fps = projectEntity.fps
                )
            }

            if (success) {
                shareFile(context, outputFile, if (projectEntity.exportAsGif) "image/gif" else "video/mp4")
            }
            _isGenerating.value = false
        }
    }

    private fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Time-lapse")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun deletePhoto(photo: PhotoEntity) {
        viewModelScope.launch {
            repository.deletePhoto(photo)
        }
    }

}
