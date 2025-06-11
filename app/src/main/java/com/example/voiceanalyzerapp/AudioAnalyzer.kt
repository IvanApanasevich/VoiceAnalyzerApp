package com.example.voiceanalyzerapp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.SilenceDetector
import be.tarsos.dsp.filters.HighPass
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.TarsosDSPAudioInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10


class AudioAnalyzer(
    private val context: Context
) {

    interface AnalysisListener {
        fun onAnalysisComplete(result: AnalysisResult, processedAudioPath: String?)
        fun onAnalysisError(errorMessage: String)
    }

    private var listener: AnalysisListener? = null


    private val ANALYSIS_BUFFER_SIZE = 2048
    private val ANALYSIS_OVERLAP = 0
    private val NOISE_REDUCTION_CUTOFF_HZ = 80f
    private val CUSTOM_SILENCE_THRESHOLD_DB = -45.0
    private val MIN_SILENCE_DURATION_MS = 5.0 // мс
    private val MIN_LETTER_SOUND_DURATION_MS = 20.0 // мс
    private val MAX_LETTER_SOUND_DURATION_MS = 3000.0 // мс
    private val MIN_SIGNIFICANT_SOUND_DURATION_MS = 50.0 // мс

    private val TAG = "AudioAnalyzer"


    private data class FullFrameData(
        val acfResult: ACFResult,
        val rms: Float,
        val splDb: Double,
        val isSilent: Boolean
    )

    private val collectedFrameData = mutableListOf<FullFrameData>()
    private var currentSampleRate: Float = 0f
    @Volatile private var audioDispatcher: AudioDispatcher? = null
    private var outputPathForProcessedAudio: String? = null
    private lateinit var silenceDetectorInstance: SilenceDetector

    fun setAnalysisListener(listener: AnalysisListener) {
        this.listener = listener
    }

    fun analyze(filePath: String, processedAudioOutputPath: String? = null) {
        Log.d(TAG, "Запуск анализа (с ACF Boersma и формантами) для файла: $filePath.")
        clearAndResetData()
        this.outputPathForProcessedAudio = processedAudioOutputPath
        this.silenceDetectorInstance = SilenceDetector(CUSTOM_SILENCE_THRESHOLD_DB, false)


        val audioFile = File(filePath)
        if (!audioFile.exists()) {
            listener?.onAnalysisError("Файл для анализа не найден.")
            return
        }
        if (audioFile.length() == 0L) {
            listener?.onAnalysisError("Файл для анализа пуст.")
            return
        }

        Thread {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            lateinit var tarsosFormat: TarsosDSPAudioFormat

            try {
                extractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
                var audioTrackIndex = -1
                var inputFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioTrackIndex = i
                        inputFormat = format
                        this.currentSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toFloat()
                        break
                    }
                }

                if (audioTrackIndex == -1 || inputFormat == null || this.currentSampleRate == 0f) {
                    listener?.onAnalysisError("Ошибка конфигурации аудиодорожки.")
                    return@Thread
                }

                extractor.selectTrack(audioTrackIndex)
                val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)!!
                codec = MediaCodec.createDecoderByType(mimeType).apply {
                    configure(inputFormat, null, null, 0)
                    start()
                }

                val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                tarsosFormat = TarsosDSPAudioFormat(this.currentSampleRate, 16, channels, true, false)


                val customAudioStream = createCustomAudioInputStream(codec, extractor, tarsosFormat, -1L)
                val localAudioDispatcher = AudioDispatcher(customAudioStream, ANALYSIS_BUFFER_SIZE, ANALYSIS_OVERLAP)
                this.audioDispatcher = localAudioDispatcher

                localAudioDispatcher.addAudioProcessor(HighPass(NOISE_REDUCTION_CUTOFF_HZ, this.currentSampleRate))
                localAudioDispatcher.addAudioProcessor(this.silenceDetectorInstance)

                val acfProcessor = ACFProcessor(
                    sampleRate = this.currentSampleRate,
                    bufferSize = ANALYSIS_BUFFER_SIZE,
                    minF0Hz = 75f,
                    maxF0Hz = 600f,
                    resultHandler = { acfResult -> // acfResult теперь содержит F0, гармоничность и F1,F2,F3
                        val rms = SilenceDetector.calculateRMS(acfResult.originalBuffer).toFloat()
                        val isSilentCurrentFrame = silenceDetectorInstance.isSilence(acfResult.originalBuffer)
                        val splCurrentFrame = silenceDetectorInstance.currentSPL()

                        collectedFrameData.add(FullFrameData(acfResult, rms, splCurrentFrame, isSilentCurrentFrame))
                    }
                )
                localAudioDispatcher.addAudioProcessor(acfProcessor)

                localAudioDispatcher.addAudioProcessor(object : AudioProcessor {
                    override fun process(audioEvent: AudioEvent?): Boolean = true
                    override fun processingFinished() {
                        performSilenceDetectionAndFinalAnalysis()
                    }
                })

                localAudioDispatcher.run()

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка во время анализа аудио: ${e.message}", e)
                listener?.onAnalysisError("Ошибка анализа: ${e.localizedMessage}")
                clearAndResetData()
            } finally {
                try {
                    codec?.stop()
                    codec?.release()
                    extractor?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при освобождении ресурсов анализа: ${e.message}")
                }
                this.audioDispatcher = null
                Log.d(TAG, "Поток анализа завершен.")
            }
        }.start()
    }

    fun stopAnalysis() {
        audioDispatcher?.stop()
        audioDispatcher = null
        Log.d(TAG, "Анализ остановлен пользователем.")
    }


    private fun performSilenceDetectionAndFinalAnalysis() {
        if (collectedFrameData.isEmpty()) {
            listener?.onAnalysisError("Нет обработанных фреймов для анализа.")
            return
        }


        val significantSoundSegments = mutableListOf<Pair<Int, Int>>()
        var inSoundSegment = false
        var currentSegmentStartIndex = -1

        collectedFrameData.forEachIndexed { index, frameData ->
            val isSound = !frameData.isSilent
            if (isSound && !inSoundSegment) {
                inSoundSegment = true
                currentSegmentStartIndex = index
            } else if (!isSound && inSoundSegment) {
                inSoundSegment = false
                val segmentDurationMs = (collectedFrameData[index - 1].acfResult.timestamp -
                        collectedFrameData[currentSegmentStartIndex].acfResult.timestamp) * 1000.0
                if (segmentDurationMs >= MIN_SIGNIFICANT_SOUND_DURATION_MS) {
                    significantSoundSegments.add(Pair(currentSegmentStartIndex, index - 1))
                }
            }
        }
        if (inSoundSegment) {
            val lastSegmentDurationMs = (collectedFrameData.last().acfResult.timestamp -
                    collectedFrameData[currentSegmentStartIndex].acfResult.timestamp) * 1000.0
            if (lastSegmentDurationMs >= MIN_SIGNIFICANT_SOUND_DURATION_MS) {
                significantSoundSegments.add(Pair(currentSegmentStartIndex, collectedFrameData.size - 1))
            }
        }

        if (significantSoundSegments.isEmpty()) {
            listener?.onAnalysisError("Запись содержит только тишину или слишком короткие звуки.")
            return
        }

        if (significantSoundSegments.size > 1) {
            listener?.onAnalysisError("Обнаружено несколько звуковых событий. Анализ одного события предпочтителен.")
            return
        }

        val (firstSoundFrameIndex, lastSoundFrameIndex) = significantSoundSegments.first()


        var hasProperLeadingSilence = false
        if (firstSoundFrameIndex > 0) {
            val leadingSilenceFrames = collectedFrameData.subList(0, firstSoundFrameIndex)
            if (leadingSilenceFrames.all { it.isSilent }) {
                val firstSoundFrameTimeMs = collectedFrameData[firstSoundFrameIndex].acfResult.timestamp * 1000.0
                if (firstSoundFrameTimeMs >= MIN_SILENCE_DURATION_MS) {
                    hasProperLeadingSilence = true
                }
            }
        } else {
            if (MIN_SILENCE_DURATION_MS == 0.0) hasProperLeadingSilence = true;
        }
        if (!hasProperLeadingSilence) {
            listener?.onAnalysisError("Отсутствует достаточная начальная тишина (${String.format("%.1f", MIN_SILENCE_DURATION_MS)} мс).")
            return
        }


        var hasProperTrailingSilence = false
        if (lastSoundFrameIndex < collectedFrameData.size - 1) {
            val trailingSilenceFrames = collectedFrameData.subList(lastSoundFrameIndex + 1, collectedFrameData.size)
            if (trailingSilenceFrames.all { it.isSilent }) {
                val soundEndTimeMs = collectedFrameData[lastSoundFrameIndex].acfResult.timestamp * 1000.0
                val totalRecordingTimeMs = collectedFrameData.last().acfResult.timestamp * 1000.0
                if ((totalRecordingTimeMs - soundEndTimeMs) >= MIN_SILENCE_DURATION_MS) {
                    hasProperTrailingSilence = true
                }
            }
        } else {
            if (MIN_SILENCE_DURATION_MS == 0.0) hasProperTrailingSilence = true;
        }

        if (!hasProperTrailingSilence) {
            listener?.onAnalysisError("Отсутствует достаточная конечная тишина (${String.format("%.1f", MIN_SILENCE_DURATION_MS)} мс).")
            return
        }


        val letterSoundDurationMs = (collectedFrameData[lastSoundFrameIndex].acfResult.timestamp -
                collectedFrameData[firstSoundFrameIndex].acfResult.timestamp) * 1000.0
        if (letterSoundDurationMs < MIN_LETTER_SOUND_DURATION_MS) {
            listener?.onAnalysisError("Звук буквы слишком короткий (длительность ${String.format("%.1f", letterSoundDurationMs)} мс, мин ${String.format("%.1f", MIN_LETTER_SOUND_DURATION_MS)} мс).")
            return
        }
        if (letterSoundDurationMs > MAX_LETTER_SOUND_DURATION_MS) {
            listener?.onAnalysisError("Звук буквы слишком длинный (длительность ${String.format("%.1f", letterSoundDurationMs)} мс, макс ${String.format("%.1f", MAX_LETTER_SOUND_DURATION_MS)} мс).")
            return
        }


        val segmentBuffers = (firstSoundFrameIndex..lastSoundFrameIndex).map {
            collectedFrameData[it].acfResult.originalBuffer.copyOf()
        }.toMutableList()

        var finalProcessedAudioPath: String? = null
        if (outputPathForProcessedAudio != null && segmentBuffers.isNotEmpty()) {
            try {
                val outputFormat = TarsosDSPAudioFormat(this.currentSampleRate, 16, 1, true, false)
                val tempPcmFile = File(context.cacheDir, "temp_processed_audio.pcm")

                FileOutputStream(tempPcmFile).use { fos ->
                    val totalSamples = segmentBuffers.sumOf { it.size }
                    val byteBuffer = ByteBuffer.allocate(totalSamples * 2)
                    byteBuffer.order(if (outputFormat.isBigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN)

                    segmentBuffers.forEach { buffer ->
                        buffer.forEach { sample ->
                            val shortSample = (sample * Short.MAX_VALUE).toInt().toShort()
                            byteBuffer.putShort(shortSample)
                        }
                    }
                    fos.write(byteBuffer.array(), 0, byteBuffer.position())
                }

                val wavFile = File(outputPathForProcessedAudio!!)
                PcmToWavConverter.convertPcmToWav(
                    tempPcmFile, wavFile,
                    outputFormat.channels,
                    currentSampleRate.toInt(),
                    outputFormat.sampleSizeInBits
                )
                finalProcessedAudioPath = wavFile.absolutePath
                tempPcmFile.delete()
                Log.d(TAG, "Обработанное аудио сохранено в: $finalProcessedAudioPath")

            } catch (e: IOException) {
                Log.e(TAG, "Ошибка записи обработанного аудио: ${e.message}", e)
            }
        }


        val segmentF0s = mutableListOf<Float>()
        val segmentHarmonicities = mutableListOf<Float>()
        val segmentRMS = mutableListOf<Float>()
        val segmentF1s = mutableListOf<Float>()
        val segmentF2s = mutableListOf<Float>()
        val segmentF3s = mutableListOf<Float>()

        for (i in firstSoundFrameIndex..lastSoundFrameIndex) {
            val data = collectedFrameData[i]

            if (data.acfResult.f0 > 0) {
                segmentF0s.add(data.acfResult.f0)

                if (data.acfResult.f1 > 0) segmentF1s.add(data.acfResult.f1)
                if (data.acfResult.f2 > 0) segmentF2s.add(data.acfResult.f2)
                if (data.acfResult.f3 > 0) segmentF3s.add(data.acfResult.f3)
            }
            segmentHarmonicities.add(data.acfResult.harmonicity)
            segmentRMS.add(data.rms)
        }

        if (segmentRMS.isEmpty()) {
            listener?.onAnalysisError("Нет RMS/F0 данных в определенном звуковом сегменте.")
            return
        }


        val avgF1 = if (segmentF1s.isNotEmpty()) segmentF1s.average().toDouble() else 0.0
        val avgF2 = if (segmentF2s.isNotEmpty()) segmentF2s.average().toDouble() else 0.0
        val avgF3 = if (segmentF3s.isNotEmpty()) segmentF3s.average().toDouble() else 0.0


        val phonationTimeMs = letterSoundDurationMs

        calculateAndReportCharacteristics(
            segmentF0s,
            segmentHarmonicities,
            segmentRMS,
            finalProcessedAudioPath,
            phonationTimeMs,
            avgF1, avgF2, avgF3
        )
    }

    private fun calculateAndReportCharacteristics(
        f0sToAnalyze: List<Float>,
        harmonicitiesToAnalyze: List<Float>,
        rmsToAnalyze: List<Float>,
        processedAudioPath: String?,
        phonationTimeMs: Double,
        f1: Double,
        f2: Double,
        f3: Double
    ) {
        val averageF0 = if (f0sToAnalyze.isNotEmpty()) f0sToAnalyze.average().toDouble() else 0.0
        val averageRMS = if (rmsToAnalyze.isNotEmpty()) rmsToAnalyze.map { it.toDouble() }.average() else 0.0
        val intensityDb = if (averageRMS > 1e-9) 20 * log10(averageRMS) else -100.0

        var hnr = 0.0
        if (harmonicitiesToAnalyze.isNotEmpty()) {
            val avgCorrectedHarmonicity = harmonicitiesToAnalyze.filter { it.isFinite() && it in 0f..1f }.average().toFloat()
            if (avgCorrectedHarmonicity.isFinite() && avgCorrectedHarmonicity > 1e-6f && avgCorrectedHarmonicity < (1.0f - 1e-6f) ) {
                val ratio = avgCorrectedHarmonicity / (1.0f - avgCorrectedHarmonicity)
                if (ratio > 0) hnr = 10.0 * log10(ratio.toDouble()) else hnr = -30.0
            } else if (avgCorrectedHarmonicity >= (1.0f - 1e-6f)) {
                hnr = 30.0
            } else {
                hnr = -30.0
            }
        }

        val periods = f0sToAnalyze.filter { it > 0 }.map { 1.0 / it }
        val amplitudes = rmsToAnalyze.map { it.toDouble() }

        var jitterLocalRel = 0.0
        var jitterRap = 0.0
        var shimmerLocalRel = 0.0
        var shimmerApq3 = 0.0
        var shimmerShdB = 0.0

        if (periods.size > 1) {
            val meanPeriod = periods.average()
            if (meanPeriod > 1e-9) {
                var sumAbsDiffPeriods = 0.0
                for (i in 0 until periods.size - 1) {
                    sumAbsDiffPeriods += abs(periods[i+1] - periods[i])
                }
                jitterLocalRel = (sumAbsDiffPeriods / (periods.size - 1) / meanPeriod) * 100.0

                if (periods.size >= 3) {
                    var sumRapNum = 0.0
                    for (i in 1 until periods.size - 1) {
                        val localMeanPeriod = (periods[i-1] + periods[i] + periods[i+1]) / 3.0
                        sumRapNum += abs(periods[i] - localMeanPeriod)
                    }
                    jitterRap = (sumRapNum / (periods.size - 2) / meanPeriod) * 100.0
                } else {
                    jitterRap = jitterLocalRel
                }
            }
        }

        if (amplitudes.size > 1 && averageRMS > 1e-9) {
            var sumAbsDiffAmps = 0.0
            for (i in 0 until amplitudes.size - 1) {
                sumAbsDiffAmps += abs(amplitudes[i+1] - amplitudes[i])
            }
            shimmerLocalRel = (sumAbsDiffAmps / (amplitudes.size - 1) / averageRMS) * 100.0

            if (amplitudes.size >= 3) {
                var sumApq3Num = 0.0
                for (i in 1 until amplitudes.size - 1) {
                    val localMeanAmp = (amplitudes[i-1] + amplitudes[i] + amplitudes[i+1]) / 3.0
                    sumApq3Num += abs(amplitudes[i] - localMeanAmp)
                }
                shimmerApq3 = (sumApq3Num / (amplitudes.size - 2) / averageRMS) * 100.0
            } else {
                shimmerApq3 = shimmerLocalRel
            }

            var sumLogRatio = 0.0
            var countValidRatios = 0
            for (i in 0 until amplitudes.size - 1) {
                if (amplitudes[i] > 1e-9 && amplitudes[i+1] > 1e-9) {
                    sumLogRatio += abs(20 * log10(amplitudes[i+1] / amplitudes[i]))
                    countValidRatios++
                }
            }
            if (countValidRatios > 0) {
                shimmerShdB = sumLogRatio / countValidRatios
            }
        }

        val finalJitter = if (jitterRap.isFinite() && jitterRap > 0 && periods.size >=3) jitterRap else jitterLocalRel
        val finalShimmer = if (shimmerApq3.isFinite() && shimmerApq3 > 0 && amplitudes.size >=3) shimmerApq3 else shimmerLocalRel

        val result = AnalysisResult(
            f0 = if (averageF0.isFinite()) averageF0 else 0.0,
            jitter = if (finalJitter.isFinite() && finalJitter >= 0) finalJitter else 0.0,
            shimmer = if (finalShimmer.isFinite() && finalShimmer >= 0) finalShimmer else 0.0,
            intensity = if (intensityDb.isFinite()) intensityDb else -100.0,
            hnr = if (hnr.isFinite()) hnr else 0.0,
            phonationTimeMs = if (phonationTimeMs.isFinite() && phonationTimeMs >= 0) phonationTimeMs else 0.0, // Новое
            f1 = if (f1.isFinite() && f1 > 0) f1 else 0.0, // Новое
            f2 = if (f2.isFinite() && f2 > 0) f2 else 0.0, // Новое
            f3 = if (f3.isFinite() && f3 > 0) f3 else 0.0  // Новое
        )

        Log.d(TAG, "Результаты анализа: F0=${String.format("%.2f",result.f0)}, " +
                "Jitter=${String.format("%.2f",result.jitter)}%, " +
                "Shimmer=${String.format("%.2f",result.shimmer)}%, " +
                "Shimmer(dB)=${String.format("%.2f",shimmerShdB)}, " +
                "HNR=${String.format("%.2f",result.hnr)} dB, " +
                "Intensity=${String.format("%.2f",result.intensity)} dB, " +
                "ВремяФонации=${String.format("%.1f",result.phonationTimeMs)} мс, " + // Новое
                "F1=${String.format("%.1f",result.f1)} Гц, " + // Новое
                "F2=${String.format("%.1f",result.f2)} Гц, " + // Новое
                "F3=${String.format("%.1f",result.f3)} Гц")   // Новое

        listener?.onAnalysisComplete(result, processedAudioPath)
    }


    private fun clearAndResetData() {
        collectedFrameData.clear()
        currentSampleRate = 0f
        outputPathForProcessedAudio = null
    }

    private fun createCustomAudioInputStream(
        codec: MediaCodec, extractor: MediaExtractor, format: TarsosDSPAudioFormat, streamFrameLength: Long
    ): TarsosDSPAudioInputStream {
        return object : TarsosDSPAudioInputStream {
            private val bufferInfo = MediaCodec.BufferInfo()
            private var MACC_inputEOS = false
            private var MACC_outputEOS = false
            private var remainingBytesFromPreviousOutput: ByteBuffer? = null

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (MACC_outputEOS && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining())) {
                    return -1
                }

                var bytesRead = 0
                var outputBuffer: ByteBuffer?

                if (remainingBytesFromPreviousOutput != null && remainingBytesFromPreviousOutput!!.hasRemaining()) {
                    val toRead = kotlin.math.min(len, remainingBytesFromPreviousOutput!!.remaining())
                    remainingBytesFromPreviousOutput!!.get(b, off, toRead)
                    bytesRead += toRead
                    if (!remainingBytesFromPreviousOutput!!.hasRemaining()) {
                        remainingBytesFromPreviousOutput = null
                    }
                    if (bytesRead == len) {
                        return bytesRead
                    }
                }

                while (!MACC_outputEOS && bytesRead < len) {
                    if (!MACC_inputEOS) {
                        val inputBufIndex = codec.dequeueInputBuffer(10000L)
                        if (inputBufIndex >= 0) {
                            val inputBuf = codec.getInputBuffer(inputBufIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                MACC_inputEOS = true
                            } else {
                                codec.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                    if (outputBufIndex >= 0) {
                        outputBuffer = codec.getOutputBuffer(outputBufIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            MACC_outputEOS = true
                        }

                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val toRead = kotlin.math.min(len - bytesRead, bufferInfo.size)
                            outputBuffer.get(b, off + bytesRead, toRead)
                            bytesRead += toRead

                            if (bufferInfo.size > toRead) {
                                val remaining = bufferInfo.size - toRead
                                val temp = ByteBuffer.allocate(remaining)
                                outputBuffer.get(temp.array())
                                remainingBytesFromPreviousOutput = temp
                            }
                        }
                        codec.releaseOutputBuffer(outputBufIndex, false)

                        if (bytesRead == len || MACC_outputEOS) {
                            break
                        }
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Анализ: Формат вывода декодера изменен: " + codec.outputFormat.toString())
                    } else if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (MACC_inputEOS && MACC_outputEOS && bytesRead == 0 && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining())) {
                            return -1;
                        }
                    }
                    if (MACC_outputEOS && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining()) && bytesRead == 0 && outputBufIndex < 0 ) {
                        return if (bytesRead > 0) bytesRead else -1
                    }
                }
                return if (bytesRead > 0) bytesRead else if (MACC_outputEOS && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining())) -1 else 0
            }

            override fun skip(n: Long): Long {
                val buffer = ByteArray(1024.coerceAtMost(n.toInt()))
                var skipped: Long = 0
                while (skipped < n) {
                    val toSkip = kotlin.math.min(n - skipped, buffer.size.toLong()).toInt()
                    val readCount = read(buffer, 0, toSkip)
                    if (readCount == -1) {
                        break
                    }
                    skipped += readCount
                }
                return skipped
            }

            override fun close() { }
            override fun getFormat(): TarsosDSPAudioFormat = format
            override fun getFrameLength(): Long = streamFrameLength
        }
    }
}
