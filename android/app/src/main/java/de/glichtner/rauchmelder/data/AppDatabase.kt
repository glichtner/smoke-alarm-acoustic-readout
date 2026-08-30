package de.glichtner.rauchmelder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Detector::class, Inspection::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun detectorDao(): DetectorDao
    abstract fun inspectionDao(): InspectionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rauchmelder.db",
                )
                    // pre-release: no migrations, a schema change recreates the database
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
