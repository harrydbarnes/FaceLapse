package com.facelapse.app.ui.project

import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facelapse.app.data.repository.ProjectRepository
import com.facelapse.app.data.repository.SettingsRepository
import com.facelapse.app.domain.FaceDetectionResult
import com.facelapse.app.domain.FaceDetectorHelper
import com.facelapse.app.domain.FaceRecognitionHelper
import com.facelapse.app.domain.ImageLoader
import com.facelapse.app.domain.VideoGenerator
import com.facelapse.app.domain.model.Photo
import com.facelapse.app.domain.model.Project
import com.google.mlkit.vision.face.Face
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.hypot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val faceDetectorHelper: FaceDetectorHelper,
    private val faceRecognitionHelper: FaceRecognitionHelper,
    private val videoGenerator: VideoGenerator,
    private val imageLoader: ImageLoader,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private val filenameTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val SAFE_FILENAME_REGEX = Regex("[^a-zA-Z0-9.-]")
        private const val GIF_SAFE_SHORTEST_SIDE_PX = 480f
    }

    private val _projectId = MutableStateFlow<String?>(null)

    private val projectId: String?
        get() = _projectId.value

    fun setProjectId(id: String) {
        _projectId.value = id
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val project: StateFlow<Project?> = _projectId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.getProjectFlow(id)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = null
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val photos: Flow<List<Photo>> = _projectId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.getPhotosForProject(id)
    }

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
            val id = projectId ?: return@launch
            repository.renameProject(id, name)
        }
    }

    fun addPhotos(uris: List<Uri>) {
        viewModelScope.launch {
            val pid = projectId ?: return@launch
            val currentCount = repository.getPhotosList(pid).size
            val newPhotos = withContext(Dispatchers.IO) {
                uris.mapIndexed { index, uri ->
                    async {
                        val exifData = imageLoader.getExifData(uri) ?: run {
                            Log.w("ProjectViewModel", "Could not load EXIF data for URI: $uri")
                            return@async null
                        }
                        Photo(
                            id = UUID.randomUUID().toString(),
                            projectId = pid,
                            originalUri = uri.toString(),
                            timestamp = exifData.timestamp,
                            sortOrder = currentCount + index,
                            isProcessed = false,
                            faceX = null,
                            faceY = null,
                            faceWidth = null,
                            faceHeight = null,
                            rotation = exifData.rotation
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            repository.addPhotos(newPhotos)
        }
    }

    fun movePhoto(photo: Photo, moveUp: Boolean) {
        viewModelScope.launch {
            val id = projectId ?: return@launch
            val currentPhotos = repository.getPhotosList(id).toMutableList()
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

    suspend fun getFacesForPhoto(photo: Photo): FaceDetectionResult {
        return faceDetectorHelper.detectFaces(Uri.parse(photo.originalUri))
    }

    fun updateFaceSelection(photo: Photo, face: Face) {
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

    fun setTargetPerson(photo: Photo, face: Face) {
        viewModelScope.launch {
            _isProcessing.value = true
            withContext(Dispatchers.Default) {
                try {
                    val loaded = imageLoader.loadOptimizedBitmap(Uri.parse(photo.originalUri), 1024, 1024)
                    if (loaded != null) {
                        try {
                            val embedding = faceRecognitionHelper.getFaceEmbedding(loaded.bitmap, face)
                            if (embedding != null) {
                                val id = projectId
                                if (id == null) {
                                    _isProcessing.value = false
                                    return@withContext
                                }
                                val currentProject = repository.getProject(id)
                                if (currentProject == null) {
                                    _isProcessing.value = false
                                    return@withContext
                                }
                                repository.updateProject(currentProject.copy(targetEmbedding = embedding))
                                processFacesInternal()
                            }
                        } finally {
                            if (!loaded.bitmap.isRecycled) loaded.bitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ProjectViewModel", "Error setting target person", e)
                } finally {
                    _isProcessing.value = false
                }
            }
        }
    }

    fun processFaces() {
        viewModelScope.launch {
            processFacesInternal()
        }
    }

    private suspend fun processFacesInternal() {
        _isProcessing.value = true
        val id = projectId
        if (id == null) {
            _isProcessing.value = false
            return
        }

        withContext(Dispatchers.Default) {
            try {
                val project = repository.getProject(id)
                val targetEmbedding = project?.targetEmbedding
                val currentPhotos = repository.getPhotosList(id).sortedBy { it.sortOrder }

                if (targetEmbedding != null) {
                    processFacesWithTarget(currentPhotos, targetEmbedding)
                } else {
                    processFacesSpatial(currentPhotos)
                }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun processFacesWithTarget(photos: List<Photo>, targetEmbedding: FloatArray) {
        withContext(Dispatchers.IO) {
            val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_FACE_PROCESSING)
            val jobs = photos.map { photo ->
                async {
                    semaphore.acquire()
                    try {
                        val loaded = imageLoader.loadOptimizedBitmap(Uri.parse(photo.originalUri), 1024, 1024)
                        if (loaded != null) {
                        try {
                            val result = faceDetectorHelper.detectFaces(loaded.bitmap)
                            val faces = result.faces

                            if (result.width == 0 || result.height == 0) {
                                repository.updatePhoto(photo.copy(isProcessed = false))
                                return@async
                            }

                            var bestCandidate: Face? = null
                            var bestScore = -1f

                            for (face in faces) {
                                val embed = faceRecognitionHelper.getFaceEmbedding(loaded.bitmap, face)
                                if (embed != null) {
                                    val score = faceRecognitionHelper.calculateCosineSimilarity(embed, targetEmbedding)
                                    if (score > bestScore) {
                                        bestScore = score
                                        bestCandidate = face
                                    }
                                }
                            }

                            val bestFace = if (bestScore > FaceRecognitionHelper.THRESHOLD) bestCandidate else null

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
                                repository.updatePhoto(photo.copy(isProcessed = false))
                            }
                        } finally {
                            if (!loaded.bitmap.isRecycled) loaded.bitmap.recycle()
                        }
                    } else {
                        // Failed to load, keep as unprocessed
                        repository.updatePhoto(photo.copy(isProcessed = false))
                    }
                    } finally {
                        semaphore.release()
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    private suspend fun processFacesSpatial(photos: List<Photo>) {
        var previousFaceCenter: PointF? = null

        for (photo in photos) {
            val result = faceDetectorHelper.detectFaces(Uri.parse(photo.originalUri))
            val faces = result.faces
            val width = result.width
            val height = result.height

            if (width == 0 || height == 0) {
                repository.updatePhoto(photo.copy(isProcessed = false))
                continue
            }
            if (photo.isProcessed) {
                val fx = photo.faceX
                val fy = photo.faceY
                val fw = photo.faceWidth
                val fh = photo.faceHeight

                previousFaceCenter = if (fx != null && fy != null && fw != null && fh != null) {
                    calculateNormalizedCenter(fx, fy, fw, fh, width, height)
                } else {
                    null
                }
            } else {
                val bestFace = if (previousFaceCenter == null) {
                    faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                } else {
                    val prevCenter = checkNotNull(previousFaceCenter)
                    faces.minByOrNull { face ->
                        val center = calculateNormalizedCenter(
                            face.boundingBox.left.toFloat(),
                            face.boundingBox.top.toFloat(),
                            face.boundingBox.width().toFloat(),
                            face.boundingBox.height().toFloat(),
                            width,
                            height
                        )
                        hypot(center.x - prevCenter.x, center.y - prevCenter.y)
                    }
                }

                if (bestFace != null) {
                    val updatedPhoto = photo.copy(
                        isProcessed = true,
                        faceX = bestFace.boundingBox.left.toFloat(),
                        faceY = bestFace.boundingBox.top.toFloat(),
                        faceWidth = bestFace.boundingBox.width().toFloat(),
                        faceHeight = bestFace.boundingBox.height().toFloat()
                    )
                    repository.updatePhoto(updatedPhoto)

                    previousFaceCenter = calculateNormalizedCenter(
                        bestFace.boundingBox.left.toFloat(),
                        bestFace.boundingBox.top.toFloat(),
                        bestFace.boundingBox.width().toFloat(),
                        bestFace.boundingBox.height().toFloat(),
                        width,
                        height
                    )
                } else {
                    if (!photo.isProcessed) {
                        repository.updatePhoto(photo.copy(isProcessed = false))
                    }
                }
            }
        }
    }

    private fun calculateNormalizedCenter(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        imageWidth: Int,
        imageHeight: Int
    ): PointF {
        val centerX = x + w / 2f
        val centerY = y + h / 2f
        return PointF(centerX / imageWidth, centerY / imageHeight)
    }

    fun updateProjectSettings(fps: Float, exportAsGif: Boolean, isDateOverlayEnabled: Boolean, faceScale: Float, aspectRatio: String) {
        viewModelScope.launch {
            updateProjectInternal(fps, exportAsGif, isDateOverlayEnabled, faceScale, aspectRatio)
        }
    }

    /**
     * Atomically saves settings and then triggers export to prevent race conditions where
     * the export uses stale settings.
     */
    fun saveAndExport(context: Context, fps: Float, exportAsGif: Boolean, isDateOverlayEnabled: Boolean, faceScale: Float, aspectRatio: String, audioUri: Uri? = null) {
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                val updatedProject = updateProjectInternal(fps, exportAsGif, isDateOverlayEnabled, faceScale, aspectRatio)
                if (updatedProject != null) {
                    // exportVideoInternal will set _isGenerating to false in its own finally block.
                    exportVideoInternal(context, updatedProject, audioUri)
                } else {
                    _isGenerating.value = false
                }
            } catch (e: Exception) {
                // Ensure loading state is reset on any failure.
                _isGenerating.value = false
            }
        }
    }

    private suspend fun updateProjectInternal(fps: Float, exportAsGif: Boolean, isDateOverlayEnabled: Boolean, faceScale: Float, aspectRatio: String): Project? {
        val id = projectId ?: return null
        val currentProject = repository.getProject(id) ?: return null
        val updatedProject = currentProject.copy(
            fps = fps,
            exportAsGif = exportAsGif,
            isDateOverlayEnabled = isDateOverlayEnabled,
            faceScale = faceScale,
            aspectRatio = aspectRatio
        )
        repository.updateProject(updatedProject)
        return updatedProject
    }

    fun exportVideo(context: Context, audioUri: Uri? = null) {
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                val projectVal = project.value
                if (projectVal != null) {
                    exportVideoInternal(context, projectVal, audioUri)
                } else {
                    _isGenerating.value = false
                }
            } catch (e: Exception) {
                _isGenerating.value = false
            }
        }
    }

    private suspend fun exportVideoInternal(context: Context, project: Project, audioUri: Uri?) {
        _isGenerating.value = true
        try {
            val id = projectId
            if (id == null) {
                _isGenerating.value = false
                return
            }
            val currentPhotos = repository.getPhotosList(id)

            val safeName = project.name.replace(SAFE_FILENAME_REGEX, "_")
            val timestamp = LocalDateTime.now().format(filenameTimestampFormatter)

            val isDateOverlayEnabled = project.isDateOverlayEnabled
            val dateFontSize = settingsRepository.dateFontSize.first()
            val dateFormat = settingsRepository.dateFormat.first()

            val extension: String
            val mimeType: String
            val generator: suspend (File) -> Boolean

            val fullDims = Project.getDimensionsForAspectRatio(project.aspectRatio)
            val (targetWidth, targetHeight) = if (project.exportAsGif) {
                val scale = GIF_SAFE_SHORTEST_SIDE_PX / kotlin.math.min(fullDims.first, fullDims.second)
                (fullDims.first * scale).toInt() to (fullDims.second * scale).toInt()
            } else {
                fullDims
            }

            if (project.exportAsGif) {
                extension = "gif"
                mimeType = "image/gif"
                generator = { file ->
                    videoGenerator.generateGif(
                        photos = currentPhotos,
                        outputFile = file,
                        isDateOverlayEnabled = isDateOverlayEnabled,
                        dateFontSize = dateFontSize,
                        dateFormat = dateFormat,
                        fps = project.fps,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        faceScale = project.faceScale
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
                        fps = project.fps,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        audioUri = audioUri,
                        faceScale = project.faceScale
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

    fun deletePhoto(photo: Photo) {
        viewModelScope.launch {
            repository.deletePhoto(photo)
        }
    }

    override fun onCleared() {
        super.onCleared()
        kotlinx.coroutines.runBlocking {
            faceRecognitionHelper.close()
        }
    }
}
