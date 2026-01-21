package com.facelapse.app.data.repository

import com.facelapse.app.data.local.dao.PhotoDao
import com.facelapse.app.data.local.dao.ProjectDao
import com.facelapse.app.data.local.entity.ProjectEntity
import com.facelapse.app.domain.mapper.toDomain
import com.facelapse.app.domain.mapper.toEntity
import com.facelapse.app.domain.model.Photo
import com.facelapse.app.domain.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val projectDao: ProjectDao,
    private val photoDao: PhotoDao
) {
    fun getAllProjects(): Flow<List<Project>> = projectDao.getAllProjects()
        .map { list -> list.map { it.toDomain() } }

    suspend fun getProject(id: String): Project? = projectDao.getProjectById(id)?.toDomain()

    fun getProjectFlow(id: String): Flow<Project?> = projectDao.getProjectFlow(id)
        .map { it?.toDomain() }

    suspend fun createProject(name: String, fps: Float, exportAsGif: Boolean) {
        projectDao.insertProject(Project(name = name, fps = fps, exportAsGif = exportAsGif).toEntity())
    }

    suspend fun updateProject(project: Project) {
        projectDao.updateProject(project.toEntity())
    }

    suspend fun renameProject(id: String, name: String) {
        projectDao.renameProject(id, name)
    }

    suspend fun deleteProject(project: Project) {
        projectDao.deleteProject(project.toEntity())
    }

    suspend fun updateAllProjectsSettings(fps: Float, exportAsGif: Boolean, isDateOverlayEnabled: Boolean) {
        projectDao.updateAllProjectsSettings(fps, exportAsGif, isDateOverlayEnabled)
    }

    fun getPhotosForProject(projectId: String): Flow<List<Photo>> = photoDao.getPhotosForProject(projectId)
        .map { list -> list.map { it.toDomain() } }

    suspend fun addPhotos(photos: List<Photo>) {
        photoDao.insertPhotos(photos.map { it.toEntity() })
    }

    suspend fun updatePhoto(photo: Photo) {
        photoDao.updatePhoto(photo.toEntity())
    }

    suspend fun deletePhoto(photo: Photo) {
        photoDao.deletePhoto(photo.toEntity())
    }

    suspend fun deletePhotos(ids: List<String>) {
        photoDao.deletePhotos(ids)
    }

    suspend fun getPhotosList(projectId: String): List<Photo> = photoDao.getPhotosForProjectList(projectId)
        .map { it.toDomain() }
}
