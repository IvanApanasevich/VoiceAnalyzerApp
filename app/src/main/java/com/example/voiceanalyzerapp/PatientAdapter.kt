package com.example.voiceanalyzerapp

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceanalyzerapp.database.Patient
import com.example.voiceanalyzerapp.databinding.ItemPatientBinding

class PatientAdapter(
    private val context: Context,
    private val onPatientSelected: (Patient?) -> Unit,
    private val onDeleteClicked: (Patient) -> Unit
) : ListAdapter<Patient, PatientAdapter.PatientViewHolder>(PatientDiffCallback()) {

    private var selectedPosition = RecyclerView.NO_POSITION
    private var selectedPatient: Patient? = null

    inner class PatientViewHolder(val binding: ItemPatientBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(patient: Patient, isSelected: Boolean) {
            binding.tvPatientName.text = "${patient.lastName} ${patient.firstName}"
            binding.tvPatientAge.text = formatAge(patient.age)

            binding.cbSelectPatient.isChecked = isSelected
            itemView.isSelected = isSelected

            if (isSelected) {
                itemView.setBackgroundResource(R.drawable.item_patient_background_selector)
            } else {
                itemView.setBackgroundResource(R.drawable.item_patient_background_selector)
            }


            binding.cbSelectPatient.setOnClickListener {
                handleSelection()
            }
            itemView.setOnClickListener {
                handleSelection()
            }

            binding.btnDeletePatient.setOnClickListener {
                onDeleteClicked(patient)
            }
        }

        private fun handleSelection() {
            val previousSelectedPosition = selectedPosition
            if (adapterPosition == RecyclerView.NO_POSITION) return

            if (selectedPosition == adapterPosition) {
                selectedPosition = RecyclerView.NO_POSITION
                selectedPatient = null
                notifyItemChanged(adapterPosition)
            } else {
                selectedPosition = adapterPosition
                selectedPatient = getItem(adapterPosition)
                notifyItemChanged(adapterPosition)
                if (previousSelectedPosition != RecyclerView.NO_POSITION) {
                    notifyItemChanged(previousSelectedPosition)
                }
            }
            onPatientSelected(selectedPatient)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientViewHolder {
        val binding = ItemPatientBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PatientViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PatientViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    fun getSelectedPatient(): Patient? {
        return selectedPatient
    }

    fun clearSelection() {
        val previouslySelected = selectedPosition
        selectedPosition = RecyclerView.NO_POSITION
        selectedPatient = null
        if (previouslySelected != RecyclerView.NO_POSITION) {
            notifyItemChanged(previouslySelected)
        }
        onPatientSelected(null)
    }


    private fun formatAge(age: Int): String {
        val lastDigit = age % 10
        val lastTwoDigits = age % 100
        val ageSuffix = when {
            lastTwoDigits in 11..14 -> context.getString(R.string.years_many) // лет
            lastDigit == 1 -> context.getString(R.string.years_single)        // год
            lastDigit in 2..4 -> context.getString(R.string.years_few)       // года
            else -> context.getString(R.string.years_many)                     // лет
        }
        return "$age $ageSuffix"
    }
}

class PatientDiffCallback : DiffUtil.ItemCallback<Patient>() {
    override fun areItemsTheSame(oldItem: Patient, newItem: Patient): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Patient, newItem: Patient): Boolean {
        return oldItem == newItem
    }
}