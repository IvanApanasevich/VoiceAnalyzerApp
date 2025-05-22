package com.example.voiceanalyzerapp.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Patient::class, TrainingSession::class], version = 2, exportSchema = false) // Increment version
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun trainingSessionDao(): TrainingSessionDao // Add this

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voice_analyzer_database"
                )
                    .fallbackToDestructiveMigration() // For simplicity in dev, replace with proper migration
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}