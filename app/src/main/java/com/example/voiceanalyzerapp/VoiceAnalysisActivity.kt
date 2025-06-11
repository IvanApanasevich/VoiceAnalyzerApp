package com.example.voiceanalyzerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
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
import androidx.core.view.isVisible // Для управления видимостью кнопок
import com.example.voiceanalyzerapp.databinding.ActivityVoiceAnalysisBinding
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

class VoiceAnalysisActivity : AppCompatActivity(), AudioAnalyzer.AnalysisListener {
    private lateinit var binding: ActivityVoiceAnalysisBinding
    private var currentAnalysisType: String = ""

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null

    // Основной файл для воспроизводимого/анализируемого аудио (после обработки)
    private var playableAudioFilePath: String? = null
    // Временный файл для сырой записи
    private var rawRecordingFilePath: String? = null

    private var isRecording = false

    private val RECORD_AUDIO_PERMISSION_CODE = 101
    private val TAG = "VoiceAnalysisActivity"

    private val progressUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var progressUpdateRunnable: Runnable
    private val PROGRESS_UPDATE_INTERVAL = 50L

    private lateinit var audioAnalyzer: AudioAnalyzer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        currentAnalysisType = getString(R.string.default_analysis_type)

        val cacheDir = externalCacheDir?.absolutePath ?: filesDir.absolutePath
        playableAudioFilePath = "$cacheDir/analyzed_audiorecord.wav" // Сохраняем в WAV
        rawRecordingFilePath = "$cacheDir/raw_audiorecord.3gp"

        Log.d(TAG, "Playable audio file path: $playableAudioFilePath")
        Log.d(TAG, "Raw recording file path: $rawRecordingFilePath")

        // Очистка кеша при входе
        clearCacheFiles()
        updatePlayButtonState() // Обновляем состояние кнопки Play

        binding.playerVisualizerView.updateVisualizer(null)
        binding.playerVisualizerView.updatePlayerPercent(0f)

        binding.btnPlay.setOnClickListener { playRecording() }
        binding.btnShowSpectrogram.setOnClickListener {
            val currentAudioFile = playableAudioFilePath?.let { File(it) }
            if (currentAudioFile != null && currentAudioFile.exists() && currentAudioFile.length() > 0) {
                val intent = Intent(this, SpectrogramActivity::class.java)
                intent.putExtra("AUDIO_FILE_PATH", playableAudioFilePath) // Используем обработанный файл
                startActivity(intent)
            } else {
                Toast.makeText(this, "Сначала запишите и проанализируйте аудио", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecordingAndAnalyze()
            } else {
                if (checkPermissions()) {
                    startRecording()
                } else {
                    requestPermissions()
                }
            }
        }

        audioAnalyzer = AudioAnalyzer(applicationContext)
        audioAnalyzer.setAnalysisListener(this)

        resetCharacteristicsDisplay()
        setupProgressUpdater()
    }

    private fun clearCacheFiles() {
        rawRecordingFilePath?.let { File(it).delete() }
        playableAudioFilePath?.let { File(it).delete() }
        Log.d(TAG, "Cache files cleared.")
    }

    private fun updatePlayButtonState() {
        val canPlay = playableAudioFilePath?.let { File(it).exists() && File(it).length() > 0 } == true
        binding.btnPlay.isEnabled = canPlay
        binding.btnPlay.alpha = if (canPlay) 1.0f else 0.5f // Визуальная индикация доступности
        binding.btnShowSpectrogram.isEnabled = canPlay
        binding.btnShowSpectrogram.alpha = if (canPlay) 1.0f else 0.5f
    }


    private fun resetCharacteristicsDisplay() {
        binding.tvF0Value.text = "--- Hz"
        binding.tvJitterValue.text = "--- %"
        binding.tvShimmerValue.text = "--- %"
        binding.tvNprValue.text = "--- dB"
        binding.tvIntensityValue.text = "--- dB"
    }

