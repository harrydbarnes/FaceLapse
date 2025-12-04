package com.facelapse.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.facelapse.app.data.local.entity.PhotoEntity
import com.facelapse.app.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("UPDATE projects SET name = :name WHERE id = :id")
    suspend fun renameProject(id: String, name: String)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE projectId = :projectId ORDER BY sortOrder ASC")
    fun getPhotosForProject(projectId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE projectId = :projectId ORDER BY sortOrder ASC")
    suspend fun getPhotosForProjectList(projectId: String): List<PhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotos(photos: List<PhotoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: PhotoEntity)

    @Delete
    suspend fun deletePhoto(photo: PhotoEntity)

    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun deletePhotos(ids: List<String>)

    @Update
    suspend fun updatePhoto(photo: PhotoEntity)

    @Query("DELETE FROM photos WHERE projectId = :projectId")
    suspend fun deleteAllPhotosForProject(projectId: String)
}
