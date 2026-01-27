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

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Create new table with fps as REAL
                database.execSQL("""
                    CREATE TABLE projects_new (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isDateOverlayEnabled INTEGER NOT NULL,
                        fps REAL NOT NULL,
                        exportAsGif INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """)

                // 2. Copy data, casting fps to REAL
                database.execSQL("""
                    INSERT INTO projects_new (id, name, createdAt, isDateOverlayEnabled, fps, exportAsGif)
                    SELECT id, name, createdAt, isDateOverlayEnabled, CAST(fps AS REAL), exportAsGif
                    FROM projects
                """)

                // 3. Drop old table
                database.execSQL("DROP TABLE projects")

                // 4. Rename new table
                database.execSQL("ALTER TABLE projects_new RENAME TO projects")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE projects ADD COLUMN faceScale REAL NOT NULL DEFAULT 0.4")
                database.execSQL("ALTER TABLE projects ADD COLUMN aspectRatio TEXT NOT NULL DEFAULT '9:16'")
            }
        }

        return Room.databaseBuilder(
            context,
            FaceLapseDatabase::class.java,
            "facelapse.db"
        )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()
    }

    @Provides
    fun provideProjectDao(database: FaceLapseDatabase): ProjectDao = database.projectDao()

    @Provides
    fun providePhotoDao(database: FaceLapseDatabase): PhotoDao = database.photoDao()
}
