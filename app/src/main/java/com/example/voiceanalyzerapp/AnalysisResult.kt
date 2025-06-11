package com.example.voiceanalyzerapp

data class AnalysisResult(
    val f0: Double = 0.0,
    val jitter: Double = 0.0,
    val shimmer: Double = 0.0,
    val intensity: Double = 0.0,
    val hnr: Double = 0.0,
    val phonationTimeMs: Double = 0.0,
    val f1: Double = 0.0,
    val f2: Double = 0.0,
    val f3: Double = 0.0,
    val errorMessage: String? = null
)