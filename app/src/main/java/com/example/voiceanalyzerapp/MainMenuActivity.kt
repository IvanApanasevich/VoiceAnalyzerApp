package com.example.voiceanalyzerapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.voiceanalyzerapp.databinding.ActivityMainMenuBinding

class MainMenuActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAnalyzeVoice.setOnClickListener {
            startActivity(Intent(this, VoiceAnalysisActivity::class.java))
        }

        // Updated button ID and action
        binding.btnStartSessionMenu.setOnClickListener {
            startActivity(Intent(this, PatientsActivity::class.java))
        }
    }
}

