package com.facelapse.app.di

import android.content.Context
import androidx.room.Room
import com.facelapse.app.data.local.FaceLapseDatabase
import com.facelapse.app.data.local.dao.PhotoDao
import com.facelapse.app.data.local.dao.ProjectDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FaceLapseDatabase {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE projects ADD COLUMN fps INTEGER NOT NULL DEFAULT 10")
                database.execSQL("ALTER TABLE projects ADD COLUMN exportAsGif INTEGER NOT NULL DEFAULT 0")
            }
        }

        return Room.databaseBuilder(
            context,
            FaceLapseDatabase::class.java,
            "facelapse.db"
        )
        .addMigrations(MIGRATION_1_2)
        .build()
    }

    @Provides
    fun provideProjectDao(database: FaceLapseDatabase): ProjectDao = database.projectDao()

    @Provides
    fun providePhotoDao(database: FaceLapseDatabase): PhotoDao = database.photoDao()
}
