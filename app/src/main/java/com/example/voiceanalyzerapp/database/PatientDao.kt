package com.example.voiceanalyzerapp.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface PatientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatient(patient: Patient)

    @Delete
    suspend fun deletePatient(patient: Patient)

    @Query("SELECT * FROM patients ORDER BY lastName ASC, firstName ASC")
    fun getAllPatients(): LiveData<List<Patient>>

    @Query("SELECT * FROM patients WHERE id = :patientId")
    suspend fun getPatientById(patientId: Long): Patient?
}