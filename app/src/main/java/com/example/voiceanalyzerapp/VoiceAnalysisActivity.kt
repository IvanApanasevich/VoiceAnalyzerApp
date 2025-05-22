package com.example.voiceanalyzerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder // Android MediaRecorder
import android.media.MediaExtractor // Для чтения файла в analyzeAudioFile
import android.media.MediaFormat   // Для чтения файла в analyzeAudioFile
import android.media.MediaCodec    // Для декодирования в analyzeAudioFile
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.voiceanalyzerapp.databinding.ActivityVoiceAnalysisBinding
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer // Для MediaCodec
import kotlin.math.sqrt // Для RMS

// Импорты TarsosDSP
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.TarsosDSPAudioInputStream
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import be.tarsos.dsp.effects.DelayEffect // Пример эффекта, если нужен
import be.tarsos.dsp.writer.WriterProcessor // Если нужно записывать обработанное аудио

class VoiceAnalysisActivity : AppCompatActivity(), PitchDetectionHandler { // Добавляем PitchDetectionHandler
    private lateinit var binding: ActivityVoiceAnalysisBinding
    private var currentAnalysisType: String = ""

    private var mediaRecorder: MediaRecorder? = null // Android MediaRecorder
    private var mediaPlayer: MediaPlayer? = null
    private var audioFilePath: String? = null
    private var isRecording = false

    private val RECORD_AUDIO_PERMISSION_CODE = 101
    private val TAG = "VoiceAnalysisActivity"

    private val progressUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var progressUpdateRunnable: Runnable
    private val PROGRESS_UPDATE_INTERVAL = 50L

    // --- Переменные для хранения результатов анализа ---
    private val detectedPitches = mutableListOf<Float>()
    private val frameRMSValues = mutableListOf<Float>()
    private var audioDispatcher: AudioDispatcher? = null // Для анализа

    // Параметры для анализа TarsosDSP (можно настраивать)
    private val ANALYSIS_BUFFER_SIZE = 2048
    private val ANALYSIS_OVERLAP = ANALYSIS_BUFFER_SIZE / 2


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        currentAnalysisType = getString(R.string.default_analysis_type)
        //updateAnalysisTypeDisplay()

        // Используем внутренний кэш для большей надежности, если externalCacheDir недоступен
        val cachePath = externalCacheDir?.absolutePath ?: filesDir.absolutePath // filesDir вместо cacheDir для большей персистентности между сессиями
        audioFilePath = "$cachePath/audiorecord.3gp"
        Log.d(TAG, "Audio file path set to: $audioFilePath")

        binding.playerVisualizerView.updateVisualizer(null)
        binding.playerVisualizerView.updatePlayerPercent(0f)