    private fun stopRecordingAndAnalyze() {
        if (!isRecording) return

        mediaRecorder?.apply {
            try {
                stop()
                Log.d(TAG, "MediaRecorder stopped successfully. Raw file: $rawRecordingFilePath")
            } catch (e: RuntimeException) {
                Log.e(TAG, "stopRecording() failed: ${e.message}")
                // Если остановка не удалась, возможно, файл поврежден или не создан
                rawRecordingFilePath?.let { File(it).delete() }
            } finally {
                release()
            }
        }
        mediaRecorder = null
        isRecording = false
        binding.btnRecord.setImageResource(R.drawable.ic_record)
        binding.btnRecord.contentDescription = getString(R.string.start_recording)


        val rawFile = rawRecordingFilePath?.let { File(it) }
        if (rawFile != null && rawFile.exists() && rawFile.length() > 0) {
            Toast.makeText(this, "Запись остановлена. Анализ...", Toast.LENGTH_SHORT).show()
            runOnUiThread {
                binding.tvF0Value.text = "Анализ..."
                // ... (остальные текстовые поля)
            }
            // Передаем путь для сохранения обработанного аудио
            audioAnalyzer.analyze(rawFile.absolutePath, playableAudioFilePath)
        } else {
            Toast.makeText(this, "Ошибка записи или файл пуст. Попробуйте снова.", Toast.LENGTH_LONG).show()
            binding.playerVisualizerView.updateVisualizer(null)
            resetCharacteristicsDisplay()
            updatePlayButtonState() // Обновляем доступность кнопки Play
        }
    }

    // --- AudioAnalyzer.AnalysisListener Implementation ---
    override fun onAnalysisComplete(result: AnalysisResult, processedAudioPath: String?) {
        runOnUiThread {
            // Удаляем сырой файл после успешного анализа (или если обработанный не создался)
            rawRecordingFilePath?.let { File(it).delete() }
            Log.d(TAG, "Raw recording file deleted after analysis.")

            if (result.errorMessage != null) {
                Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show()
                resetCharacteristicsDisplay()
                // Если была ошибка, но processedAudioPath все же есть (маловероятно, но для безопасности)
                // или если его нет, то удаляем и playableAudioFilePath
                if (processedAudioPath == null) {
                    playableAudioFilePath?.let { File(it).delete() }
                }
                updatePlayButtonState()
                binding.playerVisualizerView.updateVisualizer(null) // Очищаем визуализатор
                return@runOnUiThread
            }

            // Если анализ успешен и processedAudioPath не null, то это наш новый playableAudioFilePath
            if (processedAudioPath != null) {
                this.playableAudioFilePath = processedAudioPath // Обновляем, если имя файла изменилось (хотя мы передавали то же)
                val audioFile = File(processedAudioPath)
                if (audioFile.exists() && audioFile.length() > 0) {
                    val audioBytes = fileToBytes(audioFile) // Для PlayerVisualizerView
                    binding.playerVisualizerView.updateVisualizer(audioBytes.takeIf { it.isNotEmpty() })
                    binding.playerVisualizerView.updatePlayerPercent(0f)
                } else {
                    binding.playerVisualizerView.updateVisualizer(null)
                }
            } else {
                // Анализ успешен, но файл не был сохранен (ошибка сохранения в AudioAnalyzer)
                Toast.makeText(this, "Анализ успешен, но не удалось сохранить обработанный файл.", Toast.LENGTH_LONG).show()
                playableAudioFilePath?.let { File(it).delete() } // Удаляем, если он вдруг был создан некорректно
                binding.playerVisualizerView.updateVisualizer(null)
            }

            binding.tvF0Value.text = if (result.f0 > 0) String.format("%.2f Hz", result.f0) else "--- Hz"
            binding.tvJitterValue.text = if (result.jitter > 0) String.format("%.2f %%", result.jitter) else "--- %"
            binding.tvShimmerValue.text = if (result.shimmer > 0) String.format("%.2f %%", result.shimmer) else "--- %"
            binding.tvNprValue.text = if (result.hnr != 0.0) String.format("%.2f dB", result.hnr) else "--- dB"
            binding.tvIntensityValue.text = String.format("%.2f dB", result.intensity)

            Toast.makeText(this, "Анализ завершен!", Toast.LENGTH_SHORT).show()
            updatePlayButtonState()
        }
    }

    override fun onAnalysisError(errorMessage: String) {
        runOnUiThread {
            rawRecordingFilePath?.let { File(it).delete() } // Удаляем сырой файл при ошибке анализа
            playableAudioFilePath?.let { File(it).delete() } // Также удаляем целевой файл, если он создавался

            Toast.makeText(this, "Ошибка анализа: $errorMessage", Toast.LENGTH_LONG).show()
            resetCharacteristicsDisplay()
            binding.playerVisualizerView.updateVisualizer(null)
            updatePlayButtonState()
        }
    }


