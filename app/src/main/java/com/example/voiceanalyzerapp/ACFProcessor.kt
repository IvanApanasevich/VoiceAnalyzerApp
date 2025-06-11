package com.example.voiceanalyzerapp

import android.util.Log
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.util.fft.FFT
import be.tarsos.dsp.util.fft.HammingWindow
import kotlin.math.abs

class ACFProcessor(
    private val sampleRate: Float,
    private val bufferSize: Int,
    minF0Hz: Float,
    maxF0Hz: Float,
    private val resultHandler: (result: ACFResult) -> Unit
) : AudioProcessor {

    private val fft = FFT(bufferSize)
    private val windowFunction = HammingWindow()
    private val windowBuffer = FloatArray(bufferSize)

    private val rawWindowCoefficients: FloatArray =
        FloatArray(bufferSize) { 1.0f }
    private val acfOfWindow: FloatArray

    private val TAG_ACF = "ACFProcessor"


    private val minLagSamples = (sampleRate / maxF0Hz).toInt().coerceAtLeast(1)
    private val maxLagSamplesCalc = (sampleRate / minF0Hz).toInt().coerceAtMost(bufferSize / 2 - 1)


    private val lpcOrderForFormants: Int

    init {
        windowFunction.apply(rawWindowCoefficients)
        if (maxLagSamplesCalc >= 0 && bufferSize > 0 && maxLagSamplesCalc < bufferSize) {
            acfOfWindow = FloatArray(maxLagSamplesCalc + 1)
            for (lag in 0..maxLagSamplesCalc) {
                var sum = 0.0
                val limit = bufferSize - lag
                // if (limit > 0) {
                for (i in 0 until limit) {
                    sum += rawWindowCoefficients[i] * rawWindowCoefficients[i + lag]
                }
                // }
                acfOfWindow[lag] = sum.toFloat()
            }
        } else {
            acfOfWindow = FloatArray(0)
            Log.e(TAG_ACF, "maxLagSamplesCalc некорректен или bufferSize равен нулю. Проверьте диапазон F0 и bufferSize. maxLagSamplesCalc=$maxLagSamplesCalc, bufferSize=$bufferSize")
        }

        lpcOrderForFormants = LPCFormantCalculator.determineLpcOrder(sampleRate)
    }

    override fun process(audioEvent: AudioEvent): Boolean {
        if (audioEvent.floatBuffer.size != bufferSize) {
            Log.w(TAG_ACF, "Ожидался буфер размера $bufferSize, получен ${audioEvent.floatBuffer.size}. Кадр пропущен.")

            resultHandler(ACFResult(audioEvent.timeStamp, 0f, 0f, -1, audioEvent.floatBuffer.copyOf(),0f,0f,0f))
            return true
        }
        if (bufferSize == 0) {
            Log.e(TAG_ACF, "bufferSize равен 0, обработка невозможна.")
            resultHandler(ACFResult(audioEvent.timeStamp, 0f, 0f, -1, audioEvent.floatBuffer.copyOf(),0f,0f,0f))
            return true
        }


        System.arraycopy(audioEvent.floatBuffer, 0, windowBuffer, 0, bufferSize)
        val mean = windowBuffer.average().toFloat()
        for (i in windowBuffer.indices) {
            windowBuffer[i] -= mean
        }


        windowFunction.apply(windowBuffer)


        val fftData = FloatArray(bufferSize * 2)
        System.arraycopy(windowBuffer, 0, fftData, 0, bufferSize)
        fft.forwardTransform(fftData)


        val powerSpectrum = FloatArray(bufferSize)
        for (i in 0 until bufferSize) {
            val re = fftData[2 * i]
            val im = fftData[2 * i + 1]
            powerSpectrum[i] = re * re + im * im
        }


        val acfInputFft = FloatArray(bufferSize * 2)
        for (i in powerSpectrum.indices) {
            acfInputFft[2 * i] = powerSpectrum[i]
            acfInputFft[2 * i + 1] = 0f
        }
        fft.backwardsTransform(acfInputFft)


        val r_aa_0 = acfInputFft[0]
        if (r_aa_0 < 1e-9f) {
            resultHandler(ACFResult(audioEvent.timeStamp, 0f, 0f, -1, audioEvent.floatBuffer.copyOf(),0f,0f,0f))
            return true
        }


        val numEffectiveLagsToStore = (maxLagSamplesCalc + 1).coerceAtMost(acfInputFft.size / 2)

        if (numEffectiveLagsToStore <= 0 || acfOfWindow.isEmpty()) {
            Log.w(TAG_ACF, "numEffectiveLagsToStore ($numEffectiveLagsToStore) или acfOfWindow недействителен.")
            resultHandler(ACFResult(audioEvent.timeStamp, 0f, 0f, -1, audioEvent.floatBuffer.copyOf(),0f,0f,0f))
            return true
        }

        val normalized_r_aa = FloatArray(numEffectiveLagsToStore)
        for (lag in 0 until numEffectiveLagsToStore) {

            normalized_r_aa[lag] = acfInputFft[2 * lag] / r_aa_0
        }

        var maxRawAcfValue = -1.0f
        var bestIntLag = -1


        if (minLagSamples <= maxLagSamplesCalc) {
            for (lag in minLagSamples..maxLagSamplesCalc) {
                if (lag < normalized_r_aa.size && normalized_r_aa[lag] > maxRawAcfValue) {
                    maxRawAcfValue = normalized_r_aa[lag]
                    bestIntLag = lag
                }
            }
        }

        if (bestIntLag == -1) { // Пик не найден
            resultHandler(ACFResult(audioEvent.timeStamp, 0f, 0f, -1, audioEvent.floatBuffer.copyOf(),0f,0f,0f))
            return true
        }


        var refinedLag = bestIntLag.toFloat()
        var refined_r_aa_normalized = maxRawAcfValue
        var p_offset = 0f

        if (bestIntLag in 1..<maxLagSamplesCalc &&
            bestIntLag < normalized_r_aa.size - 1) {
            val alpha = normalized_r_aa[bestIntLag - 1]
            val beta = normalized_r_aa[bestIntLag]
            val gamma = normalized_r_aa[bestIntLag + 1]
            val denominator = alpha - 2 * beta + gamma
            if (abs(denominator) > 1e-9f && denominator < 0) {
                p_offset = 0.5f * (alpha - gamma) / denominator
                if (abs(p_offset) < 1.0f) {
                    refinedLag = bestIntLag + p_offset

                    refined_r_aa_normalized = beta - 0.25f * (alpha - gamma) * p_offset
                }
            }
        }

        val f0 = if (refinedLag > 1e-6f) sampleRate / refinedLag else 0f


        var boersmaCorrectedHarmonicity: Float
        val r_ww_0 = acfOfWindow.getOrElse(0) { 0f } // R_ww(0)

        if (bestIntLag < acfOfWindow.size && r_ww_0 > 1e-9f) { // bestIntLag >= 0 &&
            var r_ww_tau_peak_corrected = acfOfWindow[bestIntLag]

            if (abs(p_offset) > 1e-6f && bestIntLag > 0 && bestIntLag < acfOfWindow.size - 1) {
                val alpha_w = acfOfWindow[bestIntLag - 1]
                val beta_w = acfOfWindow[bestIntLag]
                val gamma_w = acfOfWindow[bestIntLag + 1]
                val denominator_w = alpha_w - 2 * beta_w + gamma_w
                if (abs(denominator_w) > 1e-9f) {
                    r_ww_tau_peak_corrected = beta_w - 0.25f * (alpha_w - gamma_w) * p_offset
                } else {
                    Log.w(TAG_ACF, "Знаменатель для интерполяции АКФ окна близок к нулю в лаге $bestIntLag. Используется неинтерполированное R_ww.")
                }
            }

            if (abs(r_ww_tau_peak_corrected) > 1e-9f) {
                boersmaCorrectedHarmonicity = refined_r_aa_normalized * (r_ww_0 / r_ww_tau_peak_corrected)
                boersmaCorrectedHarmonicity = boersmaCorrectedHarmonicity.coerceIn(0f, 1f)
            } else {
                boersmaCorrectedHarmonicity = refined_r_aa_normalized.coerceIn(0f, 1f) // Ограничение
                Log.w(TAG_ACF, "Скорректированное R_ww на пиковом лаге близко к нулю. Невозможно применить полную коррекцию Бурсмы. lag=$bestIntLag, p_offset=$p_offset")
            }
        } else {
            boersmaCorrectedHarmonicity = refined_r_aa_normalized.coerceIn(0f, 1f)
            if (r_ww_0 <= 1e-9f) Log.w(TAG_ACF, "R_ww(0) близко к нулю.")
            if (acfOfWindow.isNotEmpty() && ( bestIntLag >= acfOfWindow.size) ) { // bestIntLag < 0 ||
                Log.w(TAG_ACF, "bestIntLag $bestIntLag вне диапазона для acfOfWindow (размер ${acfOfWindow.size}) для коррекции")
            }
        }

        var f1_formant = 0f
        var f2_formant = 0f
        var f3_formant = 0f


        if (f0 > 0 && boersmaCorrectedHarmonicity > 0.3) {
            val (f1, f2, f3) = LPCFormantCalculator.calculateFormantsForFrame(
                audioEvent.floatBuffer,
                sampleRate,
                lpcOrderForFormants
            )
            f1_formant = f1
            f2_formant = f2
            f3_formant = f3
        }


        Log.d(TAG_ACF, String.format("Кадр @ %.3fs: F0=%.2f Hz, RawHarm=%.2f, CorrHarm=%.2f, Lag=%d (ref:%.2f, p:%.3f), F1=%.0f, F2=%.0f, F3=%.0f",
            audioEvent.timeStamp, f0, refined_r_aa_normalized, boersmaCorrectedHarmonicity, bestIntLag, refinedLag, p_offset, f1_formant, f2_formant, f3_formant))

        resultHandler(ACFResult(
            audioEvent.timeStamp,
            f0,
            boersmaCorrectedHarmonicity,
            bestIntLag,
            audioEvent.floatBuffer.copyOf(),
            f1_formant,
            f2_formant,
            f3_formant
        ))
        return true
    }

    override fun processingFinished() {

    }
}
