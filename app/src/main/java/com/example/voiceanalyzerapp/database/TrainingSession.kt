package com.example.voiceanalyzerapp.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "training_sessions",
    foreignKeys = [ForeignKey(
        entity = Patient::class,
        parentColumns = ["id"],
        childColumns = ["patientId"],
        onDelete = ForeignKey.CASCADE // If patient is deleted, their sessions are also deleted
    )],
    indices = [Index(value = ["patientId", "letter"], unique = true)]
)
data class TrainingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val patientId: Long,
    val letter: String,
    var currentRecordingPath: String? = null,
    var referenceRecordingPath: String? = null
)