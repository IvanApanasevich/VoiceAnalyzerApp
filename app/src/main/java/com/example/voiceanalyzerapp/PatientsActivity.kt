package com.example.voiceanalyzerapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voiceanalyzerapp.database.AppDatabase
import com.example.voiceanalyzerapp.database.Patient
import com.example.voiceanalyzerapp.databinding.ActivityPatientsBinding
import com.example.voiceanalyzerapp.databinding.DialogAddPatientBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class PatientsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPatientsBinding
    private lateinit var patientAdapter: PatientAdapter
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var currentSelectedPatient: Patient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarPatients)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarPatients.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        setupRecyclerView()
        observePatients()

        binding.btnAddPatient.setOnClickListener {
            showAddPatientDialog()
        }

        binding.btnStartSession.setOnClickListener {
            if (currentSelectedPatient != null) {
                val intent = Intent(this, SessionActivity::class.java)
                intent.putExtra(getString(R.string.patient_id_extra), currentSelectedPatient!!.id) // Pass patient ID
                startActivity(intent)
            } else {
                Toast.makeText(this, getString(R.string.select_patient_to_start), Toast.LENGTH_SHORT).show()
            }
        }
        updateStartSessionButtonState()
    }

    private fun setupRecyclerView() {
        patientAdapter = PatientAdapter(
            this,
            onPatientSelected = { patient ->
                currentSelectedPatient = patient
                updateStartSessionButtonState()
            },
            onDeleteClicked = { patient ->
                showDeleteConfirmationDialog(patient)
            }
        )
        binding.rvPatients.apply {
            adapter = patientAdapter
            layoutManager = LinearLayoutManager(this@PatientsActivity)
        }
    }

    private fun observePatients() {
        db.patientDao().getAllPatients().observe(this) { patients ->
            patientAdapter.submitList(patients)
            if (patients.isEmpty()) {
                binding.tvNoPatients.visibility = View.VISIBLE
                binding.rvPatients.visibility = View.GONE
            } else {
                binding.tvNoPatients.visibility = View.GONE
                binding.rvPatients.visibility = View.VISIBLE
            }

            if (currentSelectedPatient != null && !patients.contains(currentSelectedPatient)) {
                currentSelectedPatient = null
                patientAdapter.clearSelection()
                updateStartSessionButtonState()
            }
        }
    }

    private fun showAddPatientDialog() {
        val dialogBinding = DialogAddPatientBinding.inflate(LayoutInflater.from(this))

        MaterialAlertDialogBuilder(this, R.style.Theme_VoiceAnalyzerApp_Dialog)
            .setTitle(getString(R.string.dialog_add_patient_title))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.add_button), null)
            .setNegativeButton(getString(R.string.cancel_button)) { dialog, _ ->
                dialog.dismiss()
            }
            .create().apply {
                setOnShowListener {
                    val positiveButton = getButton(AlertDialog.BUTTON_POSITIVE)
                    positiveButton.setOnClickListener {
                        val lastName = dialogBinding.etLastName.text.toString().trim()
                        val firstName = dialogBinding.etFirstName.text.toString().trim()
                        val ageStr = dialogBinding.etAge.text.toString().trim()

                        if (lastName.isBlank() || firstName.isBlank() || ageStr.isBlank()) {
                            Toast.makeText(this@PatientsActivity, getString(R.string.error_fill_all_fields), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val age = ageStr.toIntOrNull()
                        if (age == null || age <= 0) {
                            Toast.makeText(this@PatientsActivity, getString(R.string.error_invalid_age), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val patient = Patient(firstName = firstName, lastName = lastName, age = age)
                        lifecycleScope.launch {
                            db.patientDao().insertPatient(patient)
                        }
                        dismiss()
                    }
                }
            }.show()
    }


    private fun showDeleteConfirmationDialog(patient: Patient) {
        MaterialAlertDialogBuilder(this, R.style.Theme_VoiceAnalyzerApp_Dialog)
            .setTitle("Удалить пациента?")
            .setMessage("Вы уверены, что хотите удалить ${patient.lastName} ${patient.firstName}?")
            .setPositiveButton("Удалить") { dialog, _ ->
                lifecycleScope.launch {
                    db.patientDao().deletePatient(patient)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel_button)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun updateStartSessionButtonState() {
        val isEnabled = currentSelectedPatient != null
        binding.btnStartSession.isEnabled = isEnabled
        binding.btnStartSession.alpha = if (isEnabled) 1.0f else 0.5f
    }
}