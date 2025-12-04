package com.facelapse.app.data.repository

import com.facelapse.app.data.local.dao.PhotoDao
import com.facelapse.app.data.local.dao.ProjectDao
import com.facelapse.app.data.local.entity.PhotoEntity
import com.facelapse.app.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val projectDao: ProjectDao,
    private val photoDao: PhotoDao
) {
    fun getAllProjects(): Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    suspend fun getProject(id: String): ProjectEntity? = projectDao.getProjectById(id)

    suspend fun createProject(name: String, fps: Int, exportAsGif: Boolean) {
        projectDao.insertProject(ProjectEntity(name = name, fps = fps, exportAsGif = exportAsGif))
    }

    suspend fun updateProject(project: ProjectEntity) {
        projectDao.updateProject(project)
    }

    suspend fun renameProject(id: String, name: String) {
        projectDao.renameProject(id, name)
    }

    suspend fun deleteProject(project: ProjectEntity) {
        projectDao.deleteProject(project)
    }

    fun getPhotosForProject(projectId: String): Flow<List<PhotoEntity>> = photoDao.getPhotosForProject(projectId)

    suspend fun addPhotos(photos: List<PhotoEntity>) {
        photoDao.insertPhotos(photos)
    }

    suspend fun updatePhoto(photo: PhotoEntity) {
        photoDao.updatePhoto(photo)
    }

    suspend fun deletePhoto(photo: PhotoEntity) {
        photoDao.deletePhoto(photo)
    }

    suspend fun deletePhotos(ids: List<String>) {
        photoDao.deletePhotos(ids)
    }

    suspend fun getPhotosList(projectId: String): List<PhotoEntity> = photoDao.getPhotosForProjectList(projectId)
}
