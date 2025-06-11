package com.example.voiceanalyzerapp

import android.util.Log
import be.tarsos.dsp.util.fft.FFT
import be.tarsos.dsp.util.fft.HammingWindow
import kotlin.math.abs
import kotlin.math.sqrt

object LPCFormantCalculator {
    private const val TAG = "LPCFormantCalculator"
    private const val PRE_EMPHASIS_ALPHA = 0.97f
    private const val DEFAULT_FFT_SIZE_FOR_LPC_SPECTRUM = 512

    private val hammingWindow = HammingWindow()
    private val fftInstances = mutableMapOf<Int, FFT>()

    private fun getFFT(size: Int): FFT {
        var fft = fftInstances[size]
        if (fft == null) {
            synchronized(fftInstances) {
                fft = fftInstances[size]
                if (fft == null) {
                    fft = FFT(size)
                    fftInstances[size] = fft!!
                }
            }
        }
        return fft!!
    }


    fun determineLpcOrder(sampleRate: Float): Int {
        val baseOrder = (sampleRate / 1000f).toInt() + 4
        val capOrder = (sampleRate / 1000f * 1.5f).toInt().coerceAtMost(24)
        return baseOrder.coerceIn(10, capOrder)
    }

    fun calculateFormantsForFrame(
        audioBuffer: FloatArray,
        sampleRate: Float,
        lpcOrder: Int,
        fftSize: Int = DEFAULT_FFT_SIZE_FOR_LPC_SPECTRUM
    ): Triple<Float, Float, Float> { // F1, F2, F3

        if (audioBuffer.size < lpcOrder + 1) {
            Log.w(TAG, "Кадр слишком короткий для порядка LPC $lpcOrder. Размер кадра: ${audioBuffer.size}")
            return Triple(0f, 0f, 0f)
        }
        if (audioBuffer.isEmpty()) {
            return Triple(0f,0f,0f)
        }


        val preEmphasizedBuffer = FloatArray(audioBuffer.size)
        preEmphasizedBuffer[0] = audioBuffer[0]
        for (i in 1 until audioBuffer.size) {
            preEmphasizedBuffer[i] = audioBuffer[i] - PRE_EMPHASIS_ALPHA * audioBuffer[i - 1]
        }

        val windowedBuffer = preEmphasizedBuffer.copyOf()
        hammingWindow.apply(windowedBuffer)

        val ac = DoubleArray(lpcOrder + 1)
        for (k in 0..lpcOrder) {
            var sum = 0.0
            for (n in 0 until windowedBuffer.size - k) {
                sum += windowedBuffer[n] * windowedBuffer[n + k]
            }
            ac[k] = sum
        }

        if (abs(ac[0]) < 1e-9) {
            return Triple(0f, 0f, 0f)
        }


        val a = DoubleArray(lpcOrder + 1)
        val E = DoubleArray(lpcOrder + 1)
        val k_refl = DoubleArray(lpcOrder + 1)

        a[0] = 1.0
        E[0] = ac[0]

        for (m in 1..lpcOrder) {
            var sumCorrelated = ac[m]
            for (j in 1 until m) {
                sumCorrelated += a[j] * ac[m - j]
            }
            if (abs(E[m-1]) < 1e-12) {
                Log.w(TAG, "E[m-1] близко к нулю в рекурсии Дурбина при m=$m. Пропуск кадра.")
                return Triple(0f, 0f, 0f)
            }
            k_refl[m] = -sumCorrelated / E[m-1]
            a[m] = k_refl[m]

            val tempA_prev_iter = a.copyOfRange(1, m) // a_j^(m-1)
            for (j in 1 until m) {
                // a_j^(m) = a_j^(m-1) + k_m * a_{m-j}^(m-1)
                a[j] = tempA_prev_iter[j-1] + k_refl[m] * tempA_prev_iter[m-1-j]
            }
            E[m] = (1 - k_refl[m] * k_refl[m]) * E[m-1]
        }


        val lpcForFft = FloatArray(fftSize)
        for(i in 0..lpcOrder) {
            if (i < a.size) lpcForFft[i] = a[i].toFloat()
        }

        val frameFft = getFFT(fftSize)
        val fftOutput = lpcForFft.copyOf()
        frameFft.forwardTransform(fftOutput) // A(omega)

        val lpcSpectrum = DoubleArray(fftSize / 2)
        for (i in 0 until fftSize / 2) {
            val re = fftOutput[2 * i]
            val im = fftOutput[2 * i + 1]
            val magnitudeA = sqrt(re * re + im * im)
            if (magnitudeA > 1e-7) {
                lpcSpectrum[i] = 1.0 / magnitudeA
            } else {
                lpcSpectrum[i] = 0.0
            }
        }


        val peaks = mutableListOf<Pair<Int, Double>>()
        for (i in 1 until lpcSpectrum.size - 1) {

            if (lpcSpectrum[i] > lpcSpectrum[i - 1] && lpcSpectrum[i] > lpcSpectrum[i + 1] && lpcSpectrum[i] > 1e-3) {
                peaks.add(Pair(i, lpcSpectrum[i]))
            }
        }

        if (peaks.isEmpty() && lpcSpectrum.any { it > 1e-3 }) {
            var maxVal = 0.0
            var maxIdx = -1
            lpcSpectrum.forEachIndexed { index, value ->
                if (value > maxVal) {
                    maxVal = value
                    maxIdx = index
                }
            }
            if (maxIdx != -1) peaks.add(Pair(maxIdx, maxVal))
        }


        val formantCandidates = peaks.map { peak ->
            Pair(peak.first * (sampleRate / fftSize.toFloat()), peak.second)
        }.sortedBy { it.first }


        val foundFormantsHz = mutableListOf<Float>()
        for (candidate in formantCandidates) {
            val freq = candidate.first
            if (foundFormantsHz.size >= 3) break

            when (foundFormantsHz.size) {
                0 -> { // F1
                    if (freq in 150.0..1200.0) {
                        foundFormantsHz.add(freq)
                    }
                }
                1 -> { // F2
                    val f1 = foundFormantsHz.last()
                    if (freq > f1 + 100 && freq <= 3000 && freq > f1 * 1.2) {
                        foundFormantsHz.add(freq)
                    }
                }
                2 -> { // F3
                    val f2 = foundFormantsHz.last()
                    if (freq > f2 + 100 && freq <= 4500 && freq > f2 * 1.2) {
                        foundFormantsHz.add(freq)
                    }
                }
            }
        }

        return Triple(
            foundFormantsHz.getOrElse(0) { 0f },
            foundFormantsHz.getOrElse(1) { 0f },
            foundFormantsHz.getOrElse(2) { 0f }
        )
    }
}