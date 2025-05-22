package com.example.voiceanalyzerapp.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface TrainingSessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) // Ignore if patientId+letter combo exists
    suspend fun insertSession(session: TrainingSession): Long // Returns new rowId or -1 if ignored

    @Update
    suspend fun updateSession(session: TrainingSession)

    @Query("SELECT * FROM training_sessions WHERE patientId = :patientId AND letter = :letter LIMIT 1")
    fun getSession(patientId: Long, letter: String): LiveData<TrainingSession?>

    @Query("SELECT * FROM training_sessions WHERE patientId = :patientId AND letter = :letter LIMIT 1")
    suspend fun getSessionSuspending(patientId: Long, letter: String): TrainingSession?


    @Query("SELECT * FROM training_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): TrainingSession?

    // Specific updates can be more efficient
    @Query("UPDATE training_sessions SET currentRecordingPath = :path WHERE id = :sessionId")
    suspend fun updateCurrentRecordingPath(sessionId: Long, path: String?)

    @Query("UPDATE training_sessions SET referenceRecordingPath = :path WHERE id = :sessionId")
    suspend fun updateReferenceRecordingPath(sessionId: Long, path: String?)
}