        //binding.btnChangeAnalysisType.setOnClickListener { showAnalysisTypeDialog() }
        binding.btnPlay.setOnClickListener { playRecording() }
        binding.btnShowSpectrogram.setOnClickListener {
            if (audioFilePath != null && File(audioFilePath!!).exists()) {
                val intent = Intent(this, SpectrogramActivity::class.java)
                intent.putExtra("AUDIO_FILE_PATH", audioFilePath)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Сначала запишите аудио", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecordingAndAnalyze() // Изменено
            } else {
                if (checkPermissions()) {
                    startRecording()
                } else {
                    requestPermissions()
                }
            }
        }
        resetCharacteristicsDisplay()
        setupProgressUpdater()
    }

    private fun resetCharacteristicsDisplay() {
        binding.tvF0Value.text = "--- Hz"
        binding.tvJitterValue.text = "--- %" // Указываем единицы
        binding.tvShimmerValue.text = "--- %" // Указываем единицы
        binding.tvNprValue.text = "--- dB"   // NHR -> NPR (Noise to Pitch Ratio или Noise to Harmonic Ratio)
        binding.tvIntensityValue.text = "--- dB"
    }

    // Модифицированный метод stopRecording
    private fun stopRecordingAndAnalyze() {
        if (!isRecording) return

        mediaRecorder?.apply {
            try {
                stop()
                Log.d(TAG, "MediaRecorder stopped successfully.")
            } catch (e: RuntimeException) {
                Log.e(TAG, "stopRecording() failed: ${e.message}")
                // audioFilePath?.let { File(it).delete() } // Не удаляем, если хотим попробовать проанализировать
            } finally {
                release()
            }
        }
        mediaRecorder = null
        isRecording = false
        binding.btnRecord.setImageResource(R.drawable.ic_record)
        binding.btnRecord.contentDescription = getString(R.string.start_recording)
        Toast.makeText(this, "Запись остановлена. Анализ...", Toast.LENGTH_SHORT).show()

        audioFilePath?.let { path ->
            val audioFile = File(path)
            Log.d(TAG, "Checking recorded file at: $path, Exists: ${audioFile.exists()}, Length: ${audioFile.length()}")
            if (audioFile.exists() && audioFile.length() > 0) {
                val audioBytes = fileToBytes(audioFile) // Для PlayerVisualizerView
                if (audioBytes.isNotEmpty()) {
                    binding.playerVisualizerView.updateVisualizer(audioBytes)
                    binding.playerVisualizerView.updatePlayerPercent(0f)
                } else {
                    binding.playerVisualizerView.updateVisualizer(null)
                }
                // Запускаем анализ
                analyzeAudioFile(path)
            } else {
                binding.playerVisualizerView.updateVisualizer(null)
                resetCharacteristicsDisplay()
                Toast.makeText(this, "Файл записи не найден или пуст для анализа.", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // --- Реализация PitchDetectionHandler ---
    override fun handlePitch(pitchDetectionResult: PitchDetectionResult, audioEvent: AudioEvent?) {
        if (pitchDetectionResult.isPitched) {
            detectedPitches.add(pitchDetectionResult.pitch)
        }
        // Вычисляем RMS для текущего фрейма
        audioEvent?.let {
            val rms = calculateRMS(it.floatBuffer)
            frameRMSValues.add(rms.toFloat())
        }
    }

    private fun calculateRMS(audioBuffer: FloatArray): Double {
        var sumOfSquares = 0.0
        for (sample in audioBuffer) {
            sumOfSquares += sample * sample
        }
        return sqrt(sumOfSquares / audioBuffer.size)
    }


    private fun analyzeAudioFile(filePath: String) {
        detectedPitches.clear()
        frameRMSValues.clear()

        val audioFile = File(filePath)
        if (!audioFile.exists()) {
            Log.e(TAG, "analyzeAudioFile: Файл не найден $filePath")
            runOnUiThread { Toast.makeText(this, "Файл для анализа не найден.", Toast.LENGTH_SHORT).show() }
            return
        }

        // Используем тот же подход с MediaExtractor/MediaCodec, что и в SpectrogramActivity
        // для обеспечения консистентности и обхода проблем с javax.sound
        Thread {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            var actualSampleRate = 0f
            lateinit var tarsosFormat: TarsosDSPAudioFormat

            try {
                extractor = MediaExtractor()
                extractor.setDataSource(audioFile.absolutePath)
                var audioTrackIndex = -1
                var inputFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) {
                        audioTrackIndex = i
                        inputFormat = format
                        actualSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toFloat()
                        break
                    }
                }

                if (audioTrackIndex == -1 || inputFormat == null || actualSampleRate == 0f) {
                    Log.e(TAG, "analyzeAudioFile: Не удалось найти аудиодорожку или параметры.")
                    runOnUiThread { Toast.makeText(this, "Ошибка анализа файла.", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                extractor.selectTrack(audioTrackIndex)
                val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)!!
                codec = MediaCodec.createDecoderByType(mimeType)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                tarsosFormat = TarsosDSPAudioFormat(actualSampleRate, 16, 1, true, false)
                val frameLength = -1L // Длина не так важна для этого анализа, т.к. читаем до конца

                val customAudioStream = createCustomAudioInputStreamForAnalysis(codec, extractor, tarsosFormat, frameLength)
                audioDispatcher = AudioDispatcher(customAudioStream, ANALYSIS_BUFFER_SIZE, ANALYSIS_OVERLAP)

                // Добавляем PitchProcessor
                val pitchAlgorithm = PitchProcessor.PitchEstimationAlgorithm.YIN
                val pitchProcessor = PitchProcessor(pitchAlgorithm, actualSampleRate, ANALYSIS_BUFFER_SIZE, this)
                audioDispatcher?.addAudioProcessor(pitchProcessor)

                // Можно добавить простой AudioProcessor для отладки или сбора других данных, если нужно
                audioDispatcher?.addAudioProcessor(object : AudioProcessor {
                    override fun process(audioEvent: AudioEvent?): Boolean {
                        // handlePitch уже вызывается pitchProcessor'ом и собирает RMS
                        return true // Продолжаем обработку
                    }
                    override fun processingFinished() {
                        Log.d(TAG, "Анализ TarsosDSP завершен.")
                        // Вычисляем и отображаем характеристики после завершения
                        calculateAndDisplayCharacteristics()
                    }
                })

                audioDispatcher?.run() // Запускаем обработку

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка во время анализа аудио: ${e.message}", e)
                runOnUiThread { Toast.makeText(this, "Ошибка анализа: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } finally {
                try {
                    codec?.stop()
                    codec?.release()
                    extractor?.release()
                } catch (e: Exception) { Log.e(TAG, "Ошибка при освобождении ресурсов анализа: ${e.message}") }
                audioDispatcher = null // Освобождаем dispatcher
            }
        }.start()
    }


    // Вспомогательный метод для создания TarsosDSPAudioInputStream (похож на тот, что в SpectrogramActivity)
    private fun createCustomAudioInputStreamForAnalysis(
        codec: MediaCodec,
        extractor: MediaExtractor,
        format: TarsosDSPAudioFormat,
        streamFrameLength: Long // Можно передать -1L, если длина неизвестна или не важна
    ): TarsosDSPAudioInputStream {
        return object : TarsosDSPAudioInputStream {
            private val bufferInfo = MediaCodec.BufferInfo()
            private var MACC_inputEOS = false
            private var MACC_outputEOS = false
            private var remainingBytesFromPreviousOutput: ByteBuffer? = null

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (MACC_outputEOS && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining())) {
                    return -1 // Конец потока
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
                    if (bytesRead == len) return bytesRead
                }

                while (!MACC_outputEOS && bytesRead < len) {
                    if (!MACC_inputEOS) {
                        val inputBufIndex = codec.dequeueInputBuffer(10000L) // Таймаут
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

                    val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L) // Таймаут
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
                        if (bytesRead == len || MACC_outputEOS) break
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Анализ: Формат вывода декодера изменен: " + codec.outputFormat.toString())
                    } else if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (MACC_inputEOS && MACC_outputEOS && bytesRead == 0 && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining())) return -1
                    }
                    if (MACC_outputEOS && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining()) && bytesRead == 0 && outputBufIndex <0 ){
                        return if (bytesRead > 0) bytesRead else -1
                    }
                }
                return if (bytesRead > 0) bytesRead else if (MACC_outputEOS && (remainingBytesFromPreviousOutput == null || !remainingBytesFromPreviousOutput!!.hasRemaining())) -1 else 0
            }

            override fun skip(n: Long): Long { /* ... как в SpectrogramActivity ... */
                val buffer = ByteArray(1024.coerceAtMost(n.toInt()))
                var skipped: Long = 0
                while (skipped < n) {
                    val toSkip = kotlin.math.min(n - skipped, buffer.size.toLong()).toInt()
                    val readCount = read(buffer, 0, toSkip)
                    if (readCount == -1) break
                    skipped += readCount
                }
                return skipped
            }
            override fun close() { /* Ресурсы освобождаются в finally */ }
            override fun getFormat(): TarsosDSPAudioFormat = format
            override fun getFrameLength(): Long = streamFrameLength // Может быть -1L
        }
    }

    private fun calculateAndDisplayCharacteristics() {
        // F0 (Средняя основная частота)
        val averageF0AsDouble = if (detectedPitches.isNotEmpty()) detectedPitches.average() else 0.0 // Результат average() уже Double
        val f0Text = if (averageF0AsDouble > 0) String.format("%.2f Hz", averageF0AsDouble) else "--- Hz"

        // Intensity (Средняя RMS, преобразованная в dB)
        val averageRMSAsDouble = if (frameRMSValues.isNotEmpty()) frameRMSValues.map { it.toDouble() }.average() else 0.0
        val intensityDb = if (averageRMSAsDouble > 0.00001) 20 * kotlin.math.log10(averageRMSAsDouble) else -100.0
        val intensityText = String.format("%.2f dB", intensityDb) // intensityDb уже Double

        // Упрощенный Jitter
        val jitterPercentageAsDouble = if (detectedPitches.size > 1 && averageF0AsDouble > 0) {
            // averageF0AsDouble уже посчитан
            val stdDev = kotlin.math.sqrt(detectedPitches.map { val diff = it.toDouble() - averageF0AsDouble; diff * diff }.average())
            (stdDev / averageF0AsDouble * 100.0)
        } else 0.0
        val jitterText = if (jitterPercentageAsDouble > 0) String.format("%.2f %%", jitterPercentageAsDouble) else "--- %" // Строка ~383

        // Упрощенный Shimmer
        val shimmerPercentageAsDouble = if (frameRMSValues.size > 1 && averageRMSAsDouble > 0.00001) {
            // averageRMSAsDouble уже посчитан
            val stdDevRms = kotlin.math.sqrt(frameRMSValues.map { val diff = it.toDouble() - averageRMSAsDouble; diff * diff }.average())
            (stdDevRms / averageRMSAsDouble * 100.0)
        } else 0.0
        val shimmerText = if (shimmerPercentageAsDouble > 0) String.format("%.2f %%", shimmerPercentageAsDouble) else "--- %" // Строка ~400

        val nhrText = "--- dB" // Заглушка для NHR // Строка ~405 (здесь нет форматирования, но ошибка могла быть связана с предыдущими)

        runOnUiThread {
            binding.tvF0Value.text = f0Text
            binding.tvIntensityValue.text = intensityText
            binding.tvJitterValue.text = jitterText
            binding.tvShimmerValue.text = shimmerText
            binding.tvNprValue.text = nhrText

            Toast.makeText(this, "Анализ завершен!", Toast.LENGTH_SHORT).show()
        }
    }

    // Остальные методы (setupProgressUpdater, start/stopProgressUpdater, fileToBytes, UI хелперы, запись и воспроизведение)
    // в основном остаются без изменений, кроме stopRecording, который теперь stopRecordingAndAnalyze.
    // --- Начало нетронутых методов ---
    private fun setupProgressUpdater() {
        progressUpdateRunnable = Runnable {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    try {
                        val currentPosition = it.currentPosition
                        val duration = it.duration
                        if (duration > 0) {
                            val percent = currentPosition.toFloat() / duration.toFloat()
                            binding.playerVisualizerView.updatePlayerPercent(percent)
                        }
                        progressUpdateHandler.postDelayed(progressUpdateRunnable, PROGRESS_UPDATE_INTERVAL)
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Error updating progress: ${e.message}")
                        stopProgressUpdater()
                    }
                } else {
                    if (it.duration > 0 && it.currentPosition >= it.duration - PROGRESS_UPDATE_INTERVAL*2) {
                        binding.playerVisualizerView.updatePlayerPercent(1.0f)
                    }
                    stopProgressUpdater()
                }
            }
        }
    }

    private fun startProgressUpdater() {
        stopProgressUpdater()
        progressUpdateHandler.post(progressUpdateRunnable)
    }

    private fun stopProgressUpdater() {
        progressUpdateHandler.removeCallbacks(progressUpdateRunnable)
    }

    private fun fileToBytes(file: File): ByteArray {
        val size = file.length().toInt()
        val bytes = ByteArray(size)
        try {
            val buf = BufferedInputStream(FileInputStream(file))
            buf.read(bytes, 0, bytes.size)
            buf.close()
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found: ${e.message}")
            return ByteArray(0)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading file: ${e.message}")
            return ByteArray(0)
        }
        return bytes
    }

    override fun onSupportNavigateUp(): Boolean {
        val intent = Intent(this, MainMenuActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        Log.d(TAG, "Back pressed, navigating to MainMenuActivity.")
        val intent = Intent(this, MainMenuActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

//    private fun updateAnalysisTypeDisplay() {
//        binding.tvAnalysisTypeLabel.text = getString(R.string.analysis_type_template, currentAnalysisType)
//    }

    private fun showAnalysisTypeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_analysis_type, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.rgAnalysisTypes)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmSelection)
        when (currentAnalysisType) {
            getString(R.string.speech) -> radioGroup.check(R.id.rbSpeech)
            getString(R.string.letter_a) -> radioGroup.check(R.id.rbLetterA)
            getString(R.string.letter_e) -> radioGroup.check(R.id.rbLetterE)
            getString(R.string.letter_u) -> radioGroup.check(R.id.rbLetterU)
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_VoiceAnalyzerApp_Dialog).setView(dialogView).create()
        btnConfirm.setOnClickListener {
            val selectedId = radioGroup.checkedRadioButtonId
            if (selectedId != -1) {
                val radioButton = radioGroup.findViewById<RadioButton>(selectedId)
                currentAnalysisType = radioButton.text.toString()
                //updateAnalysisTypeDisplay()
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording() // Разрешение получено, начинаем запись
            } else {
                Toast.makeText(this, "Разрешение на запись отклонено", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        audioFilePath?.let {
            val file = File(it)
            if (file.exists()) {
                file.delete()
            }
        }
        resetCharacteristicsDisplay()
        binding.playerVisualizerView.updateVisualizer(null)
        binding.playerVisualizerView.updatePlayerPercent(0f)
        mediaPlayer?.release()
        mediaPlayer = null
        stopProgressUpdater()

        // Инициализация MediaRecorder с учетом версии API
        val mr: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // S это API 31
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") // Подавляем предупреждение для старого конструктора
            MediaRecorder()
        }

        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        mr.setOutputFile(audioFilePath)

        this.mediaRecorder = mr

        Log.d(TAG, "Attempting to record to: $audioFilePath")
        try {
            mr.prepare()
            mr.start()
            isRecording = true
            binding.btnRecord.setImageResource(android.R.drawable.ic_media_pause)
            binding.btnRecord.contentDescription = getString(R.string.stop_recording)
            Toast.makeText(this@VoiceAnalysisActivity, "Запись началась...", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder prepare() failed for $audioFilePath: ${e.message}")
            Toast.makeText(this@VoiceAnalysisActivity, "Ошибка начала записи: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
            binding.btnRecord.setImageResource(R.drawable.ic_record)
            this.mediaRecorder?.release() // Освобождаем, если ошибка
            this.mediaRecorder = null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaRecorder start failed for $audioFilePath: ${e.message}")
            Toast.makeText(this@VoiceAnalysisActivity, "Ошибка старта записи: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
            binding.btnRecord.setImageResource(R.drawable.ic_record)
            this.mediaRecorder?.release() // Освобождаем, если ошибка
            this.mediaRecorder = null
        }
    }
    // stopRecording был заменен на stopRecordingAndAnalyze
    private fun playRecording() {
        if (isRecording) {
            Toast.makeText(this, "Сначала остановите запись", Toast.LENGTH_SHORT).show()
            return
        }
        if (audioFilePath != null && File(audioFilePath!!).exists()) {
            if (File(audioFilePath!!).length() == 0L) {
                Toast.makeText(this, "Файл записи пуст", Toast.LENGTH_SHORT).show()
                return
            }
            mediaPlayer?.release()
            stopProgressUpdater()
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(audioFilePath)
                    prepare()
                    start()
                    binding.playerVisualizerView.updatePlayerPercent(0f)
                    startProgressUpdater()
                    Toast.makeText(this@VoiceAnalysisActivity, "Воспроизведение...", Toast.LENGTH_SHORT).show()
                    setOnCompletionListener {
                        Toast.makeText(this@VoiceAnalysisActivity, "Воспроизведение завершено", Toast.LENGTH_SHORT).show()
                        it.release()
                        mediaPlayer = null
                        stopProgressUpdater()
                        binding.playerVisualizerView.updatePlayerPercent(1.0f)
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer Error: what $what, extra $extra")
                        Toast.makeText(this@VoiceAnalysisActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                        mp.release()
                        mediaPlayer = null
                        stopProgressUpdater()
                        binding.playerVisualizerView.updatePlayerPercent(0f)
                        true
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "MediaPlayer prepare() failed: ${e.message}")
                    Toast.makeText(this@VoiceAnalysisActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                    release()
                    mediaPlayer = null
                    stopProgressUpdater()
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "MediaPlayer state error: ${e.message}")
                    Toast.makeText(this@VoiceAnalysisActivity, "Ошибка состояния плеера", Toast.LENGTH_SHORT).show()
                    release()
                    mediaPlayer = null
                    stopProgressUpdater()
                }
            }
        } else {
            Toast.makeText(this, "Нет записи для воспроизведения", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
        stopProgressUpdater()
        audioDispatcher?.stop() // Останавливаем анализ, если он идет
        audioDispatcher = null
    }
    // --- Конец нетронутых методов ---
}

//package com.example.voiceanalyzerapp
//
//import android.Manifest
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.media.MediaPlayer
//import android.media.MediaRecorder
//import android.os.Bundle
//import android.os.Handler
//import android.os.Looper
//import android.util.Log
//import android.widget.Button
//import android.widget.RadioButton
//import android.widget.RadioGroup
//import android.widget.Toast
//import androidx.appcompat.app.AlertDialog
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
//import com.example.voiceanalyzerapp.databinding.ActivityVoiceAnalysisBinding
//import java.io.BufferedInputStream
//import java.io.File
//import java.io.FileInputStream
//import java.io.FileNotFoundException
//import java.io.IOException
//
//class VoiceAnalysisActivity : AppCompatActivity() {
//    private lateinit var binding: ActivityVoiceAnalysisBinding
//    private var currentAnalysisType: String = ""
//
//    private var mediaRecorder: MediaRecorder? = null
//    private var mediaPlayer: MediaPlayer? = null
//    private var audioFilePath: String? = null
//    private var isRecording = false
//
//    private val RECORD_AUDIO_PERMISSION_CODE = 101
//
//    // For updating playback progress on PlayerVisualizerView
//    private val progressUpdateHandler = Handler(Looper.getMainLooper())
//    private lateinit var progressUpdateRunnable: Runnable
//    private val PROGRESS_UPDATE_INTERVAL = 50L // milliseconds
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        binding = ActivityVoiceAnalysisBinding.inflate(layoutInflater)
//        setContentView(binding.root)
//
//        setSupportActionBar(binding.toolbar)
//        supportActionBar?.setDisplayHomeAsUpEnabled(true)
//        supportActionBar?.setDisplayShowHomeEnabled(true)
//
//        currentAnalysisType = getString(R.string.default_analysis_type)
//        updateAnalysisTypeDisplay()
//
//        audioFilePath = "${externalCacheDir?.absolutePath}/audiorecord.3gp"
//        Log.d("VoiceAnalysisActivity", "Audio file path set to: $audioFilePath")
//
//        // Initialize PlayerVisualizerView
//        binding.playerVisualizerView.updateVisualizer(null) // Clear it initially
//        binding.playerVisualizerView.updatePlayerPercent(0f)
//
//
//        binding.btnChangeAnalysisType.setOnClickListener {
//            showAnalysisTypeDialog()
//        }
//
//        binding.btnPlay.setOnClickListener {
//            playRecording()
//        }
//
////        binding.btnShowSpectrogram.setOnClickListener {
////            if (audioFilePath != null && File(audioFilePath!!).exists()) {
////                val intent = Intent(this, SpectrogramActivity::class.java)
////                // intent.putExtra("AUDIO_FILE_PATH", audioFilePath) // Pass path if needed
////                startActivity(intent)
////            } else {
////                Toast.makeText(this, "Сначала запишите аудио", Toast.LENGTH_SHORT).show()
////            }
////        }
//
//        binding.btnShowSpectrogram.setOnClickListener {
//            if (audioFilePath != null && File(audioFilePath!!).exists()) {
//                val intent = Intent(this, SpectrogramActivity::class.java)
//                intent.putExtra("AUDIO_FILE_PATH", audioFilePath) // <<< UNCOMMENT AND ENSURE THIS LINE IS PRESENT
//                startActivity(intent)
//            } else {
//                Toast.makeText(this, "Сначала запишите аудио", Toast.LENGTH_SHORT).show()
//            }
//        }
//
//
//        binding.btnRecord.setOnClickListener {
//            if (isRecording) {
//                stopRecording()
//            } else {
//                if (checkPermissions()) {
//                    startRecording()
//                } else {
//                    requestPermissions()
//                }
//            }
//        }
//
//        binding.tvF0Value.text = "--- Hz"
//        binding.tvJitterValue.text = "-----"
//
//        setupProgressUpdater()
//    }
//
//    private fun setupProgressUpdater() {
//        progressUpdateRunnable = Runnable {
//            mediaPlayer?.let {
//                if (it.isPlaying) {
//                    try {
//                        val currentPosition = it.currentPosition
//                        val duration = it.duration
//                        if (duration > 0) {
//                            val percent = currentPosition.toFloat() / duration.toFloat()
//                            binding.playerVisualizerView.updatePlayerPercent(percent)
//                        }
//                        progressUpdateHandler.postDelayed(progressUpdateRunnable, PROGRESS_UPDATE_INTERVAL)
//                    } catch (e: IllegalStateException) {
//                        // MediaPlayer might not be in a valid state
//                        Log.e("VoiceAnalysisActivity", "Error updating progress: ${e.message}")
//                        stopProgressUpdater()
//                    }
//                } else {
//                    // If not playing but was supposed to be updating, ensure it's at 100% if near end
//                    if (it.duration > 0 && it.currentPosition >= it.duration - PROGRESS_UPDATE_INTERVAL*2) {
//                        binding.playerVisualizerView.updatePlayerPercent(1.0f)
//                    }
//                    stopProgressUpdater()
//                }
//            }
//        }
//    }
//
//    private fun startProgressUpdater() {
//        stopProgressUpdater() // Stop any existing updater
//        progressUpdateHandler.post(progressUpdateRunnable)
//    }
//
//    private fun stopProgressUpdater() {
//        progressUpdateHandler.removeCallbacks(progressUpdateRunnable)
//    }
//
//
//    // Helper method to convert file to bytes (as provided in StackOverflow)
//    private fun fileToBytes(file: File): ByteArray {
//        val size = file.length().toInt()
//        val bytes = ByteArray(size)
//        try {
//            val buf = BufferedInputStream(FileInputStream(file))
//            buf.read(bytes, 0, bytes.size)
//            buf.close()
//        } catch (e: FileNotFoundException) {
//            Log.e("VoiceAnalysisActivity", "File not found: ${e.message}")
//            return ByteArray(0) // Return empty array on error
//        } catch (e: IOException) {
//            Log.e("VoiceAnalysisActivity", "Error reading file: ${e.message}")
//            return ByteArray(0) // Return empty array on error
//        }
//        return bytes
//    }
//
//
//    override fun onSupportNavigateUp(): Boolean {
//        val intent = Intent(this, MainMenuActivity::class.java)
//        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
//        startActivity(intent)
//        finish()
//        return true
//    }
//
//    @Deprecated("Deprecated in Java")
//    @Suppress("DEPRECATION")
//    override fun onBackPressed() {
//        Log.d("VoiceAnalysisActivity", "Back pressed, navigating to MainMenuActivity.")
//        val intent = Intent(this, MainMenuActivity::class.java)
//        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
//        startActivity(intent)
//        finish()
//    }
//
//
//    private fun updateAnalysisTypeDisplay() {
//        binding.tvAnalysisTypeLabel.text = getString(R.string.analysis_type_template, currentAnalysisType)
//    }
//
//    private fun showAnalysisTypeDialog() {
//        val dialogView = layoutInflater.inflate(R.layout.dialog_select_analysis_type, null)
//        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.rgAnalysisTypes)
//        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmSelection)
//
//        when (currentAnalysisType) {
//            getString(R.string.speech) -> radioGroup.check(R.id.rbSpeech)
//            getString(R.string.letter_a) -> radioGroup.check(R.id.rbLetterA)
//            getString(R.string.letter_e) -> radioGroup.check(R.id.rbLetterE)
//            getString(R.string.letter_u) -> radioGroup.check(R.id.rbLetterU)
//        }
//
//        val dialog = AlertDialog.Builder(this, R.style.Theme_VoiceAnalyzerApp_Dialog)
//            .setView(dialogView)
//            .create()
//
//        btnConfirm.setOnClickListener {
//            val selectedId = radioGroup.checkedRadioButtonId
//            if (selectedId != -1) {
//                val radioButton = radioGroup.findViewById<RadioButton>(selectedId)
//                currentAnalysisType = radioButton.text.toString()
//                updateAnalysisTypeDisplay()
//            }
//            dialog.dismiss()
//        }
//        dialog.show()
//    }
//
//    private fun checkPermissions(): Boolean {
//        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
//    }
//
//    private fun requestPermissions() {
//        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
//    }
//
//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
//            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                startRecording()
//            } else {
//                Toast.makeText(this, "Разрешение на запись отклонено", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    private fun startRecording() {
//        audioFilePath?.let {
//            val file = File(it)
//            if (file.exists()) {
//                file.delete()
//            }
//        }
//        // Clear characteristics and waveform
//        binding.playerVisualizerView.updateVisualizer(null)
//        binding.playerVisualizerView.updatePlayerPercent(0f)
//        binding.tvF0Value.text = "--- Hz"
//        binding.tvJitterValue.text = "-----"
//        // ... reset others
//
//        // Stop any ongoing playback and its progress updater
//        mediaPlayer?.release()
//        mediaPlayer = null
//        stopProgressUpdater()
//
//        mediaRecorder = MediaRecorder().apply {
//            setAudioSource(MediaRecorder.AudioSource.MIC)
//            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
//            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
//            setOutputFile(audioFilePath)
//            try {
//                prepare()
//                start()
//                isRecording = true
//                binding.btnRecord.setImageResource(android.R.drawable.ic_media_pause)
//                binding.btnRecord.contentDescription = getString(R.string.stop_recording)
//                Toast.makeText(this@VoiceAnalysisActivity, "Запись началась...", Toast.LENGTH_SHORT).show()
//            } catch (e: IOException) {
//                Log.e("VoiceAnalysisActivity", "prepare() failed: ${e.message}")
//                Toast.makeText(this@VoiceAnalysisActivity, "Ошибка начала записи", Toast.LENGTH_SHORT).show()
//                isRecording = false
//                binding.btnRecord.setImageResource(R.drawable.ic_record)
//            } catch (e: IllegalStateException) {
//                Log.e("VoiceAnalysisActivity", "MediaRecorder start failed: ${e.message}")
//                Toast.makeText(this@VoiceAnalysisActivity, "Ошибка старта записи", Toast.LENGTH_SHORT).show()
//                isRecording = false
//                binding.btnRecord.setImageResource(R.drawable.ic_record)
//            }
//        }
//    }
//
//    private fun stopRecording() {
//        if (!isRecording) return // Avoid stopping if not recording
//
//        mediaRecorder?.apply {
//            try {
//                stop()
//            } catch (e: RuntimeException) {
//                Log.e("VoiceAnalysisActivity", "stopRecording() failed: ${e.message}")
//                audioFilePath?.let { File(it).delete() } // Delete potentially corrupt file
//            } finally {
//                release() // Always release
//            }
//        }
//        mediaRecorder = null
//        isRecording = false
//        binding.btnRecord.setImageResource(R.drawable.ic_record)
//        binding.btnRecord.contentDescription = getString(R.string.start_recording)
//        Toast.makeText(this, "Запись остановлена.", Toast.LENGTH_SHORT).show()
//
//        audioFilePath?.let { path ->
//            val audioFile = File(path)
//            if (audioFile.exists() && audioFile.length() > 0) {
//                val audioBytes = fileToBytes(audioFile)
//                if (audioBytes.isNotEmpty()) {
//                    binding.playerVisualizerView.updateVisualizer(audioBytes)
//                    binding.playerVisualizerView.updatePlayerPercent(0f) // Reset progress
//                    // Simulate analysis
//                    binding.tvF0Value.text = "150 Hz"
//                    binding.tvJitterValue.text = "0.5%"
//                    binding.tvShimmerValue.text = "2.1%"
//                    binding.tvNprValue.text = "15 dB"
//                    binding.tvIntensityValue.text = "70 dB"
//                } else {
//                    binding.playerVisualizerView.updateVisualizer(null)
//                    Toast.makeText(this, "Файл записи пуст или поврежден.", Toast.LENGTH_SHORT).show()
//                }
//            } else {
//                binding.playerVisualizerView.updateVisualizer(null)
//                Toast.makeText(this, "Файл записи не найден или пуст.", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    private fun playRecording() {
//        if (isRecording) {
//            Toast.makeText(this, "Сначала остановите запись", Toast.LENGTH_SHORT).show()
//            return
//        }
//        if (audioFilePath != null && File(audioFilePath!!).exists()) {
//            if (File(audioFilePath!!).length() == 0L) {
//                Toast.makeText(this, "Файл записи пуст", Toast.LENGTH_SHORT).show()
//                return
//            }
//
//            mediaPlayer?.release() // Release previous player if any
//            stopProgressUpdater()  // Stop any previous progress updates
//
//            mediaPlayer = MediaPlayer().apply {
//                try {
//                    setDataSource(audioFilePath)
//                    prepare()
//                    start()
//                    binding.playerVisualizerView.updatePlayerPercent(0f) // Reset progress for new playback
//                    startProgressUpdater() // Start updating visualizer progress
//                    Toast.makeText(this@VoiceAnalysisActivity, "Воспроизведение...", Toast.LENGTH_SHORT).show()
//
//                    setOnCompletionListener {
//                        Toast.makeText(this@VoiceAnalysisActivity, "Воспроизведение завершено", Toast.LENGTH_SHORT).show()
//                        it.release()
//                        mediaPlayer = null
//                        stopProgressUpdater()
//                        binding.playerVisualizerView.updatePlayerPercent(1.0f) // Ensure it shows 100%
//                    }
//                    setOnErrorListener { mp, what, extra ->
//                        Log.e("VoiceAnalysisActivity", "MediaPlayer Error: what $what, extra $extra")
//                        Toast.makeText(this@VoiceAnalysisActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
//                        mp.release()
//                        mediaPlayer = null
//                        stopProgressUpdater()
//                        binding.playerVisualizerView.updatePlayerPercent(0f)
//                        true // Error handled
//                    }
//
//                } catch (e: IOException) {
//                    Log.e("VoiceAnalysisActivity", "MediaPlayer prepare() failed: ${e.message}")
//                    Toast.makeText(this@VoiceAnalysisActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
//                    release()
//                    mediaPlayer = null
//                    stopProgressUpdater()
//                } catch (e: IllegalStateException) {
//                    Log.e("VoiceAnalysisActivity", "MediaPlayer state error: ${e.message}")
//                    Toast.makeText(this@VoiceAnalysisActivity, "Ошибка состояния плеера", Toast.LENGTH_SHORT).show()
//                    release()
//                    mediaPlayer = null
//                    stopProgressUpdater()
//                }
//            }
//        } else {
//            Toast.makeText(this, "Нет записи для воспроизведения", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    override fun onStop() {
//        super.onStop()
//        mediaRecorder?.release()
//        mediaRecorder = null
//        mediaPlayer?.release()
//        mediaPlayer = null
//        stopProgressUpdater()
//    }
//}