package com.facelapse.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.facelapse.app.data.local.dao.PhotoDao
import com.facelapse.app.data.local.dao.ProjectDao
import com.facelapse.app.data.local.entity.PhotoEntity
import com.facelapse.app.data.local.entity.ProjectEntity

@Database(entities = [ProjectEntity::class, PhotoEntity::class], version = 1, exportSchema = false)
abstract class FaceLapseDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun photoDao(): PhotoDao
}
