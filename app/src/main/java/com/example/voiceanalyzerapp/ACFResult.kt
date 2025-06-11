package com.example.voiceanalyzerapp

data class ACFResult(
    val timestamp: Double,
    val f0: Float,
    var harmonicity: Float,
    val bestCorrelationLag: Int,
    val originalBuffer: FloatArray,
    val f1: Float = 0f,
    val f2: Float = 0f,
    val f3: Float = 0f
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ACFResult
        return timestamp == other.timestamp &&
                f0 == other.f0 &&
                harmonicity == other.harmonicity &&
                bestCorrelationLag == other.bestCorrelationLag &&
                f1 == other.f1 &&
                f2 == other.f2 &&
                f3 == other.f3
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + f0.hashCode()
        result = 31 * result + harmonicity.hashCode()
        result = 31 * result + bestCorrelationLag
        result = 31 * result + f1.hashCode()
        result = 31 * result + f2.hashCode()
        result = 31 * result + f3.hashCode()
        return result
    }
}