    private fun startRecording() {
        // Очищаем предыдущие файлы перед новой записью
        clearCacheFiles()
        resetCharacteristicsDisplay()
        binding.playerVisualizerView.updateVisualizer(null)
        binding.playerVisualizerView.updatePlayerPercent(0f)
        mediaPlayer?.release()
        mediaPlayer = null
        stopProgressUpdater()
        updatePlayButtonState() // Кнопка Play должна быть неактивна

        val mr: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP) // Записываем в 3GP
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        // Устанавливаем более высокое качество для последующей обработки, если возможно
        // mr.setAudioSamplingRate(44100) // Опционально, если поддерживается кодеком
        // mr.setAudioEncodingBitRate(96000) // Опционально
        mr.setOutputFile(rawRecordingFilePath) // Записываем в сырой файл

        this.mediaRecorder = mr
        Log.d(TAG, "Attempting to record raw audio to: $rawRecordingFilePath")
        try {
            mr.prepare()
            mr.start()
            isRecording = true
            binding.btnRecord.setImageResource(android.R.drawable.ic_media_pause)
            binding.btnRecord.contentDescription = getString(R.string.stop_recording)
            Toast.makeText(this@VoiceAnalysisActivity, "Запись началась...", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder prepare() failed for $rawRecordingFilePath: ${e.message}")
            // ... (обработка ошибок как раньше)
            Toast.makeText(this@VoiceAnalysisActivity, "Ошибка начала записи: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
            binding.btnRecord.setImageResource(R.drawable.ic_record)
            this.mediaRecorder?.release()
            this.mediaRecorder = null
            rawRecordingFilePath?.let { File(it).delete() } // Удаляем файл, если подготовка не удалась
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaRecorder start failed for $rawRecordingFilePath: ${e.message}")
            // ... (обработка ошибок как раньше)
            Toast.makeText(this@VoiceAnalysisActivity, "Ошибка старта записи: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
            binding.btnRecord.setImageResource(R.drawable.ic_record)
            this.mediaRecorder?.release()
            this.mediaRecorder = null
            rawRecordingFilePath?.let { File(it).delete() } // Удаляем файл
        }
    }

    private fun playRecording() {
        if (isRecording) {
            Toast.makeText(this, "Сначала остановите запись", Toast.LENGTH_SHORT).show()
            return
        }
        val audioFileToPlay = playableAudioFilePath?.let { File(it) } // Воспроизводим обработанный WAV

        if (audioFileToPlay != null && audioFileToPlay.exists() && audioFileToPlay.length() > 0) {
            mediaPlayer?.release()
            stopProgressUpdater()
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(audioFileToPlay.absolutePath) // Используем playableAudioFilePath
                    prepare()
                    start()
                    // Обновление визуализатора для воспроизводимого файла, если он еще не загружен
                    if (binding.playerVisualizerView.getBytes() == null && audioFileToPlay.exists() && audioFileToPlay.length() > 0) {
                        Log.w(TAG, "Visualizer was empty during play, attempting to load bytes for: ${audioFileToPlay.absolutePath}")
                        val audioBytes = fileToBytes(audioFileToPlay)
                        binding.playerVisualizerView.updateVisualizer(audioBytes.takeIf { it.isNotEmpty() })
                    }

                    binding.playerVisualizerView.updatePlayerPercent(0f)
                    startProgressUpdater()
                    Toast.makeText(this@VoiceAnalysisActivity, "Воспроизведение...", Toast.LENGTH_SHORT).show()
                    // ... (setOnCompletionListener, setOnErrorListener как раньше) ...
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

                } catch (e: Exception) { // Ловим более общие исключения
                    Log.e(TAG, "MediaPlayer setup failed: ${e.message}", e)
                    Toast.makeText(this@VoiceAnalysisActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                    release()
                    mediaPlayer = null
                    stopProgressUpdater()
                }
            }
        } else {
            Toast.makeText(this, "Нет записи для воспроизведения", Toast.LENGTH_SHORT).show()
        }
    }
    // ... (остальные методы setupProgressUpdater, fileToBytes, onSupportNavigateUp, onBackPressed,
    //      showAnalysisTypeDialog, checkPermissions, requestPermissions, onRequestPermissionsResult, onStop)
    //      остаются в основном такими же, но onStop должен также очищать файлы, если это необходимо
    //      или останавливать анализ.

    override fun onStop() {
        super.onStop()
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
        stopProgressUpdater()
        audioAnalyzer.stopAnalysis()
        // Не очищаем файлы в onStop, так как пользователь может свернуть приложение и вернуться
        // Очистка происходит в onCreate/onStart и при начале новой записи.
    }

    // --- Методы ниже остаются в основном без изменений ---
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
            BufferedInputStream(FileInputStream(file)).use { buf ->
                buf.read(bytes, 0, bytes.size)
            }
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
                startRecording()
            } else {
                Toast.makeText(this, "Разрешение на запись отклонено", Toast.LENGTH_SHORT).show()
            }
        }
    }
}