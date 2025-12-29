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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    companion object {
        private val timestampFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val SAFE_FILENAME_REGEX = Regex("[^a-zA-Z0-9.-]")
    }

    private val projectId: String = checkNotNull(savedStateHandle["projectId"])

    val project: StateFlow<ProjectEntity?> = repository.getProjectFlow(projectId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null
        )

    val photos = repository.getPhotosForProject(projectId)

    private val _selectedPhotoIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedPhotoIds = _selectedPhotoIds.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult = _exportResult.asStateFlow()

    fun clearExportResult() {
        _exportResult.value = null
    }

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

    fun updateProjectSettings(fps: Float, exportAsGif: Boolean, isDateOverlayEnabled: Boolean) {
        viewModelScope.launch {
            updateProjectInternal(fps, exportAsGif, isDateOverlayEnabled)
        }
    }

    /**
     * Atomically saves settings and then triggers export to prevent race conditions where
     * the export uses stale settings.
     */
    fun saveAndExport(context: Context, fps: Float, exportAsGif: Boolean, isDateOverlayEnabled: Boolean) {
        viewModelScope.launch {
            val updatedProject = updateProjectInternal(fps, exportAsGif, isDateOverlayEnabled)
            if (updatedProject != null) {
                // Proceed with export using the explicitly provided settings values
                exportVideoInternal(context, updatedProject)
            }
        }
    }

    private suspend fun updateProjectInternal(fps: Float, exportAsGif: Boolean, isDateOverlayEnabled: Boolean): ProjectEntity? {
        val currentProject = repository.getProject(projectId) ?: return null
        val updatedProject = currentProject.copy(
            fps = fps,
            exportAsGif = exportAsGif,
            isDateOverlayEnabled = isDateOverlayEnabled
        )
        repository.updateProject(updatedProject)
        return updatedProject
    }

    fun exportVideo(context: Context) {
        viewModelScope.launch {
            val projectEntity = repository.getProject(projectId) ?: return@launch
            exportVideoInternal(context, projectEntity)
        }
    }

    private suspend fun exportVideoInternal(context: Context, projectEntity: ProjectEntity) {
        _isGenerating.value = true
        try {
            val currentPhotos = repository.getPhotosList(projectId)

            val safeName = projectEntity.name.replace(SAFE_FILENAME_REGEX, "_")
            val timestamp = timestampFormatter.format(java.time.LocalDateTime.now())

            // Use project specific setting for on/off
            val isDateOverlayEnabled = projectEntity.isDateOverlayEnabled

            // Use global settings for styling
            val dateFontSize = settingsRepository.dateFontSize.first()
            val dateFormat = settingsRepository.dateFormat.first()

            val extension: String
            val mimeType: String
            val generator: suspend (File) -> Boolean

            if (projectEntity.exportAsGif) {
                extension = "gif"
                mimeType = "image/gif"
                generator = { file ->
                    videoGenerator.generateGif(
                        photos = currentPhotos,
                        outputFile = file,
                        isDateOverlayEnabled = isDateOverlayEnabled,
                        dateFontSize = dateFontSize,
                        dateFormat = dateFormat,
                        fps = projectEntity.fps
                    )
                }
            } else {
                extension = "mp4"
                mimeType = "video/mp4"
                generator = { file ->
                    videoGenerator.generateVideo(
                        photos = currentPhotos,
                        outputFile = file,
                        isDateOverlayEnabled = isDateOverlayEnabled,
                        dateFontSize = dateFontSize,
                        dateFormat = dateFormat,
                        fps = projectEntity.fps
                    )
                }
            }

            val outputFile = File(context.cacheDir, "facelapse_${safeName}_${timestamp}.$extension")
            val success = generator(outputFile)

            if (success) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", outputFile)
                _exportResult.value = ExportResult(outputFile, uri, mimeType)
            }
        } finally {
            _isGenerating.value = false
        }
    }

    fun shareFile(context: Context, uri: Uri, mimeType: String) {
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
